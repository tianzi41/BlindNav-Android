/**
 * TrafficLightDetector.kt - 红绿灯检测器
 * 使用 ResNet18 分类模型直接判断人行红绿灯颜色
 * 天然过滤车辆红绿灯（模型只见过人行灯）
 */
package com.blindnav.app.detector

import android.graphics.Bitmap
import android.util.Log
import com.blindnav.app.data.TrafficLightState
import com.blindnav.app.ml.YoloOnnxEngine

class TrafficLightDetector(
    private val engine: YoloOnnxEngine
) {
    companion object {
        private const val TAG = "TrafficLightDetector"
        private const val MAJORITY_WINDOW = 8          // 8 帧窗口
        private const val MIN_VALID_VOTES = 2          // 2 帧一致即确认
        private const val MIN_CONFIDENCE = 0.40f       // 最低置信度

        // classIndex → TrafficLightState 映射
        // 0=red, 1=green, 2=countdown_green, 3=countdown_blank, 4=none
        private val CLASS_TO_STATE = mapOf(
            0 to TrafficLightState.RED,
            1 to TrafficLightState.GREEN,
            2 to TrafficLightState.GREEN,     // countdown_green → 绿灯（倒数阶段仍是绿）
            3 to TrafficLightState.UNKNOWN,  // countdown_blank → 无灯
            4 to TrafficLightState.UNKNOWN   // none → 无灯
        )
    }

    data class TrafficLightResult(
        val state: TrafficLightState,
        val confidence: Float,
        val stableState: TrafficLightState,
        val className: String = ""
    )

    private val history = mutableListOf<TrafficLightState>()

    /**
     * 使用 ResNet18 分类模型检测红绿灯
     */
    fun detect(bitmap: Bitmap): TrafficLightResult {
        if (!engine.isClaModelLoaded()) {
            Log.w(TAG, "分类模型未加载")
            addToHistory(TrafficLightState.UNKNOWN)
            return TrafficLightResult(TrafficLightState.UNKNOWN, 0f, getMajorityState())
        }

        val result = engine.runClassification(bitmap)
        if (result == null) {
            addToHistory(TrafficLightState.UNKNOWN)
            return TrafficLightResult(TrafficLightState.UNKNOWN, 0f, getMajorityState())
        }

        val (classIdx, confidence) = result
        val className = if (classIdx in YoloOnnxEngine.CLA_CLASSES.indices)
            YoloOnnxEngine.CLA_CLASSES[classIdx] else "unknown"

        Log.d(TAG, "classifier: $className($classIdx) conf=${String.format("%.2f", confidence)}")

        if (confidence < MIN_CONFIDENCE) {
            addToHistory(TrafficLightState.UNKNOWN)
            return TrafficLightResult(TrafficLightState.UNKNOWN, confidence, getMajorityState(), className)
        }

        val state = CLASS_TO_STATE[classIdx] ?: TrafficLightState.UNKNOWN
        addToHistory(state)

        return TrafficLightResult(
            state = state,
            confidence = confidence,
            stableState = getMajorityState(),
            className = className
        )
    }

    /**
     * 使用 LYTNetV2 分类模型检测红绿灯（768×576, 原始像素无归一化）
     */
    fun detectWithLyt(bitmap: Bitmap): TrafficLightResult {
        if (!engine.isLytModelLoaded()) {
            Log.w(TAG, "LYTNetV2 模型未加载")
            addToHistory(TrafficLightState.UNKNOWN)
            return TrafficLightResult(TrafficLightState.UNKNOWN, 0f, getMajorityState())
        }

        val result = engine.runLytClassification(bitmap)
        if (result == null) {
            addToHistory(TrafficLightState.UNKNOWN)
            return TrafficLightResult(TrafficLightState.UNKNOWN, 0f, getMajorityState())
        }

        val (classIdx, confidence) = result
        val className = if (classIdx in YoloOnnxEngine.CLA_CLASSES.indices)
            YoloOnnxEngine.CLA_CLASSES[classIdx] else "unknown"

        Log.d(TAG, "LYTNetV2: $className($classIdx) prob=${String.format("%.2f", confidence)}")

        if (confidence < MIN_CONFIDENCE) {
            addToHistory(TrafficLightState.UNKNOWN)
            return TrafficLightResult(TrafficLightState.UNKNOWN, confidence, getMajorityState(), className)
        }

        val state = CLASS_TO_STATE[classIdx] ?: TrafficLightState.UNKNOWN
        addToHistory(state)

        return TrafficLightResult(
            state = state,
            confidence = confidence,
            stableState = getMajorityState(),
            className = "$className(LYT)"
        )
    }

    private fun addToHistory(state: TrafficLightState) {
        history.add(state)
        if (history.size > MAJORITY_WINDOW) {
            history.removeAt(0)
        }
    }

    /**
     * 多数表决：有效灯色（非 UNKNOWN）中至少 MIN_VALID_VOTES 票
     */
    private fun getMajorityState(): TrafficLightState {
        if (history.isEmpty()) return TrafficLightState.UNKNOWN
        val validCounts = history
            .filter { it != TrafficLightState.UNKNOWN }
            .groupingBy { it }.eachCount()
        if (validCounts.isEmpty()) return TrafficLightState.UNKNOWN
        val majority = validCounts.maxByOrNull { it.value }
        return if ((majority?.value ?: 0) >= MIN_VALID_VOTES) majority!!.key else TrafficLightState.UNKNOWN
    }

    fun reset() { history.clear() }
}
