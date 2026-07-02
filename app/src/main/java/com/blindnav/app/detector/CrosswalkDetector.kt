/**
 * CrosswalkDetector.kt - 斑马线检测器
 * 检测画面中的斑马线，计算位置和对准状态
 */
package com.blindnav.app.detector

import android.graphics.Bitmap
import android.util.Log
import com.blindnav.app.data.BoundingBox
import com.blindnav.app.data.DetectionResult
import com.blindnav.app.ml.YoloOnnxEngine

/**
 * 斑马线检测器
 * 检测斑马线并判断距离、位置和对准状态
 */
class CrosswalkDetector(
    private val engine: YoloOnnxEngine
) {
    companion object {
        private const val TAG = "CrosswalkDetector"
        // 斑马线类别（在自定义模型中可能是特定的类别ID）
        private const val CROSSWALK_CLASS_ID = 0  // 模型中 class 0 = 斑马线
        private const val MIN_CONFIDENCE = 0.4f

        // 斑马线距离阶段阈值
        private const val FAR_AREA_THRESHOLD = 0.05f     // 远处：面积 < 5%
        private const val APPROACHING_AREA_THRESHOLD = 0.15f  // 接近：面积 5%~15%
        private const val READY_AREA_THRESHOLD = 0.25f   // 到达：面积 > 25%

        // 对准阈值
        private const val ALIGN_CENTER_THRESHOLD = 0.08f  // 中心偏移阈值（>8% 即提示平移）
    }

    /**
     * 斑马线检测结果
     */
    data class CrosswalkResult(
        val detected: Boolean,
        val stage: CrosswalkStage,
        val centerXRatio: Float = 0.5f,
        val bottomYRatio: Float = 1.0f,
        val areaRatio: Float = 0f,
        val isAligned: Boolean = false,
        val confidence: Float = 0f,
        val detection: DetectionResult? = null
    )

    /**
     * 斑马线距离阶段
     */
    enum class CrosswalkStage(val description: String) {
        NOT_DETECTED("未检测到"),
        FAR("远处发现"),
        APPROACHING("正在接近"),
        READY("到达"),
        ON_CROSSWALK("正在斑马线上")
    }

    /**
     * 检测斑马线
     * @param bitmap 输入图像
     * @return 斑马线检测结果
     */
    fun detect(bitmap: Bitmap): CrosswalkResult {
        val detections = engine.runSegmentation(bitmap)

        // 过滤出斑马线检测结果
        val crosswalkDetections = detections.filter { detection ->
            detection.classId == CROSSWALK_CLASS_ID ||
            detection.className == "crosswalk"
        }

        if (crosswalkDetections.isEmpty()) {
            return CrosswalkResult(
                detected = false,
                stage = CrosswalkStage.NOT_DETECTED
            )
        }

        // 选择置信度最高的斑马线
        val bestDetection = crosswalkDetections.maxByOrNull { it.confidence }
            ?: return CrosswalkResult(
                detected = false,
                stage = CrosswalkStage.NOT_DETECTED
            )

        val box = bestDetection.boundingBox
        val centerX = box.centerX
        val bottomY = box.y2
        val areaRatio = box.area

        // 判断距离阶段
        val stage = determineStage(areaRatio, bottomY)

        // 判断是否对准（斑马线在画面中居中）
        val isAligned = Math.abs(centerX - 0.5f) < ALIGN_CENTER_THRESHOLD

        return CrosswalkResult(
            detected = true,
            stage = stage,
            centerXRatio = centerX,
            bottomYRatio = bottomY,
            areaRatio = areaRatio,
            isAligned = isAligned,
            confidence = bestDetection.confidence,
            detection = bestDetection
        )
    }

    /**
     * 根据面积和底部位置判断距离阶段
     */
    private fun determineStage(areaRatio: Float, bottomY: Float): CrosswalkStage {
        return when {
            areaRatio >= READY_AREA_THRESHOLD -> CrosswalkStage.ON_CROSSWALK
            areaRatio >= APPROACHING_AREA_THRESHOLD -> CrosswalkStage.READY
            areaRatio >= FAR_AREA_THRESHOLD -> CrosswalkStage.APPROACHING
            else -> CrosswalkStage.FAR
        }
    }

    /**
     * 生成对准引导提示
     */
    fun getAlignmentGuidance(centerXRatio: Float): String {
        val offset = centerXRatio - 0.5f
        return when {
            offset < -ALIGN_CENTER_THRESHOLD -> "请向右平移。"
            offset > ALIGN_CENTER_THRESHOLD -> "请向左平移。"
            else -> "方向正确，请直行。"
        }
    }

    /**
     * 从单个检测结果构建 CrosswalkResult（供外部直接调用）
     */
    fun buildResult(detection: DetectionResult): CrosswalkResult {
        val box = detection.boundingBox
        val centerX = box.centerX
        val bottomY = box.y2
        val areaRatio = box.area
        val stage = determineStage(areaRatio, bottomY)
        val isAligned = Math.abs(centerX - 0.5f) < ALIGN_CENTER_THRESHOLD

        return CrosswalkResult(
            detected = true,
            stage = stage,
            centerXRatio = centerX,
            bottomYRatio = bottomY,
            areaRatio = areaRatio,
            isAligned = isAligned,
            confidence = detection.confidence,
            detection = detection
        )
    }
}
