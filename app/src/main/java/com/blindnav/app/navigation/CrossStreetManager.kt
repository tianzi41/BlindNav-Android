/**
 * CrossStreetManager.kt - 独立的过马路状态机
 * 与盲道导航完全解耦，简化的三阶段流转
 *
 * 状态流转：
 *   SEEKING_CROSSWALK → WAIT_TRAFFIC_LIGHT → CROSSING → IDLE
 *   (任何阶段目标丢失 → 回退到 SEEKING_CROSSWALK 或 IDLE)
 */
package com.blindnav.app.navigation

import android.util.Log
import com.blindnav.app.data.*
import com.blindnav.app.detector.*
import com.blindnav.app.ml.YoloOnnxEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 过马路独立状态机
 */
class CrossStreetManager(
    private val engine: YoloOnnxEngine
) {
    companion object {
        private const val TAG = "CrossStreetManager"

        // 防抖参数
        private const val FRAMES_CROSS_END = 12       // 到达确认帧数
        private const val FRAMES_GREEN_STABLE = 5     // 绿灯稳定帧数（防误判闯红灯）
        private const val FRAMES_LOST = 8             // 目标丢失确认帧数

        // 冷却期（秒）
        private const val COOLDOWN_SEC = 0.6f

        // 最小播报间隔（秒）
        private const val MIN_TTS_INTERVAL = 0.6f

        // 周期性提醒间隔（秒）—— 同一状态下每隔此时间重复播报，避免沉默
        private const val PERIODIC_REMINDER_SEC = 3.0f
    }

    // 过马路内部状态
    enum class CrossState {
        IDLE,                  // 未激活
        SEEKING_CROSSWALK,     // 寻找斑马线
        WAIT_TRAFFIC_LIGHT,    // 等待绿灯
        CROSSING               // 通行中
    }

    // 检测器
    private val crosswalkDetector = CrosswalkDetector(engine)
    private val trafficLightDetector = TrafficLightDetector(engine)

    // 线程安全锁
    private val processMutex = Mutex()

    // 当前状态
    @Volatile
    var currentState = CrossState.IDLE
        private set

    // 防抖计数器
    private var cntCrossEnd = 0
    private var cntGreenStable = 0
    private var cntLost = 0

    // 冷却期
    private var cooldownUntil = 0L

    // 上次播报时间
    private var lastGuidanceTime = 0L

    // 上次播报的文本（用于周期性重复提醒）
    private var lastSpokenText = ""

    // 是否正在过马路
    val isActive: Boolean get() = currentState != CrossState.IDLE

    // 当前状态描述
    var currentStatusText = ""
        private set

    /**
     * 处理一帧图像（过马路专用）
     */
    suspend fun processFrame(bitmap: android.graphics.Bitmap): FrameResult {
        return processMutex.withLock {
            processFrameInternal(bitmap)
        }
    }

    private fun processFrameInternal(bitmap: android.graphics.Bitmap): FrameResult {
        val now = System.currentTimeMillis()
        val inCooldown = now < cooldownUntil

        return when (currentState) {
            CrossState.IDLE -> FrameResult(
                state = NavigationState.IDLE,
                guidance = "",
                statusText = "就绪"
            )
            CrossState.SEEKING_CROSSWALK -> handleSeekingCrosswalk(bitmap, now)
            CrossState.WAIT_TRAFFIC_LIGHT -> handleWaitTrafficLight(bitmap, inCooldown, now)
            CrossState.CROSSING -> handleCrossing(bitmap, inCooldown, now)
        }
    }

    /**
     * 寻找斑马线
     * 检测到斑马线后直接输出方位并进入等灯状态，不需要对准过程
     */
    private fun handleSeekingCrosswalk(
        bitmap: android.graphics.Bitmap,
        now: Long
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

        Log.d(TAG, "SEEKING: detected=${crosswalkResult.detected}, centerX=${crosswalkResult.centerXRatio}")

        var guidance = ""
        var statusText = "寻找斑马线"

        if (crosswalkResult.detected) {
            // 检测到斑马线：输出一次当前方位，直接进入等灯
            val direction = crosswalkDetector.getAlignmentGuidance(crosswalkResult.centerXRatio)
            guidance = "发现斑马线。$direction"
            transitionTo(CrossState.WAIT_TRAFFIC_LIGHT)
            statusText = "等待红绿灯"
        } else {
            guidance = "请向前寻找斑马线。"
        }

        return FrameResult(
            state = NavigationState.SEEKING_CROSSWALK,
            guidance = say(now, guidance),
            statusText = statusText,
            detections = crosswalkDetections
        )
    }

    /**
     * 等待绿灯（只跑红绿灯模型，不跑分割，避免双推理导致卡死）
     * 丢失判断：红绿灯模型连续返回 UNKNOWN 超过 FRAMES_LOST 帧 → 回退到 SEEKING
     */
    private fun handleWaitTrafficLight(
        bitmap: android.graphics.Bitmap,
        inCooldown: Boolean,
        now: Long
    ): FrameResult {
        // 只运行红绿灯模型（167MB），不运行分割模型（274MB）
        val tlResult = trafficLightDetector.detect(bitmap)

        Log.d(TAG, "WAIT_LIGHT: tlState=${tlResult.stableState}, cntGreen=$cntGreenStable/$FRAMES_GREEN_STABLE, cntLost=$cntLost/$FRAMES_LOST")

        var guidance = ""
        var statusText = "等待绿灯"

        // 红绿灯持续无法检测 → 目标丢失，回退到 SEEKING
        if (tlResult.stableState == TrafficLightState.UNKNOWN) {
            cntLost++
            guidance = "找不到灯"
            if (cntLost >= FRAMES_LOST) {
                Log.w(TAG, "WAIT_LIGHT: 红绿灯丢失，回退到 SEEKING")
                cntLost = 0
                cntGreenStable = 0
                transitionTo(CrossState.SEEKING_CROSSWALK)
                guidance = "红绿灯丢失，请重新确认位置。"
                return FrameResult(
                    state = NavigationState.SEEKING_CROSSWALK,
                    guidance = say(now, guidance),
                    statusText = "寻找斑马线"
                )
            }
        } else {
            cntLost = 0
        }

        when (tlResult.stableState) {
            TrafficLightState.GREEN -> {
                cntGreenStable++
                guidance = "绿灯"
                statusText = "绿灯确认中 ($cntGreenStable/$FRAMES_GREEN_STABLE)"

                if (cntGreenStable >= FRAMES_GREEN_STABLE && !inCooldown) {
                    transitionTo(CrossState.CROSSING)
                    guidance = "绿灯稳定，开始通行。"
                    statusText = "通行中"
                }
            }
            TrafficLightState.RED -> {
                cntGreenStable = 0
                guidance = "红灯"
                statusText = "等待绿灯 - 红灯"
            }
            else -> {
                cntGreenStable = 0
                statusText = "等待绿灯"
            }
        }

        return FrameResult(
            state = NavigationState.WAIT_TRAFFIC_LIGHT,
            guidance = say(now, guidance),
            statusText = statusText,
            detections = emptyList()
        )
    }

    /**
     * 通行中（跟踪斑马线 + 盲道，检测到岸或路径丢失）
     */
    private fun handleCrossing(
        bitmap: android.graphics.Bitmap,
        inCooldown: Boolean,
        now: Long
    ): FrameResult {
        val segResults = engine.runSegmentation(bitmap)

        val crosswalkDetections = segResults.filter {
            (it.classId == 0 || it.className == "crosswalk") && it.confidence >= 0.30f
        }
        val blindDetections = segResults.filter {
            (it.classId == 1 || it.className == "blind_path") && it.confidence >= 0.15f
        }

        val crosswalkPresent = crosswalkDetections.isNotEmpty()
        val bestBlind = blindDetections.maxByOrNull { it.confidence }
        val blindAreaRatio = bestBlind?.boundingBox?.area ?: 0f
        val blindDetected = bestBlind != null && blindAreaRatio > 0.05f

        Log.d(TAG, "CROSSING: crossPresent=$crosswalkPresent, blindDetected=$blindDetected, cntCrossEnd=$cntCrossEnd/$FRAMES_CROSS_END")

        var guidance = ""
        var statusText = "过马路中"

        if (crosswalkPresent) {
            // 盲道出现 → 到达对岸
            if (blindDetected) {
                cntCrossEnd++
                if (cntCrossEnd >= FRAMES_CROSS_END && !inCooldown) {
                    finishCrossing()
                    guidance = "过马路完成。"
                } else {
                    guidance = "继续前行"
                }
            } else {
                // 斑马线在但盲道未出现 → 继续走
                guidance = "继续前行"
                cntCrossEnd = maxOf(0, cntCrossEnd - 1)
            }
        } else {
            // 斑马线消失 → 可能已过完，或路径丢失
            cntCrossEnd++
            if (cntCrossEnd >= FRAMES_CROSS_END && !inCooldown) {
                finishCrossing()
                guidance = "过马路完成。"
            } else {
                guidance = "继续前行"
            }
        }

        return FrameResult(
            state = NavigationState.CROSS_STREET,
            guidance = say(now, guidance),
            statusText = statusText,
            detections = crosswalkDetections + blindDetections
        )
    }

    // ========== 外部命令 ==========

    /**
     * 启动过马路模式
     */
    fun start() {
        Log.i(TAG, "start: 进入过马路模式")
        transitionTo(CrossState.SEEKING_CROSSWALK)
        resetCounters()
        currentStatusText = "过马路模式"
    }

    /**
     * 停止过马路，回到 IDLE
     */
    fun stop() {
        Log.i(TAG, "stop: 退出过马路模式")
        finishCrossing()
    }

    // ========== 内部方法 ==========

    private fun transitionTo(newState: CrossState) {
        currentState = newState
        cooldownUntil = System.currentTimeMillis() + (COOLDOWN_SEC * 1000).toLong()
        Log.i(TAG, "状态切换: -> ${newState.name}")
    }

    private fun finishCrossing() {
        currentState = CrossState.IDLE
        resetCounters()
        currentStatusText = "就绪"
    }

    private fun resetCounters() {
        cntCrossEnd = 0
        cntGreenStable = 0
        cntLost = 0
        lastSpokenText = ""
    }

    /**
     * 语音播报控制
     * - 不同文本：0.6s 节流后立刻播报（状态切换）
     * - 相同文本：每隔 3s 重复播报一次（周期性提醒，避免沉默）
     */
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

    /**
     * 帧处理结果（与 NavigationMaster.FrameResult 结构一致）
     */
    data class FrameResult(
        val state: NavigationState,
        val guidance: String,
        val statusText: String,
        val detections: List<com.blindnav.app.data.DetectionResult> = emptyList()
    )
}
