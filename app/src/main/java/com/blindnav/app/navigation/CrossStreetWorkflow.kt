/**
 * CrossStreetWorkflow.kt - 过马路工作流
 * 处理过马路的完整流程，包括斑马线检测、红绿灯判定和通行引导
 */
package com.blindnav.app.navigation

import android.graphics.Bitmap
import android.util.Log
import com.blindnav.app.data.*
import com.blindnav.app.detector.CrosswalkDetector
import com.blindnav.app.detector.TrafficLightDetector
import com.blindnav.app.detector.BlindPathDetector

/**
 * 过马路工作流
 * 实现完整的过马路导航逻辑：
 * 1. 检测斑马线
 * 2. 对准斑马线方向
 * 3. 等待绿灯
 * 4. 引导通行
 * 5. 检测到达对岸
 */
class CrossStreetWorkflow(
    private val crosswalkDetector: CrosswalkDetector,
    private val trafficLightDetector: TrafficLightDetector,
    private val blindPathDetector: BlindPathDetector
) {
    companion object {
        private const val TAG = "CrossStreetWorkflow"

        // 绿灯稳定帧数
        private const val GREEN_LIGHT_STABLE_FRAMES = 5

        // 绿灯播报间隔（毫秒）
        private const val GREEN_LIGHT_ANNOUNCE_INTERVAL = 5000L

        // 到达对岸检测帧数
        private const val ARRIVAL_FRAMES = 10
    }

    // 当前阶段
    var currentStage = CrossStreetStage.SEEKING_CROSSWALK
        private set

    // 绿灯稳定计数
    private var greenLightStableCount = 0

    // 到达对岸计数
    private var arrivalCount = 0

    // 上次绿灯播报时间
    private var lastGreenLightAnnounce = 0L

    // 上次通行播报时间
    private var lastCrossingAnnounce = 0L

    /**
     * 过马路阶段
     */
    enum class CrossStreetStage {
        SEEKING_CROSSWALK,      // 寻找斑马线
        ALIGNING,               // 对准斑马线
        WAITING_FOR_GREEN,      // 等待绿灯
        CROSSING,               // 通行中
        ARRIVING                // 到达对岸
    }

    /**
     * 处理一帧
     * @param bitmap 输入图像
     * @return 工作流处理结果
     */
    fun processFrame(bitmap: Bitmap): WorkflowResult {
        val crosswalkResult = crosswalkDetector.detect(bitmap)
        val trafficLightResult = trafficLightDetector.detect(bitmap)
        val blindResult = blindPathDetector.detect(bitmap)

        return when (currentStage) {
            CrossStreetStage.SEEKING_CROSSWALK -> handleSeeking(crosswalkResult)
            CrossStreetStage.ALIGNING -> handleAligning(crosswalkResult)
            CrossStreetStage.WAITING_FOR_GREEN -> handleWaitingGreen(trafficLightResult)
            CrossStreetStage.CROSSING -> handleCrossing(crosswalkResult, blindResult)
            CrossStreetStage.ARRIVING -> handleArriving(blindResult)
        }
    }

    /**
     * 寻找斑马线阶段
     */
    private fun handleSeeking(crosswalkResult: CrosswalkDetector.CrosswalkResult): WorkflowResult {
        if (crosswalkResult.detected) {
            currentStage = CrossStreetStage.ALIGNING
            return WorkflowResult(
                stage = currentStage,
                guidance = "发现斑马线，对准方向。",
                statusText = "发现斑马线"
            )
        }

        return WorkflowResult(
            stage = currentStage,
            guidance = "远处发现斑马线，继续直行。",
            statusText = "寻找斑马线"
        )
    }

    /**
     * 对准斑马线阶段
     */
    private fun handleAligning(crosswalkResult: CrosswalkDetector.CrosswalkResult): WorkflowResult {
        if (!crosswalkResult.detected) {
            currentStage = CrossStreetStage.SEEKING_CROSSWALK
            return WorkflowResult(
                stage = currentStage,
                guidance = "斑马线丢失，重新寻找。",
                statusText = "寻找斑马线"
            )
        }

        // 检查是否对准
        if (crosswalkResult.isAligned && crosswalkResult.stage == CrosswalkDetector.CrosswalkStage.READY) {
            currentStage = CrossStreetStage.WAITING_FOR_GREEN
            return WorkflowResult(
                stage = currentStage,
                guidance = "已对准, 准备切换过马路模式。",
                statusText = "等待绿灯"
            )
        }

        // 引导对准
        val guidance = crosswalkDetector.getAlignmentGuidance(crosswalkResult.centerXRatio)

        return WorkflowResult(
            stage = currentStage,
            guidance = guidance,
            statusText = "对准斑马线"
        )
    }

    /**
     * 等待绿灯阶段
     */
    private fun handleWaitingGreen(trafficLightResult: TrafficLightDetector.TrafficLightResult): WorkflowResult {
        val now = System.currentTimeMillis()

        when (trafficLightResult.stableState) {
            TrafficLightState.GREEN -> {
                greenLightStableCount++
                if (greenLightStableCount >= GREEN_LIGHT_STABLE_FRAMES) {
                    currentStage = CrossStreetStage.CROSSING
                    greenLightStableCount = 0
                    return WorkflowResult(
                        stage = currentStage,
                        guidance = "绿灯稳定，开始通行。",
                        statusText = "通行中"
                    )
                }
            }
            TrafficLightState.RED -> {
                greenLightStableCount = 0
                if (now - lastGreenLightAnnounce > GREEN_LIGHT_ANNOUNCE_INTERVAL) {
                    lastGreenLightAnnounce = now
                    return WorkflowResult(
                        stage = currentStage,
                        guidance = "红灯",
                        statusText = "等待绿灯 - 红灯"
                    )
                }
            }
            TrafficLightState.YELLOW -> {
                greenLightStableCount = 0
                return WorkflowResult(
                    stage = currentStage,
                    guidance = "黄灯",
                    statusText = "等待绿灯 - 黄灯"
                )
            }
            else -> {
                greenLightStableCount = 0
            }
        }

        return WorkflowResult(
            stage = currentStage,
            guidance = "",
            statusText = "等待绿灯"
        )
    }

    /**
     * 通行中阶段
     */
    private fun handleCrossing(
        crosswalkResult: CrosswalkDetector.CrosswalkResult,
        blindResult: BlindPathDetector.BlindPathResult
    ): WorkflowResult {
        val now = System.currentTimeMillis()

        // 检查是否到达对岸（盲道出现或斑马线消失）
        if (blindResult.detected && blindResult.areaRatio > 0.05f) {
            arrivalCount++
            if (arrivalCount >= ARRIVAL_FRAMES) {
                currentStage = CrossStreetStage.ARRIVING
                return WorkflowResult(
                    stage = currentStage,
                    guidance = "已到盲道跟前，切换到盲道导航。",
                    statusText = "到达对岸"
                )
            }
        } else {
            arrivalCount = maxOf(0, arrivalCount - 1)
        }

        // 检查斑马线对准
        if (crosswalkResult.detected && !crosswalkResult.isAligned) {
            if (now - lastCrossingAnnounce > 3000L) {
                lastCrossingAnnounce = now
                return WorkflowResult(
                    stage = currentStage,
                    guidance = crosswalkDetector.getAlignmentGuidance(crosswalkResult.centerXRatio),
                    statusText = "通行中 - 调整方向"
                )
            }
        }

        return WorkflowResult(
            stage = currentStage,
            guidance = "方向正确，请直行。",
            statusText = "通行中"
        )
    }

    /**
     * 到达对岸阶段
     */
    private fun handleArriving(blindResult: BlindPathDetector.BlindPathResult): WorkflowResult {
        if (blindResult.detected && blindResult.areaRatio > 0.02f) {
            return WorkflowResult(
                stage = currentStage,
                guidance = "过马路结束，准备上人行道。",
                statusText = "到达对岸",
                shouldSwitchToBlindNav = true
            )
        }

        return WorkflowResult(
            stage = currentStage,
            guidance = "寻找盲道...",
            statusText = "寻找盲道"
        )
    }

    /**
     * 重置工作流状态
     */
    fun reset() {
        currentStage = CrossStreetStage.SEEKING_CROSSWALK
        greenLightStableCount = 0
        arrivalCount = 0
        lastGreenLightAnnounce = 0
        lastCrossingAnnounce = 0
    }

    /**
     * 工作流处理结果
     */
    data class WorkflowResult(
        val stage: CrossStreetStage,
        val guidance: String,
        val statusText: String,
        val shouldSwitchToBlindNav: Boolean = false
    )
}
