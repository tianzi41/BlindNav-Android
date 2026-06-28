/**
 * ItemDetector.kt - 物品查找检测器
 * 使用 YOLO 检测目标物品，结合手部检测引导用户抓取
 */
package com.blindnav.app.detector

import android.graphics.Bitmap
import android.util.Log
import com.blindnav.app.data.BoundingBox
import com.blindnav.app.data.DetectionResult
import com.blindnav.app.ml.YoloOnnxEngine

/**
 * 物品查找检测器
 * 检测用户指定的目标物品，计算相对位置并生成引导指令
 */
class ItemDetector(
    private val engine: YoloOnnxEngine
) {
    companion object {
        private const val TAG = "ItemDetector"
        private const val MIN_CONFIDENCE = 0.3f

        // 位置分区
        private const val LEFT_THRESHOLD = 0.35f
        private const val RIGHT_THRESHOLD = 0.65f
        private const val TOP_THRESHOLD = 0.35f
        private const val BOTTOM_THRESHOLD = 0.65f

        // 接近判定阈值（物品面积占比）
        private const val NEAR_THRESHOLD = 0.1f

        // 物品名称中英文映射（同时支持 COCO 和 shopping 模型的类名）
        val ITEM_NAME_MAP = mapOf(
            "红牛" to "Red_Bull",
            "AD钙奶" to "AD_milk",
            "ad钙奶" to "AD_milk",
            "钙奶" to "AD_milk",
            "矿泉水" to "bottle",
            "水" to "bottle",
            "手机" to "cell phone",
            "书" to "book",
            "杯子" to "cup",
            "雨伞" to "umbrella",
            "背包" to "backpack",
            "笔记本" to "laptop",
            "可乐" to "coke",
            "牛奶" to "milk",
            "饮料" to "bottle"
        )
    }

    /**
     * 物品查找结果
     */
    data class ItemSearchResult(
        val found: Boolean,
        val detection: DetectionResult? = null,
        val horizontalGuidance: String = "",
        val verticalGuidance: String = "",
        val distanceGuidance: String = "",
        val isNear: Boolean = false
    )

    /**
     * 查找指定物品
     * 优先使用商品专用模型，回退到 COCO 通用模型
     * @param bitmap 输入图像
     * @param targetName 目标物品名称（英文或中文）
     * @return 物品查找结果
     */
    fun search(bitmap: Bitmap, targetName: String): ItemSearchResult {
        // 优先使用商品专用模型
        val detections = if (engine.isShoppingModelLoaded()) {
            engine.runShoppingDetection(bitmap)
        } else {
            engine.runDetection(bitmap)
        }

        // 物品名称映射（中文->英文）
        val englishName = ITEM_NAME_MAP[targetName] ?: targetName.lowercase()

        // 过滤出目标物品检测结果（双向不区分大小写匹配）
        val itemDetections = detections.filter { detection ->
            val cn = detection.className.lowercase()
            val en = englishName.lowercase()
            (cn.contains(en) || en.contains(cn)) &&
            detection.confidence >= MIN_CONFIDENCE
        }

        if (itemDetections.isEmpty()) {
            return ItemSearchResult(found = false)
        }

        // 选择置信度最高的物品
        val bestDetection = itemDetections.maxByOrNull { it.confidence }
            ?: return ItemSearchResult(found = false)

        val box = bestDetection.boundingBox

        // 计算水平引导
        val horizontalGuidance = getHorizontalGuidance(box.centerX)

        // 计算垂直引导
        val verticalGuidance = getVerticalGuidance(box.centerY)

        // 计算距离引导
        val areaRatio = box.area
        val isNear = areaRatio >= NEAR_THRESHOLD
        val distanceGuidance = getDistanceGuidance(areaRatio)

        return ItemSearchResult(
            found = true,
            detection = bestDetection,
            horizontalGuidance = horizontalGuidance,
            verticalGuidance = verticalGuidance,
            distanceGuidance = distanceGuidance,
            isNear = isNear
        )
    }

    /**
     * 获取水平方向引导
     */
    private fun getHorizontalGuidance(centerX: Float): String {
        return when {
            centerX < LEFT_THRESHOLD -> "在画面左侧"
            centerX > RIGHT_THRESHOLD -> "在画面右侧"
            else -> "在画面中间"
        }
    }

    /**
     * 获取垂直方向引导
     */
    private fun getVerticalGuidance(centerY: Float): String {
        return when {
            centerY < TOP_THRESHOLD -> "向上"
            centerY > BOTTOM_THRESHOLD -> "向下"
            else -> ""
        }
    }

    /**
     * 获取距离引导
     */
    private fun getDistanceGuidance(areaRatio: Float): String {
        return when {
            areaRatio >= NEAR_THRESHOLD -> "已到达目标前方，请注意。"
            areaRatio >= 0.03f -> "目标就在前方，请慢慢靠近。"
            else -> "远处有目标，继续前行。"
        }
    }

}
