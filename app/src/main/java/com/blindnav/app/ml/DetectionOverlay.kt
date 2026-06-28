/**
 * DetectionOverlay.kt - 检测结果叠加绘制
 * 在相机预览上绘制检测框、掩码和方向指引
 */
package com.blindnav.app.ml

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.blindnav.app.data.BoundingBox
import com.blindnav.app.data.DetectionResult
import com.blindnav.app.data.GuidanceDirection

/**
 * 检测结果叠加绘制器
 * 负责在相机预览上绘制检测框、分割掩码和导航箭头
 */
class DetectionOverlay {

    // 画笔定义
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 80
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val textBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 0, 0, 0)
        isAntiAlias = true
    }

    private val arrowPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.GREEN
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 100
    }

    // 类别颜色映射
    private val classColors = mapOf(
        "person" to Color.rgb(255, 100, 100),
        "bicycle" to Color.rgb(255, 200, 100),
        "car" to Color.rgb(255, 150, 50),
        "motorcycle" to Color.rgb(255, 180, 80),
        "bus" to Color.rgb(255, 120, 60),
        "truck" to Color.rgb(255, 100, 40),
        "dog" to Color.rgb(200, 150, 255),
        "cat" to Color.rgb(180, 130, 255),
        "traffic light" to Color.rgb(255, 255, 0),
        // 盲道和斑马线使用特殊颜色
        "blind_path" to Color.argb(120, 0, 255, 0),
        "crosswalk" to Color.argb(120, 255, 165, 0)
    )

    /**
     * 绘制检测结果叠加层
     * @param canvas 目标画布
     * @param detections 检测结果列表
     * @param imageWidth 图像宽度
     * @param imageHeight 图像高度
     */
    fun drawDetections(
        canvas: Canvas,
        detections: List<DetectionResult>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        for (detection in detections) {
            drawSingleDetection(canvas, detection, imageWidth, imageHeight)
        }
    }

    /**
     * 绘制单个检测结果
     */
    private fun drawSingleDetection(
        canvas: Canvas,
        detection: DetectionResult,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val box = detection.boundingBox
        val rect = box.toRectF(imageWidth, imageHeight)

        // 获取类别颜色
        val color = classColors[detection.className] ?: Color.CYAN

        // 绘制分割掩码（如果有）
        detection.mask?.let { mask ->
            drawMask(canvas, mask, imageWidth, imageHeight, color)
        }

        // 绘制边界框
        boxPaint.color = color
        canvas.drawRect(rect, boxPaint)

        // 绘制标签背景和文本
        val label = "${detection.className} ${(detection.confidence * 100).toInt()}%"
        drawLabel(canvas, label, rect.left, rect.top - 10f, color)
    }

    /**
     * 绘制分割掩码多边形
     */
    private fun drawMask(
        canvas: Canvas,
        mask: com.blindnav.app.data.SegmentationMask,
        imageWidth: Int,
        imageHeight: Int,
        color: Int
    ) {
        if (mask.polygon.size < 3) return

        val path = Path()
        val firstPoint = mask.polygon[0]
        path.moveTo(firstPoint.x * imageWidth, firstPoint.y * imageHeight)

        for (i in 1 until mask.polygon.size) {
            val point = mask.polygon[i]
            path.lineTo(point.x * imageWidth, point.y * imageHeight)
        }
        path.close()

        maskPaint.color = color
        canvas.drawPath(path, maskPaint)
    }

    /**
     * 绘制标签（类别名 + 置信度）
     */
    private fun drawLabel(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        bgColor: Int
    ) {
        val textWidth = textPaint.measureText(text)
        val textHeight = textPaint.textSize

        // 背景框
        textBgPaint.color = bgColor
        canvas.drawRoundRect(
            RectF(x - 4, y - textHeight - 4, x + textWidth + 8, y + 4),
            8f, 8f, textBgPaint
        )

        // 文本
        canvas.drawText(text, x + 2, y - 4, textPaint)
    }

    /**
     * 绘制导航方向箭头
     * @param canvas 目标画布
     * @param direction 导航方向
     * @param canvasWidth 画布宽度
     * @param canvasHeight 画布高度
     */
    fun drawNavigationArrow(
        canvas: Canvas,
        direction: GuidanceDirection,
        canvasWidth: Int,
        canvasHeight: Int
    ) {
        val centerX = canvasWidth / 2f
        val centerY = canvasHeight * 0.7f
        val arrowLength = canvasHeight * 0.15f

        when (direction) {
            GuidanceDirection.STRAIGHT -> {
                // 向上箭头
                drawArrow(canvas, centerX, centerY, centerX, centerY - arrowLength)
            }
            GuidanceDirection.LEFT_TURN, GuidanceDirection.LEFT_SHIFT -> {
                // 向左箭头
                drawArrow(canvas, centerX, centerY, centerX - arrowLength, centerY)
            }
            GuidanceDirection.RIGHT_TURN, GuidanceDirection.RIGHT_SHIFT -> {
                // 向右箭头
                drawArrow(canvas, centerX, centerY, centerX + arrowLength, centerY)
            }
            GuidanceDirection.STOP -> {
                // 画一个 X 表示停止
                val size = arrowLength * 0.6f
                arrowPaint.color = Color.RED
                drawArrow(canvas, centerX - size, centerY - size, centerX + size, centerY + size)
                drawArrow(canvas, centerX + size, centerY - size, centerX - size, centerY + size)
            }
            else -> { /* 不绘制箭头 */ }
        }
    }

    /**
     * 绘制箭头
     */
    private fun drawArrow(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ) {
        arrowPaint.color = Color.GREEN
        canvas.drawLine(startX, startY, endX, endY, arrowPaint)

        // 箭头头部
        val angle = Math.atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val arrowHeadLength = 40f
        val arrowHeadAngle = Math.PI / 6

        val head1X = endX - arrowHeadLength * Math.cos(angle - arrowHeadAngle).toFloat()
        val head1Y = endY - arrowHeadLength * Math.sin(angle - arrowHeadAngle).toFloat()
        val head2X = endX - arrowHeadLength * Math.cos(angle + arrowHeadAngle).toFloat()
        val head2Y = endY - arrowHeadLength * Math.sin(angle + arrowHeadAngle).toFloat()

        canvas.drawLine(endX, endY, head1X, head1Y, arrowPaint)
        canvas.drawLine(endX, endY, head2X, head2Y, arrowPaint)
    }

    /**
     * 绘制盲道引导线
     * @param canvas 目标画布
     * @param centerXRatio 盲道中心 X 比例 (0~1)
     * @param canvasWidth 画布宽度
     * @param canvasHeight 画布高度
     */
    fun drawBlindPathGuide(
        canvas: Canvas,
        centerXRatio: Float,
        canvasWidth: Int,
        canvasHeight: Int
    ) {
        val guidePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.argb(200, 0, 255, 0)
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
            isAntiAlias = true
        }

        val targetX = centerXRatio * canvasWidth
        val startY = canvasHeight * 0.9f
        val endY = canvasHeight * 0.3f

        // 绘制虚线引导线
        canvas.drawLine(targetX, startY, targetX, endY, guidePaint)

        // 绘制目标点
        val dotPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(targetX, endY, 12f, dotPaint)
    }
}
