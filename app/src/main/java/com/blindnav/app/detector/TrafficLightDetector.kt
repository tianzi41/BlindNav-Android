/**
 * TrafficLightDetector.kt - 红绿灯检测器
 * 检测画面中的红绿灯并判断当前灯色状态
 */
package com.blindnav.app.detector

import android.graphics.Bitmap
import android.util.Log
import com.blindnav.app.data.DetectionResult
import com.blindnav.app.data.TrafficLightState
import com.blindnav.app.ml.YoloOnnxEngine

/**
 * 红绿灯检测器
 * 检测画面中的红绿灯，使用多数表决稳定检测结果
 */
class TrafficLightDetector(
    private val engine: YoloOnnxEngine
) {
    companion object {
        private const val TAG = "TrafficLightDetector"
        private const val TRAFFIC_LIGHT_CLASS = "traffic light"
        private const val TRAFFIC_LIGHT_CLASS_ID = 9  // COCO 类别 ID
        private const val MIN_CONFIDENCE = 0.18f

        // 多数表决窗口大小（增大以稳定弱检测信号）
        private const val MAJORITY_WINDOW = 8

        // 专用红绿灯模型类别映射
        private val TRAFFIC_LIGHT_CLASSES = mapOf(
            "stop" to TrafficLightState.RED,
            "go" to TrafficLightState.GREEN,
            "countdown_go" to TrafficLightState.YELLOW,
            "countdown_stop" to TrafficLightState.RED
        )
        // 需要过滤掉的非红绿灯类别
        private val FILTERED_CLASSES = setOf("crossing", "blank", "countdown_blank")
    }

    // 多数表决历史
    private val history = mutableListOf<TrafficLightState>()

    /**
     * 红绿灯检测结果
     */
    data class TrafficLightResult(
        val state: TrafficLightState,
        val confidence: Float,
        val detection: DetectionResult? = null,
        val stableState: TrafficLightState  // 多数表决后的稳定状态
    )

    /**
     * 检测红绿灯状态
     * 优先使用专用红绿灯模型，回退到 COCO 通用模型
     * @param bitmap 输入图像
     * @return 红绿灯检测结果
     */
    fun detect(bitmap: Bitmap): TrafficLightResult {
        // 优先使用专用红绿灯模型
        if (engine.isTrafficModelLoaded()) {
            return detectWithTrafficModel(bitmap)
        }
        // 回退到 COCO 通用模型
        return detectWithCocoModel(bitmap)
    }

    /**
     * 使用专用红绿灯模型检测
     * 模型类别: stop(红灯), go(绿灯), countdown_go(黄灯), countdown_stop(红灯)
     */
    private fun detectWithTrafficModel(bitmap: Bitmap): TrafficLightResult {
        val detections = engine.runTrafficDetection(bitmap)

        Log.d(TAG, "detectWithTrafficModel: raw detections=${detections.size}")
        detections.take(5).forEachIndexed { i, d ->
            Log.d(TAG, "  [$i] class='${d.className}' id=${d.classId} conf=${String.format("%.2f", d.confidence)}")
        }

        // 过滤出红绿灯检测结果（排除 crossing, blank 等非灯类别）
        val trafficLightDetections = detections.filter { detection ->
            detection.className !in FILTERED_CLASSES &&
            detection.confidence >= MIN_CONFIDENCE &&
            TRAFFIC_LIGHT_CLASSES.containsKey(detection.className)
        }

        Log.d(TAG, "detectWithTrafficModel: filtered=${trafficLightDetections.size} (from ${detections.size})")
        trafficLightDetections.forEachIndexed { i, d ->
            Log.d(TAG, "  PASS[$i] class='${d.className}' conf=${String.format("%.2f", d.confidence)}")
        }

        if (trafficLightDetections.isEmpty()) {
            addToHistory(TrafficLightState.UNKNOWN)
            return TrafficLightResult(
                state = TrafficLightState.UNKNOWN,
                confidence = 0f,
                stableState = getMajorityState()
            )
        }

        // 选择置信度最高的红绿灯
        val bestDetection = trafficLightDetections.maxByOrNull { it.confidence }
            ?: return TrafficLightResult(
                state = TrafficLightState.UNKNOWN,
                confidence = 0f,
                stableState = getMajorityState()
            )

        // 直接从类别名映射灯色
        val state = TRAFFIC_LIGHT_CLASSES[bestDetection.className] ?: TrafficLightState.UNKNOWN

        addToHistory(state)

        return TrafficLightResult(
            state = state,
            confidence = bestDetection.confidence,
            detection = bestDetection,
            stableState = getMajorityState()
        )
    }

    /**
     * 使用 COCO 通用模型检测（回退方案）
     * 检测 COCO "traffic light" 类别，然后通过颜色分析判断灯色
     */
    private fun detectWithCocoModel(bitmap: Bitmap): TrafficLightResult {
        val detections = engine.runDetection(bitmap)

        // 过滤出红绿灯检测结果
        val trafficLightDetections = detections.filter { detection ->
            (detection.className == TRAFFIC_LIGHT_CLASS ||
             detection.classId == TRAFFIC_LIGHT_CLASS_ID) &&
            detection.confidence >= MIN_CONFIDENCE
        }

        if (trafficLightDetections.isEmpty()) {
            addToHistory(TrafficLightState.UNKNOWN)
            return TrafficLightResult(
                state = TrafficLightState.UNKNOWN,
                confidence = 0f,
                stableState = getMajorityState()
            )
        }

        // 选择置信度最高的红绿灯
        val bestDetection = trafficLightDetections.maxByOrNull { it.confidence }
            ?: return TrafficLightResult(
                state = TrafficLightState.UNKNOWN,
                confidence = 0f,
                stableState = getMajorityState()
            )

        // 分析灯色（通过检测框内的颜色）
        val state = analyzeLightColor(bitmap, bestDetection.boundingBox)

        // 添加到历史并获取稳定状态
        addToHistory(state)

        return TrafficLightResult(
            state = state,
            confidence = bestDetection.confidence,
            detection = bestDetection,
            stableState = getMajorityState()
        )
    }

    /**
     * 分析检测框内的颜色来判断灯色
     * 使用简化的颜色分析方法
     */
    private fun analyzeLightColor(
        bitmap: Bitmap,
        box: com.blindnav.app.data.BoundingBox
    ): TrafficLightState {
        val x1 = (box.x1 * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y1 = (box.y1 * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val x2 = (box.x2 * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y2 = (box.y2 * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)

        if (x2 <= x1 || y2 <= y1) return TrafficLightState.UNKNOWN

        // 采样 ROI 区域
        val roiWidth = x2 - x1
        val roiHeight = y2 - y1
        val pixels = IntArray(roiWidth * roiHeight)
        bitmap.getPixels(pixels, 0, roiWidth, x1, y1, roiWidth, roiHeight)

        var redCount = 0
        var greenCount = 0
        var yellowCount = 0
        val totalPixels = pixels.size

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            when {
                // 红色检测
                r > 150 && g < 100 && b < 100 -> redCount++
                // 绿色检测
                r < 100 && g > 150 && b < 100 -> greenCount++
                // 黄色检测
                r > 150 && g > 150 && b < 100 -> yellowCount++
            }
        }

        // 计算颜色比例
        val redRatio = redCount.toFloat() / totalPixels
        val greenRatio = greenCount.toFloat() / totalPixels
        val yellowRatio = yellowCount.toFloat() / totalPixels

        // 选择比例最高的颜色
        val threshold = 0.1f
        return when {
            redRatio > threshold && redRatio >= greenRatio && redRatio >= yellowRatio ->
                TrafficLightState.RED
            greenRatio > threshold && greenRatio >= redRatio && greenRatio >= yellowRatio ->
                TrafficLightState.GREEN
            yellowRatio > threshold && yellowRatio >= redRatio && yellowRatio >= greenRatio ->
                TrafficLightState.YELLOW
            else -> TrafficLightState.UNKNOWN
        }
    }

    /**
     * 添加状态到历史记录
     */
    private fun addToHistory(state: TrafficLightState) {
        history.add(state)
        if (history.size > MAJORITY_WINDOW) {
            history.removeAt(0)
        }
    }

    /**
     * 获取多数表决后的稳定状态
     * 排除 UNKNOWN，只在有效灯色（RED/GREEN/YELLOW）中投票
     * 至少需要 2 票有效结果才输出，否则返回 UNKNOWN
     */
    private fun getMajorityState(): TrafficLightState {
        if (history.isEmpty()) return TrafficLightState.UNKNOWN

        // 只统计有效灯色（排除 UNKNOWN）
        val validCounts = history
            .filter { it != TrafficLightState.UNKNOWN }
            .groupingBy { it }
            .eachCount()

        if (validCounts.isEmpty()) return TrafficLightState.UNKNOWN

        val majority = validCounts.maxByOrNull { it.value }
        // 至少 2 票有效才确认灯色
        return if ((majority?.value ?: 0) >= 2) majority!!.key else TrafficLightState.UNKNOWN
    }

    /**
     * 重置检测器状态
     */
    fun reset() {
        history.clear()
    }
}
