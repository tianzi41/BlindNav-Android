/**
 * NavigationMaster.kt - 导航状态机主控制器
 * 管理所有导航状态的切换，协调各工作流
 */
package com.blindnav.app.navigation

import android.util.Log
import com.blindnav.app.data.*
import com.blindnav.app.detector.*
import com.blindnav.app.ml.YoloOnnxEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 导航主控制器 - 状态机
 * 管理 IDLE、BLIND_NAV、CROSS_STREET、ITEM_SEARCH、OBSTACLE_AVOID 等状态
 */
class NavigationMaster(
    private val engine: YoloOnnxEngine
) {
    companion object {
        private const val TAG = "NavigationMaster"

        // 防抖参数
        private const val FRAMES_CROSS_SEEN = 8
        private const val FRAMES_ALIGN_READY = 12
        private const val FRAMES_CROSS_END = 12
        private const val FRAMES_LOST_MAX = 45

        // 对准阈值
        private const val ANGLE_ALIGN_THR_DEG = 12.0f
        private const val OFFSET_ALIGN_THR = 0.15f

        // 冷却期（秒）
        private const val COOLDOWN_SEC = 0.6f

        // 最小播报间隔（秒）
        private const val MIN_TTS_INTERVAL = 1.2f
    }

    // 各检测器
    private val blindPathDetector = BlindPathDetector(engine)
    private val obstacleDetector = ObstacleDetector(engine)
    private val trafficLightDetector = TrafficLightDetector(engine)
    private val crosswalkDetector = CrosswalkDetector(engine)
    private val itemDetector = ItemDetector(engine)

    // 线程安全锁
    private val processMutex = Mutex()

    // 当前状态
    @Volatile
    var currentState = NavigationState.IDLE
        private set

    // 上一个状态（用于恢复）
    private var previousState = NavigationState.IDLE

    // 找物品模式前的导航状态
    private var prevNavStateBeforeSearch: NavigationState? = null

    // 防抖计数器
    private var cntCrosswalkSeen = 0
    private var cntAlignReady = 0
    private var cntCrossEnd = 0
    private var cntLost = 0

    // 冷却期
    private var cooldownUntil = 0L

    // 障碍物检测帧计数器（降低频率，每6帧检测一次以节省性能）
    private var obstacleFrameCount = 0
    private val OBSTACLE_DETECT_INTERVAL = 6

    // 上次播报时间
    private var lastGuidanceTime = 0L

    // 当前引导文本
    var currentGuidance = ""
        private set

    // 当前状态描述
    var currentStatusText = ""
        private set

    /**
     * 处理一帧图像
     * 使用 Mutex 防止并发处理导致状态竞争
     * @param bitmap 输入图像
     * @return 帧处理结果
     */
    suspend fun processFrame(bitmap: android.graphics.Bitmap): FrameResult {
        return processMutex.withLock {
            processFrameInternal(bitmap)
        }
    }

    /**
     * 内部帧处理（在 Mutex 保护下执行）
     */
    private fun processFrameInternal(bitmap: android.graphics.Bitmap): FrameResult {
        val now = System.currentTimeMillis()
        val inCooldown = now < cooldownUntil
        Log.d(TAG, "processFrame: state=${currentState.name}, inCooldown=$inCooldown")

        // YOLO 障碍物检测（降频执行，跳过 BLIND_NAV/ITEM_SEARCH 状态以避免双重推理导致帧率过低）
        if (currentState != NavigationState.IDLE &&
            currentState != NavigationState.BLIND_NAV &&
            currentState != NavigationState.ITEM_SEARCH &&
            currentState != NavigationState.OBSTACLE_AVOID) {
            obstacleFrameCount++
            if (obstacleFrameCount >= OBSTACLE_DETECT_INTERVAL) {
                obstacleFrameCount = 0
                val obstacleResult = obstacleDetector.detect(bitmap)
                if (obstacleResult.hasNearObstacle) {
                    return FrameResult(
                        state = currentState,
                        guidance = obstacleResult.audioPrompt,
                        statusText = "检测到障碍物"
                    )
                }
            }
        }

        return when (currentState) {
            NavigationState.IDLE -> handleIdle(bitmap)
            NavigationState.BLIND_NAV -> handleBlindNav(bitmap, inCooldown)
            NavigationState.CROSS_STREET -> handleCrossStreet(bitmap, inCooldown)
            NavigationState.ITEM_SEARCH -> handleItemSearch(bitmap)
            NavigationState.OBSTACLE_AVOID -> handleObstacleAvoid(bitmap)
            NavigationState.WAIT_TRAFFIC_LIGHT -> handleWaitTrafficLight(bitmap, inCooldown)
            NavigationState.SEEKING_CROSSWALK -> handleSeekingCrosswalk(bitmap, inCooldown)
            NavigationState.SEEKING_NEXT_BLINDPATH -> handleSeekingNextBlindpath(bitmap, inCooldown)
            NavigationState.RECOVERY -> handleRecovery(bitmap)
        }
    }

    /**
     * 空闲状态处理
     */
    private fun handleIdle(bitmap: android.graphics.Bitmap): FrameResult {
        return FrameResult(
            state = NavigationState.IDLE,
            guidance = "",
            statusText = "就绪 - 请选择导航模式"
        )
    }

    /**
     * 盲道导航状态处理
     * 优化：只运行一次分割推理，结果同时用于盲道和斑马线检测
     */
    private fun handleBlindNav(
        bitmap: android.graphics.Bitmap,
        inCooldown: Boolean
    ): FrameResult {
        // 运行一次分割推理，结果同时用于盲道和斑马线
        val segResults = engine.runSegmentation(bitmap)

        // 统一用 detector 解析，与 handleSeekingNextBlindpath/handleRecovery 使用相同逻辑
        val blindResult = blindPathDetector.parseFromSegmentation(segResults)
        Log.d(TAG, "handleBlindNav: segResults=${segResults.size}, blindDetected=${blindResult.detected}, conf=${blindResult.confidence}, area=${blindResult.areaRatio}, centerX=${blindResult.centerXRatio}, cntLost=$cntLost")

        // 斑马线：从分割结果中提取（置信度 >= 0.30 过滤低置信度噪声）
        val crosswalkDetections = segResults.filter {
            (it.classId == 0 || it.className == "crosswalk") && it.confidence >= 0.30f
        }
        val bestCrosswalk = crosswalkDetections.maxByOrNull { it.confidence }
        val crosswalkResult = if (bestCrosswalk != null) {
            crosswalkDetector.buildResult(bestCrosswalk)
        } else {
            CrosswalkDetector.CrosswalkResult(
                detected = false, stage = CrosswalkDetector.CrosswalkStage.NOT_DETECTED
            )
        }

        // 盲道检测结果用于界面叠加
        val blindDetections = if (blindResult.detection != null) listOf(blindResult.detection) else emptyList()

        var guidance = ""
        var statusText = "盲道导航中"

        if (blindResult.detected) {
            guidance = blindResult.guidance.audioKey
            statusText = "盲道导航中"

            // 检查是否发现斑马线
            if (crosswalkResult.detected &&
                crosswalkResult.stage in listOf(
                    CrosswalkDetector.CrosswalkStage.APPROACHING,
                    CrosswalkDetector.CrosswalkStage.READY
                )
            ) {
                cntCrosswalkSeen++
                Log.d(TAG, "handleBlindNav: 发现斑马线 cntCrosswalkSeen=$cntCrosswalkSeen/${FRAMES_CROSS_SEEN}")
                if (cntCrosswalkSeen >= FRAMES_CROSS_SEEN && !inCooldown) {
                    transitionTo(NavigationState.SEEKING_CROSSWALK)
                    guidance = "正在接近斑马线，为您对准方向。"
                }
            } else {
                cntCrosswalkSeen = maxOf(0, cntCrosswalkSeen - 1)
            }
        } else {
            cntLost++
            Log.d(TAG, "handleBlindNav: 盲道丢失 cntLost=$cntLost/${FRAMES_LOST_MAX}")
            guidance = "丢失路径，重新搜索。"
            if (cntLost >= FRAMES_LOST_MAX) {
                transitionTo(NavigationState.RECOVERY)
            }
        }

        val currentTime = now()
        // 盲道丢失时只返回斑马线检测，不返回旧的盲道检测
        val returnDetections = if (blindResult.detected) {
            blindDetections + crosswalkDetections
        } else {
            crosswalkDetections  // 盲道丢失，清空盲道检测框
        }
        return FrameResult(
            state = currentState,
            guidance = say(currentTime, guidance),
            statusText = statusText,
            detections = returnDetections
        )
    }

    /**
     * 过马路状态处理
     * 优化：只运行一次分割推理，结果同时用于斑马线和盲道检测
     */
    private fun handleCrossStreet(
        bitmap: android.graphics.Bitmap,
        inCooldown: Boolean
    ): FrameResult {
        // 运行一次分割推理，结果同时用于斑马线和盲道
        val segResults = engine.runSegmentation(bitmap)

        val crosswalkDetections = segResults.filter {
            (it.classId == 0 || it.className == "crosswalk") && it.confidence >= 0.30f
        }
        val blindDetections = segResults.filter {
            (it.classId == 1 || it.className == "blind_path") &&
            it.confidence >= 0.15f
        }

        val bestCrosswalk = crosswalkDetections.maxByOrNull { it.confidence }
        val crosswalkResult = if (bestCrosswalk != null) {
            crosswalkDetector.buildResult(bestCrosswalk)
        } else {
            CrosswalkDetector.CrosswalkResult(
                detected = false, stage = CrosswalkDetector.CrosswalkStage.NOT_DETECTED
            )
        }

        val bestBlind = blindDetections.maxByOrNull { it.confidence }
        val blindAreaRatio = bestBlind?.boundingBox?.area ?: 0f
        val blindDetected = bestBlind != null && blindAreaRatio > 0.05f

        Log.d(TAG, "handleCrossStreet: crossDetected=${crosswalkResult.detected}, blindDetected=$blindDetected, cntCrossEnd=$cntCrossEnd")
        var guidance = ""
        var statusText = "过马路中"

        if (crosswalkResult.detected) {
            // 检查是否到达对岸（斑马线消失或盲道出现）
            if (blindDetected) {
                cntCrossEnd++
                if (cntCrossEnd >= FRAMES_CROSS_END && !inCooldown) {
                    transitionTo(NavigationState.SEEKING_NEXT_BLINDPATH)
                    guidance = "过马路结束，准备上人行道。"
                }
            } else {
                guidance = crosswalkResult.detection?.let { "继续前行" } ?: ""
                cntCrossEnd = maxOf(0, cntCrossEnd - 1)
            }

            // 对准引导
            if (!crosswalkResult.isAligned) {
                guidance = crosswalkDetector.getAlignmentGuidance(crosswalkResult.centerXRatio)
            }
        } else {
            // 斑马线丢失，可能已过完马路
            cntCrossEnd++
            if (cntCrossEnd >= FRAMES_CROSS_END && !inCooldown) {
                transitionTo(NavigationState.SEEKING_NEXT_BLINDPATH)
                guidance = "过马路结束，准备上人行道。"
            }
        }

        val currentTime = now()
        return FrameResult(
            state = currentState,
            guidance = say(currentTime, guidance),
            statusText = statusText,
            detections = crosswalkDetections + blindDetections
        )
    }

    /**
     * 物品查找状态处理
     */
    private fun handleItemSearch(bitmap: android.graphics.Bitmap): FrameResult {
        // 物品查找由 MainViewModel 单独处理
        return FrameResult(
            state = NavigationState.ITEM_SEARCH,
            guidance = "",
            statusText = "物品查找模式"
        )
    }

    /**
     * 障碍物避障处理
     */
    private fun handleObstacleAvoid(bitmap: android.graphics.Bitmap): FrameResult {
        val obstacleResult = obstacleDetector.detect(bitmap)

        val guidance = if (obstacleResult.hasNearObstacle) {
            obstacleResult.audioPrompt
        } else {
            // 障碍物消失，恢复之前的导航
            transitionTo(previousState)
            "避让完成，已回到盲道。"
        }

        val currentTime = now()
        return FrameResult(
            state = currentState,
            guidance = say(currentTime, guidance),
            statusText = "避障中"
        )
    }

    /**
     * 等待红绿灯处理
     */
    private fun handleWaitTrafficLight(
        bitmap: android.graphics.Bitmap,
        inCooldown: Boolean
    ): FrameResult {
        val tlResult = trafficLightDetector.detect(bitmap)

        var guidance = ""
        var statusText = "等待绿灯"

        Log.d(TAG, "handleWaitTrafficLight: state=${tlResult.stableState}")
        when (tlResult.stableState) {
            TrafficLightState.GREEN -> {
                if (!inCooldown) {
                    transitionTo(NavigationState.CROSS_STREET)
                    guidance = "绿灯稳定，开始通行。"
                    statusText = "绿灯 - 开始通行"
                }
            }
            TrafficLightState.RED -> {
                guidance = "红灯"
                statusText = "等待绿灯 - 红灯"
            }
            TrafficLightState.YELLOW -> {
                guidance = "黄灯"
                statusText = "等待绿灯 - 黄灯"
            }
            else -> {
                statusText = "等待绿灯"
            }
        }

        val currentTime = now()
        return FrameResult(
            state = currentState,
            guidance = say(currentTime, guidance),
            statusText = statusText,
            detections = listOfNotNull(tlResult.detection)
        )
    }

    /**
     * 斑马线对准处理
     */
    private fun handleSeekingCrosswalk(
        bitmap: android.graphics.Bitmap,
        inCooldown: Boolean
    ): FrameResult {
        val segResults = engine.runSegmentation(bitmap)
        val crosswalkDetections = segResults.filter {
            (it.classId == 0 || it.className == "crosswalk") && it.confidence >= 0.30f
        }
        val bestCrosswalk = crosswalkDetections.maxByOrNull { it.confidence }
        val crosswalkResult = if (bestCrosswalk != null) {
            crosswalkDetector.buildResult(bestCrosswalk)
        } else {
            CrosswalkDetector.CrosswalkResult(
                detected = false, stage = CrosswalkDetector.CrosswalkStage.NOT_DETECTED
            )
        }

        Log.d(TAG, "handleSeekingCrosswalk: detected=${crosswalkResult.detected}, aligned=${crosswalkResult.isAligned}, cntAlignReady=$cntAlignReady/${FRAMES_ALIGN_READY}")
        var guidance = ""
        var statusText = "对准斑马线"

        if (crosswalkResult.detected && crosswalkResult.isAligned) {
            cntAlignReady++
            guidance = crosswalkDetector.getAlignmentGuidance(crosswalkResult.centerXRatio)

            if (cntAlignReady >= FRAMES_ALIGN_READY && !inCooldown) {
                transitionTo(NavigationState.WAIT_TRAFFIC_LIGHT)
                guidance = "已到达斑马线，请等待红绿灯。"
            }
        } else {
            cntAlignReady = maxOf(0, cntAlignReady - 1)
            if (crosswalkResult.detected) {
                guidance = crosswalkDetector.getAlignmentGuidance(crosswalkResult.centerXRatio)
            }
        }

        val currentTime = now()
        return FrameResult(
            state = currentState,
            guidance = say(currentTime, guidance),
            statusText = statusText,
            detections = crosswalkDetections
        )
    }

    /**
     * 寻找下一段盲道处理
     */
    private fun handleSeekingNextBlindpath(
        bitmap: android.graphics.Bitmap,
        inCooldown: Boolean
    ): FrameResult {
        val blindResult = blindPathDetector.detect(bitmap)

        var guidance = ""
        var statusText = "寻找盲道"

        if (blindResult.detected && blindResult.areaRatio > 0.02f) {
            cntCrossEnd++
            if (cntCrossEnd >= FRAMES_CROSS_END && !inCooldown) {
                transitionTo(NavigationState.BLIND_NAV)
                guidance = "方向正确，请继续前进。"
            }
        } else {
            cntCrossEnd = maxOf(0, cntCrossEnd - 1)
        }

        val currentTime = now()
        return FrameResult(
            state = currentState,
            guidance = say(currentTime, guidance),
            statusText = statusText
        )
    }

    /**
     * 恢复模式处理
     */
    private fun handleRecovery(bitmap: android.graphics.Bitmap): FrameResult {
        val blindResult = blindPathDetector.detect(bitmap)

        if (blindResult.detected && blindResult.areaRatio > 0.02f) {
            Log.i(TAG, "handleRecovery: 盲道已找到, conf=${blindResult.confidence}")
            transitionTo(NavigationState.BLIND_NAV)
            val currentTime = now()
            return FrameResult(
                state = currentState,
                guidance = say(currentTime, "已回到盲道。"),
                statusText = "盲道导航中"
            )
        }

        return FrameResult(
            state = NavigationState.RECOVERY,
            guidance = "",
            statusText = "恢复中 - 请缓慢环顾"
        )
    }

    // ========== 状态切换命令 ==========

    /**
     * 启动盲道导航
     */
    fun startBlindPathNavigation() {
        Log.i(TAG, "startBlindPathNavigation")
        transitionTo(NavigationState.BLIND_NAV)
        resetCounters()
        currentStatusText = "盲道导航中"
    }

    /**
     * 启动过马路模式
     */
    fun startCrossStreet() {
        Log.i(TAG, "startCrossStreet")
        transitionTo(NavigationState.SEEKING_CROSSWALK)
        resetCounters()
        currentStatusText = "过马路模式"
    }

    /**
     * 启动物品查找模式
     */
    fun startItemSearch() {
        if (currentState in listOf(
                NavigationState.BLIND_NAV,
                NavigationState.SEEKING_CROSSWALK,
                NavigationState.WAIT_TRAFFIC_LIGHT,
                NavigationState.CROSS_STREET,
                NavigationState.SEEKING_NEXT_BLINDPATH
            )
        ) {
            prevNavStateBeforeSearch = currentState
        }
        transitionTo(NavigationState.ITEM_SEARCH)
        currentStatusText = "物品查找模式"
    }

    /**
     * 停止物品查找
     */
    fun stopItemSearch(restoreNav: Boolean = true) {
        if (restoreNav && prevNavStateBeforeSearch != null) {
            transitionTo(prevNavStateBeforeSearch!!)
            prevNavStateBeforeSearch = null
        } else {
            transitionTo(NavigationState.IDLE)
        }
    }

    /**
     * 停止导航
     */
    fun stopNavigation() {
        Log.i(TAG, "stopNavigation")
        transitionTo(NavigationState.IDLE)
        resetCounters()
        currentStatusText = "就绪"
    }

    // ========== 内部方法 ==========

    /**
     * 状态切换
     */
    private fun transitionTo(newState: NavigationState) {
        previousState = currentState
        currentState = newState
        cooldownUntil = now() + (COOLDOWN_SEC * 1000).toLong()
        Log.i(TAG, "状态切换: ${previousState.name} -> ${newState.name}")
    }

    /**
     * 重置防抖计数器
     */
    private fun resetCounters() {
        cntCrosswalkSeen = 0
        cntAlignReady = 0
        cntCrossEnd = 0
        cntLost = 0
        obstacleFrameCount = 0
    }

    /**
     * 节流播报
     */
    private fun say(now: Long, text: String): String {
        if (text.isEmpty()) return ""
        if (now - lastGuidanceTime >= MIN_TTS_INTERVAL * 1000) {
            lastGuidanceTime = now
            return text
        }
        return ""
    }

    /**
     * 获取当前时间戳
     */
    private fun now() = System.currentTimeMillis()

    /**
     * 帧处理结果
     */
    data class FrameResult(
        val state: NavigationState,
        val guidance: String,
        val statusText: String,
        val detections: List<com.blindnav.app.data.DetectionResult> = emptyList()
    )
}
