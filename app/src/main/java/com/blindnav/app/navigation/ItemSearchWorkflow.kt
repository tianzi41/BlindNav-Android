/**
 * ItemSearchWorkflow.kt - 物品查找工作流
 * 处理物品查找的完整流程，包括目标锁定、方向引导和抓取确认
 */
package com.blindnav.app.navigation

import android.graphics.Bitmap
import android.util.Log
import com.blindnav.app.data.*
import com.blindnav.app.detector.ItemDetector

/**
 * 物品查找工作流
 * 实现物品查找导航逻辑：
 * 1. 输入目标物品名称
 * 2. 检测目标物品
 * 3. 方向引导靠近
 * 4. 到达确认
 */
class ItemSearchWorkflow(
    private val itemDetector: ItemDetector
) {
    companion object {
        private const val TAG = "ItemSearchWorkflow"

        // 引导播报间隔（毫秒）
        private const val GUIDANCE_INTERVAL = 2000L

        // 到达确认帧数
        private const val ARRIVAL_FRAMES = 5

        // 方向引导音频映射
        private val DIRECTION_AUDIO_MAP = mapOf(
            "在画面左侧" to "在画面左侧",
            "在画面中间" to "在画面中间",
            "在画面右侧" to "在画面右侧",
            "向上" to "向上",
            "向下" to "向下"
        )
    }

    // 当前阶段
    var currentStage = ItemSearchStage.IDLE
        private set

    // 目标物品名称
    var targetItemName = ""
        private set

    // 到达计数
    private var arrivalCount = 0

    // 上次引导播报时间
    private var lastGuidanceTime = 0L

    // 上次方向播报
    private var lastDirection = ""

    /**
     * 物品查找阶段
     */
    enum class ItemSearchStage {
        IDLE,           // 空闲
        SEARCHING,      // 搜索中
        FOUND,          // 已发现目标
        GUIDING,        // 引导靠近中
        ARRIVED,        // 已到达
        LOST            // 目标丢失
    }

    /**
     * 开始查找物品
     * @param itemName 目标物品名称
     */
    fun startSearch(itemName: String) {
        targetItemName = itemName
        currentStage = ItemSearchStage.SEARCHING
        arrivalCount = 0
        lastGuidanceTime = 0
        lastDirection = ""
        Log.i(TAG, "开始查找物品: $itemName")
    }

    /**
     * 停止查找
     */
    fun stopSearch() {
        currentStage = ItemSearchStage.IDLE
        targetItemName = ""
        arrivalCount = 0
    }

    /**
     * 处理一帧
     * @param bitmap 输入图像
     * @return 工作流处理结果
     */
    fun processFrame(bitmap: Bitmap): WorkflowResult {
        if (targetItemName.isEmpty() || currentStage == ItemSearchStage.IDLE) {
            return WorkflowResult(
                stage = ItemSearchStage.IDLE,
                guidance = "",
                statusText = "物品查找未启动"
            )
        }

        val searchResult = itemDetector.search(bitmap, targetItemName)
        val now = System.currentTimeMillis()

        if (!searchResult.found) {
            // 目标丢失
            if (currentStage == ItemSearchStage.GUIDING || currentStage == ItemSearchStage.FOUND) {
                currentStage = ItemSearchStage.LOST
                arrivalCount = 0
                return WorkflowResult(
                    stage = currentStage,
                    guidance = "目标消失，请原地小幅转动。",
                    statusText = "目标丢失"
                )
            }

            // 继续搜索
            return WorkflowResult(
                stage = ItemSearchStage.SEARCHING,
                guidance = "正在搜索 $targetItemName...",
                statusText = "搜索中"
            )
        }

        // 目标已找到
        currentStage = ItemSearchStage.FOUND

        // 检查是否已到达
        if (searchResult.isNear) {
            arrivalCount++
            if (arrivalCount >= ARRIVAL_FRAMES) {
                currentStage = ItemSearchStage.ARRIVED
                return WorkflowResult(
                    stage = currentStage,
                    guidance = "已到达目标前方，请注意。",
                    statusText = "已到达",
                    searchResult = searchResult
                )
            }
        } else {
            arrivalCount = maxOf(0, arrivalCount - 1)
        }

        // 方向引导
        currentStage = ItemSearchStage.GUIDING
        var guidance = ""

        // 生成方向引导
        val directionGuidance = buildDirectionGuidance(searchResult)

        if (now - lastGuidanceTime > GUIDANCE_INTERVAL) {
            guidance = directionGuidance
            lastGuidanceTime = now
        }

        return WorkflowResult(
            stage = currentStage,
            guidance = guidance,
            statusText = "引导靠近 - $targetItemName",
            searchResult = searchResult
        )
    }

    /**
     * 构建方向引导文本
     */
    private fun buildDirectionGuidance(searchResult: ItemDetector.ItemSearchResult): String {
        val parts = mutableListOf<String>()

        // 水平方向
        if (searchResult.horizontalGuidance.isNotEmpty()) {
            parts.add(searchResult.horizontalGuidance)
        }

        // 垂直方向
        if (searchResult.verticalGuidance.isNotEmpty()) {
            parts.add(searchResult.verticalGuidance)
        }

        // 距离引导
        if (searchResult.distanceGuidance.isNotEmpty()) {
            parts.add(searchResult.distanceGuidance)
        }

        return parts.joinToString("，")
    }

    /**
     * 确认找到（用户手动确认）
     */
    fun confirmFound() {
        currentStage = ItemSearchStage.ARRIVED
    }

    /**
     * 重置工作流状态
     */
    fun reset() {
        currentStage = ItemSearchStage.IDLE
        targetItemName = ""
        arrivalCount = 0
        lastGuidanceTime = 0
        lastDirection = ""
    }

    /**
     * 工作流处理结果
     */
    data class WorkflowResult(
        val stage: ItemSearchStage,
        val guidance: String,
        val statusText: String,
        val searchResult: ItemDetector.ItemSearchResult? = null
    )
}
