/**
 * BlindPathDetector.kt - 盲道分割检测器
 * 使用 YOLO 分割模型检测盲道，计算中心线和方向
 */
package com.blindnav.app.detector

import android.graphics.Bitmap
import android.util.Log
import com.blindnav.app.data.BoundingBox
import com.blindnav.app.data.DetectionResult
import com.blindnav.app.data.GuidanceDirection
import com.blindnav.app.ml.YoloOnnxEngine

/**
 * 盲道检测器
 * 检测画面中的盲道，计算中心位置和方向引导
 */
class BlindPathDetector(
    private val engine: YoloOnnxEngine
) {
    companion object {
        private const val TAG = "BlindPathDetector"
        // 盲道在分割模型中的类别ID（通常是自定义训练的类别）
        const val BLIND_PATH_CLASS_ID = 1  // 模型中 class 1 = 盲道
        // 盲道中心偏移阈值
        private const val CENTER_THRESHOLD_LEFT = 0.35f
        private const val CENTER_THRESHOLD_RIGHT = 0.65f
        // 盲道面积最小阈值（相对于画面面积）
        private const val MIN_AREA_RATIO = 0.02f
        // 最小置信度阈值（与引擎层 CONF_THRESHOLD 一致）
        private const val MIN_CONFIDENCE = 0.10f
        // 宽高比阈值：兼容横竖屏，取长边/短边
        // 降低到 1.05 以适应真实分割掩码（bbox 可能接近正方形）
        private const val MIN_ASPECT_RATIO = 1.05f

        /**
         * 根据盲道中心位置计算引导方向（静态方法，供外部调用）
         */
        fun calculateGuidance(centerX: Float, areaRatio: Float): GuidanceDirection {
            return when {
                areaRatio < MIN_AREA_RATIO -> GuidanceDirection.STRAIGHT
                centerX < CENTER_THRESHOLD_LEFT -> GuidanceDirection.LEFT_SHIFT
                centerX > CENTER_THRESHOLD_RIGHT -> GuidanceDirection.RIGHT_SHIFT
                else -> GuidanceDirection.STRAIGHT
            }
        }
    }

    /**
     * 盲道检测结果
     */
    data class BlindPathResult(
        val detected: Boolean,
        val centerXRatio: Float = 0.5f,
        val areaRatio: Float = 0f,
        val guidance: GuidanceDirection = GuidanceDirection.NONE,
        val confidence: Float = 0f,
        val detection: DetectionResult? = null
    )

    /**
     * 检测盲道并返回引导方向
     * 添加验证逻辑：置信度过滤 + 形状验证
     * @param bitmap 输入图像
     * @return 盲道检测结果
     */
    fun detect(bitmap: Bitmap): BlindPathResult {
        val detections = engine.runSegmentation(bitmap)

        // 过滤出盲道检测结果
        val blindPathDetections = detections.filter { detection ->
            (detection.classId == BLIND_PATH_CLASS_ID ||
            detection.className == "blind_path") &&
            detection.confidence >= MIN_CONFIDENCE  // 过滤低置信度误检
        }

        if (blindPathDetections.isEmpty()) {
            return BlindPathResult(detected = false)
        }

        // 选择置信度最高的盲道检测
        val bestDetection = blindPathDetections.maxByOrNull { it.confidence }
            ?: return BlindPathResult(detected = false)

        val box = bestDetection.boundingBox
        val centerX = box.centerX
        val areaRatio = box.area

        // 形状验证：当有真实分割掩码（多边形 > 4 点）时跳过验证
        // 真实掩码比 bbox 更可靠，不需要宽高比过滤
        val hasRealMask = (bestDetection.mask?.polygon?.size ?: 0) > 4
        if (!hasRealMask && box.width > 0.01f && box.height > 0.01f) {
            val aspectRatio = maxOf(box.height / box.width, box.width / box.height)
            if (aspectRatio < MIN_ASPECT_RATIO) {
                Log.d(TAG, "形状验证失败: aspectRatio=${aspectRatio}, 宽度=${box.width}, 高度=${box.height}")
                return BlindPathResult(detected = false)
            }
        }

        // 计算引导方向
        val guidance = calculateGuidance(centerX, areaRatio)

        return BlindPathResult(
            detected = true,
            centerXRatio = centerX,
            areaRatio = areaRatio,
            guidance = guidance,
            confidence = bestDetection.confidence,
            detection = bestDetection
        )
    }

    /**
     * 从已有分割结果中解析盲道（统一检测逻辑，供 NavigationMaster 调用）
     * 避免 handleBlindNav 和 detect() 使用不同的过滤条件
     * @param segResults runSegmentation 的完整输出
     * @return 盲道检测结果
     */
    fun parseFromSegmentation(segResults: List<DetectionResult>): BlindPathResult {
        val blindPathDetections = segResults.filter { detection ->
            (detection.classId == BLIND_PATH_CLASS_ID ||
            detection.className == "blind_path") &&
            detection.confidence >= MIN_CONFIDENCE
        }

        if (blindPathDetections.isEmpty()) {
            Log.d(TAG, "parseFromSegmentation: 未找到盲道 (segResults=${segResults.size}个)")
            return BlindPathResult(detected = false)
        }

        val bestDetection = blindPathDetections.maxByOrNull { it.confidence }
            ?: return BlindPathResult(detected = false)

        val box = bestDetection.boundingBox
        val centerX = box.centerX
        val areaRatio = box.area

        // 形状验证：当有真实分割掩码（多边形 > 4 点）时跳过验证
        val hasRealMask = (bestDetection.mask?.polygon?.size ?: 0) > 4
        if (!hasRealMask && box.width > 0.01f && box.height > 0.01f) {
            val aspectRatio = maxOf(box.height / box.width, box.width / box.height)
            if (aspectRatio < MIN_ASPECT_RATIO) {
                Log.d(TAG, "形状验证失败: aspectRatio=${aspectRatio}")
                return BlindPathResult(detected = false)
            }
        }

        val guidance = calculateGuidance(centerX, areaRatio)
        Log.d(TAG, "parseFromSegmentation: OK conf=${bestDetection.confidence}, centerX=$centerX, area=$areaRatio")
        return BlindPathResult(
            detected = true,
            centerXRatio = centerX,
            areaRatio = areaRatio,
            guidance = guidance,
            confidence = bestDetection.confidence,
            detection = bestDetection
        )
    }

    /**
     * 检查盲道是否在画面中居中
     */
    fun isCentered(centerXRatio: Float): Boolean {
        return centerXRatio in CENTER_THRESHOLD_LEFT..CENTER_THRESHOLD_RIGHT
    }

    /**
     * 计算盲道偏移量（负值偏左，正值偏右）
     */
    fun calculateOffset(centerXRatio: Float): Float {
        return centerXRatio - 0.5f
    }
}
