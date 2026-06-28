/**
 * ObstacleDetector.kt - 障碍物检测器
 * 检测前方障碍物，判断位置和距离，生成避障提示
 */
package com.blindnav.app.detector

import android.graphics.Bitmap
import android.util.Log
import com.blindnav.app.data.*
import com.blindnav.app.ml.YoloOnnxEngine

/**
 * 障碍物检测器
 * 检测画面中的障碍物，计算位置关系并生成语音提示
 */
class ObstacleDetector(
    private val engine: YoloOnnxEngine
) {
    companion object {
        private const val TAG = "ObstacleDetector"

        // 障碍物类别名称映射（英文->中文）
        private val OBSTACLE_NAME_CN = mapOf(
            "person" to "人",
            "bicycle" to "自行车",
            "car" to "车",
            "motorcycle" to "摩托车",
            "bus" to "公交车",
            "truck" to "卡车",
            "dog" to "狗",
            "cat" to "猫",
            "bench" to "长椅",
            "chair" to "椅子",
            "potted plant" to "盆栽",
            "fire hydrant" to "消防栓",
            "backpack" to "背包",
            "umbrella" to "雨伞",
            "handbag" to "手提包",
            "suitcase" to "行李箱",
            "bottle" to "瓶子",
            "stroller" to "婴儿车"
        )

        // 需要检测的障碍物类别（COCO 子集 + 原项目白名单中的 COCO 类别）
        private val OBSTACLE_CLASSES = setOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck",
            "dog", "cat", "bench", "chair", "potted plant",
            "fire hydrant", "backpack", "umbrella", "handbag",
            "suitcase", "bottle"
        )

        // 距离阈值（基于边界框面积比例）
        private const val NEAR_THRESHOLD = 0.15f   // 近距离：面积 > 15%
        private const val MEDIUM_THRESHOLD = 0.05f  // 中距离：面积 > 5%

        // 位置阈值（画面分区）
        private const val LEFT_THRESHOLD = 0.35f
        private const val RIGHT_THRESHOLD = 0.65f

        // 最小置信度
        private const val MIN_CONFIDENCE = 0.4f
    }

    /**
     * 障碍物检测结果
     */
    data class ObstacleDetectionResult(
        val obstacles: List<Obstacle>,
        val hasNearObstacle: Boolean,
        val primaryObstacle: Obstacle? = null,
        val audioPrompt: String = ""
    )

    /**
     * 检测障碍物
     * @param bitmap 输入图像
     * @return 障碍物检测结果
     */
    fun detect(bitmap: Bitmap): ObstacleDetectionResult {
        val detections = engine.runDetection(bitmap)

        // 过滤出障碍物类别
        val obstacleDetections = detections.filter { detection ->
            detection.className in OBSTACLE_CLASSES && detection.confidence >= MIN_CONFIDENCE
        }

        if (obstacleDetections.isEmpty()) {
            return ObstacleDetectionResult(
                obstacles = emptyList(),
                hasNearObstacle = false
            )
        }

        // 转换为 Obstacle 对象
        val obstacles = obstacleDetections.map { detection ->
            val position = calculatePosition(detection.boundingBox)
            val distance = calculateDistance(detection.boundingBox)
            Obstacle(
                detection = detection,
                position = position,
                distance = distance
            )
        }

        // 找到最近/最危险的障碍物
        val nearObstacles = obstacles.filter { it.distance == ObstacleDistance.NEAR }
        val hasNearObstacle = nearObstacles.isNotEmpty()
        val primaryObstacle = nearObstacles.firstOrNull() ?: obstacles.firstOrNull()

        // 生成语音提示
        val audioPrompt = generateAudioPrompt(obstacles, hasNearObstacle)

        return ObstacleDetectionResult(
            obstacles = obstacles,
            hasNearObstacle = hasNearObstacle,
            primaryObstacle = primaryObstacle,
            audioPrompt = audioPrompt
        )
    }

    /**
     * 计算障碍物在画面中的位置
     */
    private fun calculatePosition(box: BoundingBox): ObstaclePosition {
        val centerX = box.centerX
        return when {
            centerX < LEFT_THRESHOLD -> ObstaclePosition.LEFT
            centerX > RIGHT_THRESHOLD -> ObstaclePosition.RIGHT
            else -> ObstaclePosition.CENTER
        }
    }

    /**
     * 根据边界框大小估算距离
     */
    private fun calculateDistance(box: BoundingBox): ObstacleDistance {
        val areaRatio = box.area
        return when {
            areaRatio >= NEAR_THRESHOLD -> ObstacleDistance.NEAR
            areaRatio >= MEDIUM_THRESHOLD -> ObstacleDistance.MEDIUM
            else -> ObstacleDistance.FAR
        }
    }

    /**
     * 生成音频提示文本
     * 根据障碍物位置和类型生成中文提示
     */
    private fun generateAudioPrompt(
        obstacles: List<Obstacle>,
        hasNearObstacle: Boolean
    ): String {
        if (obstacles.isEmpty()) return ""

        // 优先处理近距离障碍物
        val targetObstacle = if (hasNearObstacle) {
            obstacles.filter { it.distance == ObstacleDistance.NEAR }.first()
        } else {
            obstacles.first()
        }

        val cnName = OBSTACLE_NAME_CN[targetObstacle.detection.className]
            ?: targetObstacle.detection.className
        val positionStr = targetObstacle.position.description
        val distanceStr = targetObstacle.distance.description

        // 构建提示文本，匹配预录音频格式
        return when (targetObstacle.distance) {
            ObstacleDistance.NEAR -> {
                // "前方有XX，停一下。" 或 "左侧有XX，停一下。"
                "${positionStr}有${cnName}，${distanceStr}。"
            }
            ObstacleDistance.MEDIUM -> {
                // "前方有XX，注意避让。"
                "${positionStr}有${cnName}，注意避让。"
            }
            ObstacleDistance.FAR -> {
                "" // 远处障碍物不播报
            }
        }
    }

    /**
     * 检查是否有需要紧急避障的障碍物
     */
    fun hasEmergencyObstacle(obstacles: List<Obstacle>): Boolean {
        return obstacles.any {
            it.distance == ObstacleDistance.NEAR &&
            it.position == ObstaclePosition.CENTER
        }
    }
}
