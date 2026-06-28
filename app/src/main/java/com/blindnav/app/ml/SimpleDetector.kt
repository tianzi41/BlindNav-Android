/**
 * SimpleDetector.kt - 轻量级视觉检测（不依赖 ONNX 模型）
 * 基于颜色和边界的盲道检测、障碍物感知
 */
package com.blindnav.app.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * 轻量级检测结果
 */
data class SimpleBlindPathResult(
    val detected: Boolean,
    val centerXRatio: Float = 0.5f,
    val areaRatio: Float = 0f,
    val guidance: String = ""
)

data class SimpleObstacleResult(
    val hasObstacle: Boolean,
    val position: String = "前方",
    val guidance: String = ""
)

/**
 * 轻量级检测器 - 基于颜色和边缘的手机可用方案
 * 不依赖 ONNX/大型模型，所有计算在 CPU 上轻量完成
 */
object SimpleDetector {
    private const val TAG = "SimpleDetector"

    // 黄色盲道砖的 HSV 阈值
    private const val HUE_YELLOW_LOW = 20f
    private const val HUE_YELLOW_HIGH = 40f
    private const val SATURATION_MIN = 40f
    private const val VALUE_MIN = 40f

    // 画面分区阈值
    private const val LEFT_THRESHOLD = 0.33f
    private const val RIGHT_THRESHOLD = 0.67f
    private const val MIN_AREA_RATIO = 0.03f

    /**
     * 检测盲道 - 基于黄色的颜色分割
     * @param bitmap 相机帧
     * @param sampleStep 采样步长（越大越快，越小越准，默认 4）
     */
    fun detectBlindPath(bitmap: Bitmap, sampleStep: Int = 4): SimpleBlindPathResult {
        val w = bitmap.width
        val h = bitmap.height
        val hsv = FloatArray(3)

        var totalPixels = 0
        var yellowPixels = 0
        var sumX = 0f
        var sumY = 0f

        // 主要关注画面下半部分（路面区域）
        val startY = h / 3
        val endY = h

        for (y in startY until endY step sampleStep) {
            for (x in 0 until w step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)

                val (hue, sat, value) = hsv

                // 检测黄色（盲道砖颜色）
                if (hue in HUE_YELLOW_LOW..HUE_YELLOW_HIGH &&
                    sat * 100 > SATURATION_MIN &&
                    value * 100 > VALUE_MIN
                ) {
                    yellowPixels++
                    sumX += x
                    sumY += y
                }
                totalPixels++
            }
        }

        if (totalPixels == 0) return SimpleBlindPathResult(detected = false)

        val areaRatio = yellowPixels.toFloat() / totalPixels

        if (areaRatio < MIN_AREA_RATIO) {
            return SimpleBlindPathResult(detected = false, areaRatio = areaRatio)
        }

        val centerXRatio = if (yellowPixels > 0) {
            (sumX / yellowPixels) / w
        } else 0.5f

        val guidance = when {
            areaRatio < 0.05f -> "保持直行"  // 太远
            centerXRatio < LEFT_THRESHOLD -> "请向右平移。"
            centerXRatio > RIGHT_THRESHOLD -> "请向左平移。"
            else -> "保持直行"
        }

        Log.d(TAG, "盲道检测: area=$areaRatio, centerX=$centerXRatio, guidance=$guidance")

        return SimpleBlindPathResult(
            detected = true,
            centerXRatio = centerXRatio,
            areaRatio = areaRatio,
            guidance = guidance
        )
    }

    /**
     * 简单障碍物检测 - 基于画面下半部分的边缘复杂度
     * @param bitmap 相机帧
     * @param sampleStep 采样步长
     */
    fun detectObstacle(bitmap: Bitmap, sampleStep: Int = 6): SimpleObstacleResult {
        val w = bitmap.width
        val h = bitmap.height

        val startY = h / 3
        val endY = h

        var totalPixels = 0
        var darkPixels = 0
        var darkSumX = 0f

        for (y in startY until endY step sampleStep) {
            for (x in 0 until w step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // 检测暗色物体（障碍物通常是深色的）
                val brightness = (r + g + b) / 3f
                if (brightness < 60) {
                    darkPixels++
                    darkSumX += x
                }
                totalPixels++
            }
        }

        val darkRatio = if (totalPixels > 0) darkPixels.toFloat() / totalPixels else 0f

        if (darkRatio < 0.08f) {
            return SimpleObstacleResult(hasObstacle = false)
        }

        val centerX = if (darkPixels > 0) darkSumX / darkPixels / w else 0.5f
        val position = when {
            centerX < LEFT_THRESHOLD -> "左侧"
            centerX > RIGHT_THRESHOLD -> "右侧"
            else -> "前方"
        }

        val guidance = if (darkRatio > 0.25f) {
            "${position}有障碍物，停一下。"
        } else {
            "${position}有障碍物，注意避让。"
        }

        return SimpleObstacleResult(
            hasObstacle = true,
            position = position,
            guidance = guidance
        )
    }
}
