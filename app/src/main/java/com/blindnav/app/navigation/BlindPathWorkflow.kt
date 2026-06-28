/**
 * BlindPathWorkflow.kt - 盲道导航工作流
 * 处理盲道导航的完整流程，包括对准、直行、转弯和避障
 */
package com.blindnav.app.navigation

import android.graphics.Bitmap
import android.util.Log
import com.blindnav.app.data.*
import com.blindnav.app.detector.BlindPathDetector
import com.blindnav.app.detector.CrosswalkDetector
import com.blindnav.app.detector.ObstacleDetector

/**
 * 盲道导航工作流
 * 实现完整的盲道导航逻辑，包括：
 * 1. 引导对准盲道 (ONBOARDING)
 * 2. 直行导航 (NAVIGATING)
 * 3. 转弯处理 (MANEUVERING_TURN)
 * 4. 避障处理 (AVOIDING_OBSTACLE)
 */
class BlindPathWorkflow(
    private val blindPathDetector: BlindPathDetector,
    private val obstacleDetector: ObstacleDetector,
    private val crosswalkDetector: CrosswalkDetector
) {
    companion object {
        private const val TAG = "BlindPathWorkflow"

        // 直行播报间隔（毫秒）
        private const val STRAIGHT_GUIDE_INTERVAL = 4000L

        // 方向指令间隔（毫秒）
        private const val DIRECTION_INTERVAL = 3000L

        // 转弯检测持续帧数
        private const val TURN_CONSECUTIVE_FRAMES = 5

        // 避障冷却帧数
        private const val AVOIDANCE_COOLDOWN = 30
    }

    // 当前子状态
    var currentSubState = BlindPathState.ONBOARDING
        private set

    // 上次直行播报时间
    private var lastStraightGuideTime = 0L

    // 上次方向指令时间
    private var lastDirectionTime = 0L

    // 转弯检测状态
    private var turnDirection: GuidanceDirection? = null
    private var turnConsecutiveCount = 0

    // 避障冷却
    private var avoidanceCooldown = 0

    // 帧计数器
    private var frameCount = 0

    /**
     * 处理一帧
     * @param bitmap 输入图像
     * @return 工作流处理结果
     */
    fun processFrame(bitmap: Bitmap): WorkflowResult {
        frameCount++

        // 检测障碍物
        val obstacleResult = obstacleDetector.detect(bitmap)
        if (obstacleResult.hasNearObstacle && avoidanceCooldown <= 0) {
            currentSubState = BlindPathState.AVOIDING_OBSTACLE
            avoidanceCooldown = AVOIDANCE_COOLDOWN
            return WorkflowResult(
                subState = currentSubState,
                guidance = obstacleResult.audioPrompt,
                statusText = "检测到障碍物",
                obstacleDetected = true
            )
        }

        if (avoidanceCooldown > 0) avoidanceCooldown--

        // 检测盲道
        val blindResult = blindPathDetector.detect(bitmap)

        return when (currentSubState) {
            BlindPathState.ONBOARDING -> handleOnboarding(blindResult)
            BlindPathState.NAVIGATING -> handleNavigating(blindResult, bitmap)
            BlindPathState.MANEUVERING_TURN -> handleTurn(blindResult)
            BlindPathState.AVOIDING_OBSTACLE -> handleAvoidance(blindResult)
            BlindPathState.LOCKING_ON -> handleLockingOn(blindResult)
            BlindPathState.UNKNOWN -> handleUnknown(blindResult)
        }
    }

    /**
     * 引导对准阶段
     */
    private fun handleOnboarding(blindResult: BlindPathDetector.BlindPathResult): WorkflowResult {
        if (!blindResult.detected) {
            return WorkflowResult(
                subState = BlindPathState.ONBOARDING,
                guidance = "没看到盲道，请向右侧小幅移动。",
                statusText = "寻找盲道"
            )
        }

        // 检查是否对准
        if (blindPathDetector.isCentered(blindResult.centerXRatio)) {
            currentSubState = BlindPathState.NAVIGATING
            return WorkflowResult(
                subState = BlindPathState.NAVIGATING,
                guidance = "校准完成！您已在盲道上，开始前行。",
                statusText = "导航中"
            )
        }

        // 引导对准
        val offset = blindPathDetector.calculateOffset(blindResult.centerXRatio)
        val guidance = if (offset < 0) {
            "请向右微调，对准盲道。"
        } else {
            "请向左微调，对准盲道。"
        }

        return WorkflowResult(
            subState = BlindPathState.ONBOARDING,
            guidance = guidance,
            statusText = "对准盲道"
        )
    }

    /**
     * 直行导航阶段
     */
    private fun handleNavigating(
        blindResult: BlindPathDetector.BlindPathResult,
        bitmap: Bitmap
    ): WorkflowResult {
        if (!blindResult.detected) {
            currentSubState = BlindPathState.ONBOARDING
            return WorkflowResult(
                subState = BlindPathState.ONBOARDING,
                guidance = "丢失路径，重新搜索。",
                statusText = "寻找盲道"
            )
        }

        // 检查是否需要转弯
        if (blindResult.guidance == GuidanceDirection.LEFT_TURN ||
            blindResult.guidance == GuidanceDirection.RIGHT_TURN
        ) {
            turnConsecutiveCount++
            if (turnConsecutiveCount >= TURN_CONSECUTIVE_FRAMES) {
                turnDirection = blindResult.guidance
                currentSubState = BlindPathState.MANEUVERING_TURN
                turnConsecutiveCount = 0
                return WorkflowResult(
                    subState = BlindPathState.MANEUVERING_TURN,
                    guidance = blindResult.guidance.audioKey,
                    statusText = "转弯中"
                )
            }
        } else {
            turnConsecutiveCount = maxOf(0, turnConsecutiveCount - 1)
        }

        // 检查偏移
        val offset = blindPathDetector.calculateOffset(blindResult.centerXRatio)
        val now = System.currentTimeMillis()

        var guidance = ""

        // 方向纠正
        if (Math.abs(offset) > 0.1f && now - lastDirectionTime > DIRECTION_INTERVAL) {
            guidance = if (offset < 0) {
                "稍微向右调整，继续前进。"
            } else {
                "稍微向左调整，继续前进。"
            }
            lastDirectionTime = now
        }

        // 直行播报
        if (guidance.isEmpty() && now - lastStraightGuideTime > STRAIGHT_GUIDE_INTERVAL) {
            guidance = "保持直行"
            lastStraightGuideTime = now
        }

        return WorkflowResult(
            subState = BlindPathState.NAVIGATING,
            guidance = guidance,
            statusText = "导航中 - 直行"
        )
    }

    /**
     * 转弯处理阶段
     */
    private fun handleTurn(blindResult: BlindPathDetector.BlindPathResult): WorkflowResult {
        if (!blindResult.detected) {
            currentSubState = BlindPathState.ONBOARDING
            return WorkflowResult(
                subState = BlindPathState.ONBOARDING,
                guidance = "丢失路径，重新搜索。",
                statusText = "寻找盲道"
            )
        }

        // 检查转弯是否完成
        if (blindPathDetector.isCentered(blindResult.centerXRatio)) {
            currentSubState = BlindPathState.NAVIGATING
            turnDirection = null
            return WorkflowResult(
                subState = BlindPathState.NAVIGATING,
                guidance = "方向正确，请继续前进。",
                statusText = "导航中"
            )
        }

        // 继续引导转弯
        val guidance = turnDirection?.audioKey ?: "保持直行"

        return WorkflowResult(
            subState = BlindPathState.MANEUVERING_TURN,
            guidance = guidance,
            statusText = "转弯中"
        )
    }

    /**
     * 避障处理阶段
     */
    private fun handleAvoidance(blindResult: BlindPathDetector.BlindPathResult): WorkflowResult {
        // 等待障碍物消失或用户手动确认
        return WorkflowResult(
            subState = BlindPathState.AVOIDING_OBSTACLE,
            guidance = "请小心避让障碍物。",
            statusText = "避障中"
        )
    }

    /**
     * 锁定目标阶段（用于物品查找）
     */
    private fun handleLockingOn(blindResult: BlindPathDetector.BlindPathResult): WorkflowResult {
        return WorkflowResult(
            subState = BlindPathState.LOCKING_ON,
            guidance = "",
            statusText = "锁定目标"
        )
    }

    /**
     * 未知状态处理
     */
    private fun handleUnknown(blindResult: BlindPathDetector.BlindPathResult): WorkflowResult {
        currentSubState = BlindPathState.ONBOARDING
        return WorkflowResult(
            subState = BlindPathState.ONBOARDING,
            guidance = "",
            statusText = "初始化"
        )
    }

    /**
     * 重置工作流状态
     */
    fun reset() {
        currentSubState = BlindPathState.ONBOARDING
        turnDirection = null
        turnConsecutiveCount = 0
        avoidanceCooldown = 0
        lastStraightGuideTime = 0
        lastDirectionTime = 0
        frameCount = 0
    }

    /**
     * 工作流处理结果
     */
    data class WorkflowResult(
        val subState: BlindPathState,
        val guidance: String,
        val statusText: String,
        val obstacleDetected: Boolean = false
    )
}
