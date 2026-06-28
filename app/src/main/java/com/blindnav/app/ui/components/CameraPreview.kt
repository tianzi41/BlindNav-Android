/**
 * CameraPreview.kt - 相机预览组件
 * 显示实时相机画面，叠加检测结果
 * 连接 CameraManager 启动相机并获取实时帧
 */
package com.blindnav.app.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.blindnav.app.camera.CameraManager
import com.blindnav.app.data.DetectionResult
import com.blindnav.app.data.GuidanceDirection
import com.blindnav.app.ml.DetectionOverlay

/** FILL_CENTER 诊断日志是否已输出（仅打印一次） */
private var fillCenterLogged = false

/**
 * 相机预览组件
 * 显示实时相机画面，支持检测结果叠加绘制
 * 自动连接 CameraManager 启动相机预览
 *
 * @param cameraManager 相机管理器实例
 * @param detections 检测结果列表
 * @param guidanceDirection 当前导航方向
 * @param onFrameAvailable 帧可用回调（传入 Bitmap）
 * @param onCameraRunningChanged 相机运行状态变化回调
 * @param modifier Modifier
 */
@Composable
fun CameraPreview(
    cameraManager: CameraManager,
    detections: List<DetectionResult> = emptyList(),
    guidanceDirection: GuidanceDirection = GuidanceDirection.NONE,
    onFrameAvailable: (Bitmap) -> Unit = {},
    onCameraRunningChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 创建 PreviewView
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // 检测叠加绘制器
    val overlay = remember { DetectionOverlay() }

    // 相机帧尺寸（用于 FILL_CENTER 坐标映射）
    // PreviewView 使用 FILL_CENTER 缩放，当图像宽高比与屏幕不同时，
    // 图像会被等比放大并裁剪超出部分。Canvas 叠加层需要应用相同的变换，
    // 否则检测坐标会与预览画面错位。
    var imageWidth by remember { mutableIntStateOf(0) }
    var imageHeight by remember { mutableIntStateOf(0) }

    // 启动相机并设置帧回调
    LaunchedEffect(cameraManager) {
        // 设置帧回调：将 ImageProxy 转换为 Bitmap 后传递
        cameraManager.setFrameCallback { imageProxy ->
            val bitmap = imageProxy.toRotatedBitmap()
            // 记录帧尺寸，供 Canvas 坐标映射使用
            imageWidth = bitmap.width
            imageHeight = bitmap.height
            onFrameAvailable(bitmap)
            imageProxy.close()
        }

        // 启动相机预览
        cameraManager.startCamera(previewView)
        onCameraRunningChanged(true)
    }

    // 清理资源
    DisposableEffect(cameraManager) {
        onDispose {
            cameraManager.stopCamera()
            onCameraRunningChanged(false)
        }
    }

    Box(modifier = modifier) {
        // 相机预览
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // 检测结果叠加层
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawDetections(detections, overlay, imageWidth, imageHeight)
            drawNavigationArrow(guidanceDirection)

            // 画面中心十字标记（帮助对照相机画面中心与检测位置）
            val cx = size.width / 2f
            val cy = size.height / 2f
            val crossSize = 15f
            val crossColor = Color(0xAAFFFFFF)
            drawLine(crossColor, Offset(cx - crossSize, cy), Offset(cx + crossSize, cy), strokeWidth = 2f)
            drawLine(crossColor, Offset(cx, cy - crossSize), Offset(cx, cy + crossSize), strokeWidth = 2f)
        }
    }
}

/**
 * 绘制检测结果
 * 盲道使用分割掩码多边形渲染（不使用矩形框），其他类别保持矩形框绘制
 *
 * @param imageWidth  相机帧宽度（像素），用于计算 FILL_CENTER 坐标映射
 * @param imageHeight 相机帧高度（像素），用于计算 FILL_CENTER 坐标映射
 *
 * FILL_CENTER 坐标映射原理：
 * PreviewView 使用 FILL_CENTER 缩放，以 max(viewW/imgW, viewH/imgH) 等比缩放图像，
 * 然后居中裁剪。Canvas 叠加层必须应用相同变换，否则检测坐标会偏移。
 */
private fun DrawScope.drawDetections(
    detections: List<DetectionResult>,
    overlay: DetectionOverlay,
    imageWidth: Int,
    imageHeight: Int
) {
    val viewW = size.width
    val viewH = size.height

    // ===== FILL_CENTER 坐标映射 =====
    // PreviewView(FILL_CENTER) 的缩放逻辑：
    //   scale = max(viewW / imgW, viewH / imgH)
    // 缩放后图像居中于视口，超出部分被裁剪。
    // 检测坐标 (normX, normY) 相对原始图像，需经相同变换才能正确显示在 Canvas 上。
    val scale: Float
    val offsetX: Float
    val offsetY: Float
    if (imageWidth > 0 && imageHeight > 0) {
        val sx = viewW / imageWidth
        val sy = viewH / imageHeight
        scale = maxOf(sx, sy)
        offsetX = (viewW - imageWidth * scale) / 2f
        offsetY = (viewH - imageHeight * scale) / 2f
    } else {
        // 帧尺寸未知，回退到直接映射（不应发生）
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    // 将归一化图像坐标 (0~1) 映射到 Canvas 坐标
    fun mapX(normX: Float): Float = normX * imageWidth * scale + offsetX
    fun mapY(normY: Float): Float = normY * imageHeight * scale + offsetY

    // 诊断日志：首次绘制时输出坐标映射参数
    if (imageWidth > 0 && imageHeight > 0 && !fillCenterLogged) {
        fillCenterLogged = true
        android.util.Log.i("CameraPreview",
            "FILL_CENTER 映射: img=${imageWidth}x${imageHeight}, " +
            "view=${viewW.toInt()}x${viewH.toInt()}, " +
            "scale=$scale, offsetX=$offsetX, offsetY=$offsetY")
    }

    for (detection in detections) {
        val box = detection.boundingBox

        // 使用 FILL_CENTER 映射转换 bbox 坐标
        val x1 = mapX(box.x1)
        val y1 = mapY(box.y1)
        val x2 = mapX(box.x2)
        val y2 = mapY(box.y2)

        // 根据类别选择颜色
        val color = when (detection.className) {
            "person" -> Color(0xFFFF6464)
            "car", "truck", "bus" -> Color(0xFFFF9632)
            "bicycle", "motorcycle" -> Color(0xFFFFB450)
            "dog", "cat" -> Color(0xFFC896FF)
            "traffic light" -> Color(0xFFFFFF00)
            "blind_path" -> Color(0x8000FF00)
            "crosswalk" -> Color(0x80FFA500)
            else -> Color.Cyan
        }

        if (detection.className == "blind_path") {
            // ===== 盲道：使用像素级掩码网格渲染，确保精确定位 =====
            val mask = detection.mask
            val grid = mask?.maskGrid
            val gw = mask?.gridWidth ?: 0
            val gh = mask?.gridHeight ?: 0
            val boxW = x2 - x1  // bbox 在画布上的像素宽度
            val boxH = y2 - y1  // bbox 在画布上的像素高度

            if (grid != null && gw > 0 && gh > 0) {
                // 使用掩码网格进行像素级渲染
                val cellW = boxW / gw
                val cellH = boxH / gh

                val fillColor = Color(0x5000FF00)  // 半透明绿色
                for (gy in 0 until gh) {
                    for (gx in 0 until gw) {
                        if (grid[gy * gw + gx]) {
                            val cx = x1 + gx * cellW
                            val cy = y1 + gy * cellH
                            drawRect(
                                color = fillColor,
                                topLeft = Offset(cx, cy),
                                size = Size(cellW + 1f, cellH + 1f)  // +1 避免网格间隙
                            )
                        }
                    }
                }

                // 绘制 bbox 区域的描边（标示检测范围）
                drawRect(
                    color = Color(0x6000FF00),
                    topLeft = Offset(x1, y1),
                    size = Size(boxW, boxH),
                    style = Stroke(width = 2f)
                )
            } else {
                // 回退：使用多边形渲染
                val polygon = mask?.polygon
                if (polygon != null && polygon.size >= 3) {
                    val path = androidx.compose.ui.graphics.Path()
                    path.moveTo(mapX(polygon[0].x), mapY(polygon[0].y))
                    for (i in 1 until polygon.size) {
                        path.lineTo(mapX(polygon[i].x), mapY(polygon[i].y))
                    }
                    path.close()
                    drawPath(path = path, color = Color(0x5000FF00))
                    drawPath(path = path, color = Color(0xA000FF00), style = Stroke(width = 3f))
                }
            }

            // 诊断：绘制醒目的 bbox 矩形（黄色虚线），帮助验证检测框是否对准盲道
            // 如果此框与盲道对齐但绿色网格偏移，说明 mask 解码有问题
            // 如果此框也与盲道不对齐，说明是模型检测或坐标映射的问题
            val dashInterval = 15f
            val diagColor = Color(0xCCFFFF00)
            val diagStroke = 3f
            // 上下左右四条虚线
            // 上边
            var dx = x1
            while (dx < x2) {
                val segEnd = minOf(dx + dashInterval, x2)
                drawLine(diagColor, Offset(dx, y1), Offset(segEnd, y1), strokeWidth = diagStroke)
                dx += dashInterval * 2
            }
            // 下边
            dx = x1
            while (dx < x2) {
                val segEnd = minOf(dx + dashInterval, x2)
                drawLine(diagColor, Offset(dx, y2), Offset(segEnd, y2), strokeWidth = diagStroke)
                dx += dashInterval * 2
            }
            // 左边
            var dy = y1
            while (dy < y2) {
                val segEnd = minOf(dy + dashInterval, y2)
                drawLine(diagColor, Offset(x1, dy), Offset(x1, segEnd), strokeWidth = diagStroke)
                dy += dashInterval * 2
            }
            // 右边
            dy = y1
            while (dy < y2) {
                val segEnd = minOf(dy + dashInterval, y2)
                drawLine(diagColor, Offset(x2, dy), Offset(x2, segEnd), strokeWidth = diagStroke)
                dy += dashInterval * 2
            }

            // 绘制标签
            val canvas = drawContext.canvas.nativeCanvas
            val label = "盲道 ${(detection.confidence * 100).toInt()}%"
            val paint = android.graphics.Paint().apply {
                textSize = 32f
                this.color = android.graphics.Color.WHITE
                isAntiAlias = true
                setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
            }
            val textWidth = paint.measureText(label)
            val bgPaint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.argb(180, 0, 0, 0)
                isAntiAlias = true
            }
            canvas.drawRoundRect(
                android.graphics.RectF(x1 - 4, y1 - 40, x1 + textWidth + 8, y1 + 4),
                8f, 8f, bgPaint
            )
            canvas.drawText(label, x1 + 2, y1 - 8, paint)

        } else {
            // ===== 其他类别：保持矩形框 + 多边形绘制 =====

            // 绘制边界框
            drawRect(
                color = color,
                topLeft = Offset(x1, y1),
                size = Size(x2 - x1, y2 - y1),
                style = Stroke(width = 4f)
            )

            // 绘制分割掩码（如果有多边形数据）
            detection.mask?.polygon?.let { polygon ->
                if (polygon.size >= 3) {
                    val path = androidx.compose.ui.graphics.Path()
                    path.moveTo(mapX(polygon[0].x), mapY(polygon[0].y))
                    for (i in 1 until polygon.size) {
                        path.lineTo(mapX(polygon[i].x), mapY(polygon[i].y))
                    }
                    path.close()

                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.3f)
                    )
                }
            }

            // 绘制标签
            val canvas = drawContext.canvas.nativeCanvas
            val label = "${detection.className} ${(detection.confidence * 100).toInt()}%"
            val paint = android.graphics.Paint().apply {
                textSize = 32f
                this.color = android.graphics.Color.WHITE
                isAntiAlias = true
                setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
            }

            // 标签背景
            val textWidth = paint.measureText(label)
            val bgPaint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.argb(180, 0, 0, 0)
                isAntiAlias = true
            }
            canvas.drawRoundRect(
                android.graphics.RectF(x1 - 4, y1 - 40, x1 + textWidth + 8, y1 + 4),
                8f, 8f, bgPaint
            )
            canvas.drawText(label, x1 + 2, y1 - 8, paint)
        }
    }
}

/**
 * 绘制导航方向箭头
 */
private fun DrawScope.drawNavigationArrow(direction: GuidanceDirection) {
    if (direction == GuidanceDirection.NONE) return

    val centerX = size.width / 2
    val centerY = size.height * 0.7f
    val arrowLength = size.height * 0.15f

    val arrowColor = when (direction) {
        GuidanceDirection.STOP -> Color.Red
        else -> Color.Green
    }

    when (direction) {
        GuidanceDirection.STRAIGHT -> {
            // 向上箭头
            drawLine(
                color = arrowColor,
                start = Offset(centerX, centerY),
                end = Offset(centerX, centerY - arrowLength),
                strokeWidth = 8f
            )
            // 箭头头部
            drawLine(color = arrowColor, start = Offset(centerX, centerY - arrowLength), end = Offset(centerX - 30, centerY - arrowLength + 40), strokeWidth = 8f)
            drawLine(color = arrowColor, start = Offset(centerX, centerY - arrowLength), end = Offset(centerX + 30, centerY - arrowLength + 40), strokeWidth = 8f)
        }
        GuidanceDirection.LEFT_TURN, GuidanceDirection.LEFT_SHIFT -> {
            drawLine(color = arrowColor, start = Offset(centerX, centerY), end = Offset(centerX - arrowLength, centerY), strokeWidth = 8f)
            drawLine(color = arrowColor, start = Offset(centerX - arrowLength, centerY), end = Offset(centerX - arrowLength + 40, centerY - 30), strokeWidth = 8f)
            drawLine(color = arrowColor, start = Offset(centerX - arrowLength, centerY), end = Offset(centerX - arrowLength + 40, centerY + 30), strokeWidth = 8f)
        }
        GuidanceDirection.RIGHT_TURN, GuidanceDirection.RIGHT_SHIFT -> {
            drawLine(color = arrowColor, start = Offset(centerX, centerY), end = Offset(centerX + arrowLength, centerY), strokeWidth = 8f)
            drawLine(color = arrowColor, start = Offset(centerX + arrowLength, centerY), end = Offset(centerX + arrowLength - 40, centerY - 30), strokeWidth = 8f)
            drawLine(color = arrowColor, start = Offset(centerX + arrowLength, centerY), end = Offset(centerX + arrowLength - 40, centerY + 30), strokeWidth = 8f)
        }
        GuidanceDirection.STOP -> {
            drawLine(color = arrowColor, start = Offset(centerX - 40, centerY - 40), end = Offset(centerX + 40, centerY + 40), strokeWidth = 8f)
            drawLine(color = arrowColor, start = Offset(centerX + 40, centerY - 40), end = Offset(centerX - 40, centerY + 40), strokeWidth = 8f)
        }
        else -> {}
    }
}

/**
 * 将 ImageProxy 转换为 Bitmap
 * 使用 CameraX 1.3+ 内置的 toBitmap() 方法，正确处理所有 YUV 格式（包括不同的 pixel stride）
 * 然后应用旋转以匹配显示方向
 */
fun ImageProxy.toRotatedBitmap(): Bitmap {
    // CameraX 内置方法：正确处理 YUV_420_888 各种变体（I420/NV12/NV21）
    val bitmap = this.toBitmap()

    // 处理旋转
    val rotationDegrees = imageInfo.rotationDegrees
    return if (rotationDegrees != 0) {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()
        rotated
    } else {
        bitmap
    }
}
