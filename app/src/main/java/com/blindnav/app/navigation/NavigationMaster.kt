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

        // 已弃用：旧的帧数防抖（保留供 resetCounters 兼容，不再用于判定）
        private const val FRAMES_LOST_MAX = 45

        // 冷却期（秒）
        private const val COOLDOWN_SEC = 0.6f

        // 最小播报间隔（秒）
        private const val MIN_TTS_INTERVAL = 0.6f

        // 周期性提醒间隔（秒）
        private const val PERIODIC_REMINDER_SEC = 3.0f

        // ===== 盲道导航增强：时间阈值（毫秒，避免帧率漂移）=====
        // 短暂丢失下限（< 此值视为抖动，静默）
        private const val LOST_SILENCE_MS = 250L
        // #9 中断判定窗口上限（超过此值转 #12 搜索）
        private const val LOST_BRIEF_MAX_MS = 3000L
        // #12 进入搜索模式阈值
        private const val SEARCH_THRESHOLD_MS = 3000L
        // #12 搜索模式重复提醒间隔
        private const val SEARCH_REMIND_INTERVAL_MS = 10000L
        // #9 中断前必须持续检测到盲道的最短时长
        private const val INTERRUPT_PRE_DETECT_MS = 1000L
        // #9 中断播报冷却
        private const val INTERRUPT_COOLDOWN_MS = 5000L
        // #3 转弯播报冷却
        private const val TURN_COOLDOWN_MS = 10000L
        // #3 转弯判定：centerX 历史窗口（帧数）
        private const val TURN_HISTORY_SIZE = 20
        // #3 转弯判定：centerX 居中区间
        private const val TURN_CENTER_MIN = 0.40f
        private const val TURN_CENTER_MAX = 0.60f
        // #3 转弯判定：centerX 居中时允许的最大方差
        private const val TURN_CENTER_MAX_VAR = 0.05f
        // #3 转弯判定：当前 centerX 进入边缘的阈值
        private const val TURN_EDGE_MIN = 0.75f
        private const val TURN_EDGE_MAX = 0.25f
        // #3 转弯判定：面积保持比例下限（相对近期均值）
        private const val TURN_AREA_KEEP_RATIO = 0.40f
    }

    // 检测器
    private val blindPathDetector = BlindPathDetector(engine)
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

    // 防抖计数器（已弃用，保留兼容）
    private var cntLost = 0

    // ===== 盲道导航增强：内部状态机 =====
    // BLIND_NAV 状态内部的细分阶段，不影响 NavigationState 枚举
    private enum class BlindNavStage {
        NORMAL,       // 正常跟随盲道
        LOST_BRIEF,   // 短暂丢失（250ms~3s），候选 #9 中断
        SEARCHING     // 长时间丢失（>3s），#12 搜索循环
    }
    private var blindNavStage = BlindNavStage.NORMAL

    // 盲道历史追踪器（跨帧持久，NavigationMaster 成员变量）
    private inner class BlindPathTracker {
        // 首次检测到盲道的时间戳（用于 #9 判断"之前持续检测时长"）
        var foundStartMs = 0L
        // 开始丢失的时间戳
        var lostStartMs = 0L
        // 最后一次检测到的时间戳
        var lastFoundMs = 0L
        // 最近 N 帧 centerX（仅 NORMAL 阶段持续追加）
        val recentCenters = ArrayDeque<Float>(TURN_HISTORY_SIZE)
        // 最近 N 帧面积（与 recentCenters 同步维护，避免 areaMean 永远增长）
        val recentAreas = ArrayDeque<Float>(TURN_HISTORY_SIZE)
        // 最近 N 帧面积滑动和（与 recentAreas 保持同步）
        var recentAreaSum = 0f
        // #3 转弯播报冷却到期时间
        var turnCooldownUntil = 0L
        // #9 中断播报冷却到期时间
        var interruptCooldownUntil = 0L
        // #12 搜索模式下最后一次提醒时间
        var searchRemindMs = 0L
        // 丢失前的 centerX 方差（丢失瞬间计算，用于 #9 防误报）
        var preLostCenterVar = -1f

        fun reset() {
            foundStartMs = 0L; lostStartMs = 0L; lastFoundMs = 0L
            recentCenters.clear()
            recentAreas.clear()
            recentAreaSum = 0f
            turnCooldownUntil = 0L; interruptCooldownUntil = 0L
            searchRemindMs = 0L; preLostCenterVar = -1f
        }

        fun addSample(centerX: Float, area: Float) {
            recentCenters.addLast(centerX)
            recentAreas.addLast(area)
            recentAreaSum += area
            while (recentCenters.size > TURN_HISTORY_SIZE) {
                recentCenters.removeFirst()
                recentAreaSum -= recentAreas.removeFirst()
            }
        }

        fun centerMean(): Float {
            if (recentCenters.isEmpty()) return 0.5f
            var sum = 0f
            for (v in recentCenters) sum += v
            return sum / recentCenters.size
        }

        fun centerVariance(): Float {
            val n = recentCenters.size
            if (n < 2) return 0f
            val mean = centerMean()
            var sumSq = 0f
            for (v in recentCenters) { val d = v - mean; sumSq += d * d }
            return sumSq / n
        }

        fun areaMean(): Float {
            return if (recentAreas.isNotEmpty()) recentAreaSum / recentAreas.size else 0f
        }
    }
    private val blindTracker = BlindPathTracker()

    // 盲道导航帧跳过（每 3 帧推理一次，中间帧复用上次结果）
    private var blindNavFrameCount = 0
    private var lastBlindNavResult: FrameResult? = null

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
            NavigationState.BLIND_NAV -> {
                blindNavFrameCount++
                if (blindNavFrameCount % TEST_FRAME_SKIP == 0) {
                    val result = handleBlindNav(bitmap, inCooldown)
                    lastBlindNavResult = result
                    result
                } else {
                    lastBlindNavResult ?: handleBlindNav(bitmap, inCooldown)
                }
            }
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
            NavigationState.LYT_TRAFFIC_LIGHT_TEST -> {
                testFrameCount++
                if (testFrameCount % TEST_FRAME_SKIP == 0) {
                    val result = handleLytTrafficLightTest(bitmap, now)
                    lastTestResult = result
                    result
                } else {
                    lastTestResult ?: handleLytTrafficLightTest(bitmap, now)
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
     * 盲道导航状态处理（增强版）
     * 包含 #9 中断检测、#3 L型转弯检测、#12 搜索循环
     * 导航永不自动停止，只有用户手动按"停止"才退出
     */
    private fun handleBlindNav(
        bitmap: android.graphics.Bitmap,
        inCooldown: Boolean
    ): FrameResult {
        val segResults = engine.runSegmentation(bitmap)
        val now = now()

        // 诊断日志
        Log.d(TAG, "handleBlindNav: segResults=${segResults.size} 个检测, stage=$blindNavStage")
        segResults.take(5).forEachIndexed { i, d ->
            Log.d(TAG, "  seg[$i] class='${d.className}' id=${d.classId} conf=${String.format("%.3f", d.confidence)}")
        }

        val blindResult = blindPathDetector.parseFromSegmentation(segResults)
        Log.d(TAG, "handleBlindNav: blindDetected=${blindResult.detected}, conf=${blindResult.confidence}, stage=$blindNavStage")

        // 收集盲道 + 斑马线检测用于叠加层显示
        val allDetections = mutableListOf<DetectionResult>()
        blindResult.detection?.let { allDetections.add(it) }
        segResults.filter {
            (it.classId == 0 || it.className == "crosswalk") && it.confidence >= 0.30f
        }.forEach { allDetections.add(it) }

        var guidance = ""
        var statusText = "盲道导航中"

        if (blindResult.detected) {
            // ===== 盲道检测到 =====
            blindTracker.lastFoundMs = now

            when (blindNavStage) {
                BlindNavStage.NORMAL -> {
                    // 正常跟随：更新 tracker + 检测转弯
                    blindTracker.addSample(blindResult.centerXRatio, blindResult.areaRatio)
                    if (blindTracker.foundStartMs == 0L) {
                        blindTracker.foundStartMs = now
                    }

                    // #3 L型转弯检测：先检查条件，再播报
                    val turnDirection = checkTurnConditions(blindResult)
                    if (turnDirection != 0) {
                        // 转弯条件满足：尝试播报（可能被 say() 节流）
                        val direction = if (turnDirection > 0) "前方右转，请跟随。" else "前方左转，请跟随。"
                        guidance = say(now, direction)
                        if (guidance.isNotEmpty()) {
                            blindTracker.turnCooldownUntil = now + TURN_COOLDOWN_MS
                            Log.i(TAG, "#3 L型转弯: $direction")
                        }
                        // 如果被节流，guidance 为空，不回退到常规引导
                    } else {
                        guidance = say(now, blindResult.guidance.audioKey)
                    }
                }

                BlindNavStage.LOST_BRIEF -> {
                    // 从短暂丢失恢复：检查是否为 #9 中断
                    val lostDuration = now - blindTracker.lostStartMs
                    if (checkInterruptionConditions(lostDuration)) {
                        guidance = say(now, "前方盲道中断，请小心通过。")
                        if (guidance.isNotEmpty()) {
                            blindTracker.interruptCooldownUntil = now + INTERRUPT_COOLDOWN_MS
                            Log.i(TAG, "#9 盲道中断: lostDuration=${lostDuration}ms")
                        }
                    } else {
                        guidance = say(now, blindResult.guidance.audioKey)
                    }
                    // 恢复到 NORMAL，重置采样历史
                    blindNavStage = BlindNavStage.NORMAL
                    blindTracker.foundStartMs = now
                    blindTracker.recentCenters.clear()
                    blindTracker.recentAreaSum = 0f
                    blindTracker.recentAreas.clear()
                    blindTracker.recentAreaSum = 0f
                    blindTracker.addSample(blindResult.centerXRatio, blindResult.areaRatio)
                    blindTracker.preLostCenterVar = -1f
                }

                BlindNavStage.SEARCHING -> {
                    // 从搜索模式恢复
                    val centerX = blindResult.centerXRatio
                    if (centerX in TURN_CENTER_MIN..TURN_CENTER_MAX) {
                        // 盲道居中：恢复正常导航
                        guidance = say(now, "盲道恢复，继续前行。")
                        blindNavStage = BlindNavStage.NORMAL
                        blindTracker.reset()
                        blindTracker.foundStartMs = now
                        blindTracker.addSample(centerX, blindResult.areaRatio)
                        statusText = "盲道导航中"
                    } else {
                        // 盲道在侧面：播报方位
                        val sideGuidance = if (centerX < 0.5f) "盲道在左侧。" else "盲道在右侧。"
                        guidance = say(now, sideGuidance)
                        statusText = "搜索中 - 盲道在${if (centerX < 0.5f) "左" else "右"}侧"
                    }
                }
            }
        } else {
            // ===== 盲道未检测到 =====
            val lostDuration: Long
            when (blindNavStage) {
                BlindNavStage.NORMAL -> {
                    // 刚开始丢失
                    blindTracker.lostStartMs = now
                    // 计算丢失前的 centerX 方差（用于 #9 防误报）
                    blindTracker.preLostCenterVar = blindTracker.centerVariance()
                    blindNavStage = BlindNavStage.LOST_BRIEF
                    lostDuration = 0L
                    guidance = ""  // 短暂丢失不播报，避免抖动
                }
                BlindNavStage.LOST_BRIEF -> {
                    lostDuration = now - blindTracker.lostStartMs
                    if (lostDuration >= SEARCH_THRESHOLD_MS) {
                        // 超过 3 秒：进入搜索模式
                        blindNavStage = BlindNavStage.SEARCHING
                        blindTracker.searchRemindMs = now
                        guidance = say(now, "未检测到盲道，请调整方向寻找。")
                        statusText = "搜索中 - 未检测到盲道"
                    } else {
                        // 仍在短暂丢失窗口内，静默
                        guidance = ""
                    }
                }
                BlindNavStage.SEARCHING -> {
                    // 已在搜索模式：每 10 秒重复提醒
                    lostDuration = now - blindTracker.lostStartMs
                    if (now - blindTracker.searchRemindMs >= SEARCH_REMIND_INTERVAL_MS) {
                        blindTracker.searchRemindMs = now
                        guidance = say(now, "未检测到盲道，请调整方向寻找。")
                    } else {
                        guidance = ""
                    }
                    statusText = "搜索中 - ${lostDuration / 1000}秒"
                }
            }
            Log.d(TAG, "handleBlindNav: 盲道丢失 stage=$blindNavStage, lostDuration=${lostDuration}ms")
        }

        return FrameResult(
            state = currentState,
            guidance = guidance,
            statusText = statusText,
            detections = allDetections
        )
    }

    /**
     * #9 盲道中断条件检查
     * 条件：丢失前持续检测 ≥1s、丢失时长 250ms~3s、丢失前 centerX 方差小（稳定行走）
     * @return true=满足中断条件，false=不满足
     */
    private fun checkInterruptionConditions(lostDuration: Long): Boolean {
        // 冷却期内不重复检测
        if (now() < blindTracker.interruptCooldownUntil) return false

        // 丢失前必须持续检测到盲道
        val preDetectDuration = blindTracker.lostStartMs - blindTracker.foundStartMs
        if (preDetectDuration < INTERRUPT_PRE_DETECT_MS) return false

        // 丢失时长必须在窗口内
        if (lostDuration < LOST_SILENCE_MS || lostDuration > LOST_BRIEF_MAX_MS) return false

        // 丢失前 centerX 方差过大（用户在走动/晃动）→ 不报
        if (blindTracker.preLostCenterVar > TURN_CENTER_MAX_VAR) return false

        return true
    }

    /**
     * #3 L型转弯条件检查
     * 条件：最近20帧 centerX 均值在 0.4~0.6 且方差<0.05（稳定居中），
     *       当前帧 centerX 跳到边缘（<0.25 或 >0.75），面积保持>40%近期均值
     * @return -1=左转, 0=无转弯, 1=右转
     */
    private fun checkTurnConditions(blindResult: BlindPathDetector.BlindPathResult): Int {
        // 冷却期内不重复检测
        if (now() < blindTracker.turnCooldownUntil) return 0

        // 需要足够的历史数据
        if (blindTracker.recentCenters.size < TURN_HISTORY_SIZE) return 0

        val mean = blindTracker.centerMean()
        val variance = blindTracker.centerVariance()
        val currentX = blindResult.centerXRatio
        val areaMean = blindTracker.areaMean()

        // 历史必须稳定居中
        if (mean !in TURN_CENTER_MIN..TURN_CENTER_MAX) return 0
        if (variance > TURN_CENTER_MAX_VAR) return 0

        // 当前帧跳到边缘
        val isLeftEdge = currentX <= TURN_EDGE_MAX
        val isRightEdge = currentX >= TURN_EDGE_MIN
        if (!isLeftEdge && !isRightEdge) return 0

        // 面积不能大幅缩小（排除盲道变窄/移出视野）
        if (areaMean > 0 && blindResult.areaRatio < areaMean * TURN_AREA_KEEP_RATIO) return 0

        return if (isRightEdge) 1 else -1
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
        val modelLoaded = engine.isClaModelLoaded()
        Log.d(TAG, "TRAFFIC_TEST: claModelLoaded=$modelLoaded")

        val tlResult = trafficLightDetector.detect(bitmap)

        Log.d(TAG, "TRAFFIC_TEST: state=${tlResult.state}, stableState=${tlResult.stableState}, conf=${tlResult.confidence}, className=${tlResult.className}")

        val guidance = when (tlResult.stableState) {
            TrafficLightState.RED -> "红灯"
            TrafficLightState.GREEN -> "绿灯"
            else -> "找不到灯"
        }

        return FrameResult(
            state = NavigationState.TRAFFIC_LIGHT_TEST,
            guidance = say(now, guidance),
            statusText = "红绿灯: ${tlResult.stableState.name}  红=${(tlResult.redConf * 100).toInt()}% 绿=${(tlResult.greenConf * 100).toInt()}%",
            detections = emptyList()
        )
    }

    /**
     * 红绿灯检测测试模式 (LYTNetV2)
     */
    private fun handleLytTrafficLightTest(bitmap: android.graphics.Bitmap, now: Long): FrameResult {
        val modelLoaded = engine.isLytModelLoaded()
        Log.d(TAG, "TRAFFIC_LYT_TEST: lytModelLoaded=$modelLoaded")

        val tlResult = trafficLightDetector.detectWithLyt(bitmap)
        Log.d(TAG, "TRAFFIC_LYT_TEST: state=${tlResult.state}, stableState=${tlResult.stableState}, conf=${tlResult.confidence}, className=${tlResult.className}")

        val guidance = when (tlResult.stableState) {
            TrafficLightState.RED -> "红灯"
            TrafficLightState.GREEN -> "绿灯"
            else -> "找不到灯"
        }

        return FrameResult(
            state = NavigationState.LYT_TRAFFIC_LIGHT_TEST,
            guidance = say(now, guidance),
            statusText = "LYTNet: ${tlResult.stableState.name}  红=${(tlResult.redConf * 100).toInt()}% 绿=${(tlResult.greenConf * 100).toInt()}% 无=${(tlResult.noneConf * 100).toInt()}%",
            detections = emptyList()
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
        blindNavStage = BlindNavStage.NORMAL
        blindTracker.reset()
        currentStatusText = "盲道导航中"
    }

    /**
     * 停止导航
     */
    fun stopNavigation() {
        Log.i(TAG, "stopNavigation")
        transitionTo(NavigationState.IDLE)
        resetCounters()
        blindNavStage = BlindNavStage.NORMAL
        blindTracker.reset()
        testFrameCount = 0
        lastTestResult = null
        blindNavFrameCount = 0
        lastBlindNavResult = null
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
        currentStatusText = "红绿灯测试(ResNet)"
    }

    /**
     * 启动红绿灯测试模式 (LYTNetV2)
     */
    fun startLytTrafficLightTest() {
        Log.i(TAG, "startLytTrafficLightTest")
        transitionTo(NavigationState.LYT_TRAFFIC_LIGHT_TEST)
        testFrameCount = 0
        lastTestResult = null
        lastSpokenText = ""
        currentStatusText = "红绿灯测试(LYTNet)"
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
