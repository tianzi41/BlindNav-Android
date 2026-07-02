/**
 * NavigationMaster.kt - 盲道导航状态机
 * 仅管理盲道导航相关状态：IDLE、BLIND_NAV、OBSTACLE_AVOID、RECOVERY
 * 过马路功能已分离到独立的 CrossStreetManager
 */
package com.blindnav.app.navigation

import android.util.Log
import com.blindnav.app.data.*
import com.blindnav.app.detector.*
import com.blindnav.app.ml.YoloOnnxEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 盲道导航主控制器 - 状态机
 * 管理 IDLE、BLIND_NAV、OBSTACLE_AVOID、RECOVERY 状态
 */
class NavigationMaster(
    private val engine: YoloOnnxEngine
) {
    companion object {
        private const val TAG = "NavigationMaster"

        // 防抖参数
        private const val FRAMES_LOST_MAX = 45

        // 冷却期（秒）
        private const val COOLDOWN_SEC = 0.6f

        // 最小播报间隔（秒）
        private const val MIN_TTS_INTERVAL = 0.6f

        // 周期性提醒间隔（秒）
        private const val PERIODIC_REMINDER_SEC = 3.0f
    }

    // 检测器
    private val blindPathDetector = BlindPathDetector(engine)
    private val itemDetector = ItemDetector(engine)
    private val crosswalkDetector = CrosswalkDetector(engine)
    private val trafficLightDetector = TrafficLightDetector(engine)

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
    private var cntLost = 0

    // 测试模式帧跳过（每 3 帧推理一次，减少卡顿）
    private var testFrameCount = 0
    private val TEST_FRAME_SKIP = 3

    // 缓存上次测试结果（跳帧时复用）
    private var lastTestResult: FrameResult? = null

    // 冷却期
    private var cooldownUntil = 0L

    // 上次播报时间
    private var lastGuidanceTime = 0L

    // 上次播报文本（用于周期性重复提醒）
    private var lastSpokenText = ""

    // 当前引导文本
    var currentGuidance = ""
        private set

    // 当前状态描述
    var currentStatusText = ""
        private set

    /**
     * 处理一帧图像
     * 使用 Mutex 防止并发处理导致状态竞争
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

        return when (currentState) {
            NavigationState.IDLE -> handleIdle(bitmap)
            NavigationState.BLIND_NAV -> handleBlindNav(bitmap, inCooldown)
            NavigationState.ITEM_SEARCH -> handleItemSearch(bitmap)
            NavigationState.OBSTACLE_AVOID -> handleObstacleAvoid(bitmap)
            NavigationState.RECOVERY -> handleRecovery(bitmap)
            NavigationState.CROSSWALK_TEST -> {
                testFrameCount++
                if (testFrameCount % TEST_FRAME_SKIP == 0) {
                    val result = handleCrosswalkTest(bitmap, now)
                    lastTestResult = result
                    result
                } else {
                    lastTestResult ?: handleCrosswalkTest(bitmap, now)
                }
            }
            NavigationState.TRAFFIC_LIGHT_TEST -> {
                testFrameCount++
                if (testFrameCount % TEST_FRAME_SKIP == 0) {
                    val result = handleTrafficLightTest(bitmap, now)
                    lastTestResult = result
                    result
                } else {
                    lastTestResult ?: handleTrafficLightTest(bitmap, now)
                }
            }
            // 过马路相关状态由 CrossStreetManager 处理，不应到达这里
            else -> handleIdle(bitmap)
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
     */
    private fun handleBlindNav(
        bitmap: android.graphics.Bitmap,
        inCooldown: Boolean
    ): FrameResult {
        val segResults = engine.runSegmentation(bitmap)
        val blindResult = blindPathDetector.parseFromSegmentation(segResults)
        Log.d(TAG, "handleBlindNav: blindDetected=${blindResult.detected}, conf=${blindResult.confidence}, cntLost=$cntLost")

        val blindDetections = if (blindResult.detection != null) listOf(blindResult.detection) else emptyList()

        var guidance = ""
        var statusText = "盲道导航中"

        if (blindResult.detected) {
            guidance = blindResult.guidance.audioKey
            cntLost = 0
        } else {
            cntLost++
            Log.d(TAG, "handleBlindNav: 盲道丢失 cntLost=$cntLost/${FRAMES_LOST_MAX}")
            guidance = "丢失路径，重新搜索。"
            if (cntLost >= FRAMES_LOST_MAX) {
                transitionTo(NavigationState.RECOVERY)
            }
        }

        val currentTime = now()
        return FrameResult(
            state = currentState,
            guidance = say(currentTime, guidance),
            statusText = statusText,
            detections = blindDetections
        )
    }

    /**
     * 物品查找状态处理
     */
    private fun handleItemSearch(bitmap: android.graphics.Bitmap): FrameResult {
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
        // 障碍物消失，恢复之前的导航
        transitionTo(previousState)
        val currentTime = now()
        return FrameResult(
            state = currentState,
            guidance = say(currentTime, "避让完成，已回到盲道。"),
            statusText = "盲道导航中"
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

    /**
     * 斑马线检测测试模式
     * 检测到斑马线时播报方位（左移/右移/直行），找不到则播报"找不到斑马线"
     */
    private fun handleCrosswalkTest(bitmap: android.graphics.Bitmap, now: Long): FrameResult {
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

        val guidance = if (crosswalkResult.detected) {
            crosswalkDetector.getAlignmentGuidance(crosswalkResult.centerXRatio)
        } else {
            "找不到斑马线"
        }

        return FrameResult(
            state = NavigationState.CROSSWALK_TEST,
            guidance = say(now, guidance),
            statusText = "斑马线测试: conf=${String.format("%.0f", bestCrosswalk?.confidence?.times(100) ?: 0)}%",
            detections = crosswalkDetections
        )
    }

    /**
     * 红绿灯检测测试模式
     * 检测到红绿灯时持续播报颜色，找不到则播报"找不到红绿灯"
     */
    private fun handleTrafficLightTest(bitmap: android.graphics.Bitmap, now: Long): FrameResult {
        val modelLoaded = engine.isTrafficModelLoaded()
        Log.d(TAG, "TRAFFIC_TEST: trafficModelLoaded=$modelLoaded")

        val tlResult = trafficLightDetector.detect(bitmap)

        Log.d(TAG, "TRAFFIC_TEST: state=${tlResult.state}, stableState=${tlResult.stableState}, conf=${tlResult.confidence}, hasDetection=${tlResult.detection != null}")

        val guidance = when (tlResult.stableState) {
            TrafficLightState.RED -> "红灯"
            TrafficLightState.GREEN -> "绿灯"
            TrafficLightState.YELLOW -> "黄灯"
            else -> "找不到红绿灯"
        }

        return FrameResult(
            state = NavigationState.TRAFFIC_LIGHT_TEST,
            guidance = say(now, guidance),
            statusText = "红绿灯测试: ${tlResult.stableState.name} (model=$modelLoaded)",
            detections = listOfNotNull(tlResult.detection)
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
     * 启动物品查找模式
     */
    fun startItemSearch() {
        if (currentState in listOf(NavigationState.BLIND_NAV)) {
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
        testFrameCount = 0
        lastTestResult = null
        lastSpokenText = ""
        currentStatusText = "就绪"
    }

    /**
     * 启动斑马线测试模式
     */
    fun startCrosswalkTest() {
        Log.i(TAG, "startCrosswalkTest")
        transitionTo(NavigationState.CROSSWALK_TEST)
        testFrameCount = 0
        lastTestResult = null
        lastSpokenText = ""
        currentStatusText = "斑马线测试"
    }

    /**
     * 启动红绿灯测试模式
     */
    fun startTrafficLightTest() {
        Log.i(TAG, "startTrafficLightTest")
        transitionTo(NavigationState.TRAFFIC_LIGHT_TEST)
        testFrameCount = 0
        lastTestResult = null
        lastSpokenText = ""
        currentStatusText = "红绿灯测试"
    }

    // ========== 内部方法 ==========

    private fun transitionTo(newState: NavigationState) {
        previousState = currentState
        currentState = newState
        cooldownUntil = now() + (COOLDOWN_SEC * 1000).toLong()
        Log.i(TAG, "状态切换: ${previousState.name} -> ${newState.name}")
    }

    private fun resetCounters() {
        cntLost = 0
    }

    private fun say(now: Long, text: String): String {
        if (text.isEmpty()) return ""
        val elapsed = now - lastGuidanceTime

        if (text != lastSpokenText) {
            // 新文本：0.6s 节流
            if (elapsed >= MIN_TTS_INTERVAL * 1000) {
                lastGuidanceTime = now
                lastSpokenText = text
                return text
            }
        } else {
            // 相同文本：3s 周期性重复
            if (elapsed >= PERIODIC_REMINDER_SEC * 1000) {
                lastGuidanceTime = now
                return text
            }
        }
        return ""
    }

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
