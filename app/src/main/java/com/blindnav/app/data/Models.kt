/**
 * Models.kt - 数据模型定义
 * 定义检测结果、障碍物、导航状态等核心数据结构
 */
package com.blindnav.app.data

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * 检测结果 - 包含边界框、置信度、类别和可选的分割掩码
 */
data class DetectionResult(
    val boundingBox: BoundingBox,
    val confidence: Float,
    val classId: Int,
    val className: String,
    val mask: SegmentationMask? = null
)

/**
 * 边界框 - 归一化坐标 (0.0 ~ 1.0)
 */
data class BoundingBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
) {
    /** 计算中心点 X 坐标 */
    val centerX: Float get() = (x1 + x2) / 2f

    /** 计算中心点 Y 坐标 */
    val centerY: Float get() = (y1 + y2) / 2f

    /** 计算宽度 */
    val width: Float get() = x2 - x1

    /** 计算高度 */
    val height: Float get() = y2 - y1

    /** 计算面积比例 */
    val area: Float get() = width * height

    /** 转换为 Android RectF */
    fun toRectF(imageWidth: Int, imageHeight: Int): RectF {
        return RectF(
            x1 * imageWidth,
            y1 * imageHeight,
            x2 * imageWidth,
            y2 * imageHeight
        )
    }
}

/**
 * 分割掩码 - 多边形轮廓点列表 + 可选的像素级掩码网格
 * @param polygon 轮廓多边形（归一化坐标）
 * @param maskBitmap 掩码位图（可选）
 * @param maskGrid 下采样二值掩码网格（用于像素级渲染）
 * @param gridWidth 网格宽度
 * @param gridHeight 网格高度
 */
data class SegmentationMask(
    val polygon: List<Point>,
    val maskBitmap: Bitmap? = null,
    val maskGrid: BooleanArray? = null,
    val gridWidth: Int = 0,
    val gridHeight: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SegmentationMask) return false
        return polygon == other.polygon && maskBitmap == other.maskBitmap &&
                gridWidth == other.gridWidth && gridHeight == other.gridHeight
    }

    override fun hashCode(): Int {
        var result = polygon.hashCode()
        result = 31 * result + (maskBitmap?.hashCode() ?: 0)
        result = 31 * result + gridWidth
        result = 31 * result + gridHeight
        return result
    }
}

/**
 * 二维点
 */
data class Point(
    val x: Float,
    val y: Float
)

/**
 * 障碍物信息 - 扩展检测结果，包含位置描述
 */
data class Obstacle(
    val detection: DetectionResult,
    val position: ObstaclePosition,
    val distance: ObstacleDistance
)

/**
 * 障碍物位置（画面中的方位）
 */
enum class ObstaclePosition(val description: String) {
    LEFT("左侧"),
    CENTER("前方"),
    RIGHT("右侧")
}

/**
 * 障碍物距离估算
 */
enum class ObstacleDistance(val description: String) {
    NEAR("停一下"),
    MEDIUM("注意避让"),
    FAR("远处")
}

/**
 * 导航状态 - 状态机的所有可能状态
 */
enum class NavigationState {
    /** 空闲状态，未进行导航 */
    IDLE,

    /** 盲道导航模式 */
    BLIND_NAV,

    /** 过马路模式 */
    CROSS_STREET,

    /** 物品查找模式 */
    ITEM_SEARCH,

    /** 障碍物避障模式 */
    OBSTACLE_AVOID,

    /** 等待交通灯 */
    WAIT_TRAFFIC_LIGHT,

    /** 斑马线对准阶段 */
    SEEKING_CROSSWALK,

    /** 寻找下一段盲道 */
    SEEKING_NEXT_BLINDPATH,

    /** 恢复模式（感知丢失） */
    RECOVERY
}

/**
 * 盲道导航子状态
 */
enum class BlindPathState {
    /** 引导对准盲道 */
    ONBOARDING,

    /** 正在导航 */
    NAVIGATING,

    /** 转弯操作中 */
    MANEUVERING_TURN,

    /** 避障操作中 */
    AVOIDING_OBSTACLE,

    /** 锁定目标 */
    LOCKING_ON,

    /** 未知状态 */
    UNKNOWN
}

/**
 * 导航引导指令
 */
enum class GuidanceDirection(val audioKey: String) {
    STRAIGHT("保持直行"),
    LEFT_TURN("左转"),
    RIGHT_TURN("右转"),
    LEFT_SHIFT("向左平移"),
    RIGHT_SHIFT("向右平移"),
    STOP("停一下"),
    CALIBRATED("校准完成！您已在盲道上，开始前行。"),
    LOST("丢失路径，重新搜索。"),
    OBSTACLE_AHEAD("前方有障碍物，停一下。"),
    NONE("")
}

/**
 * 红绿灯状态
 */
enum class TrafficLightState(val audioKey: String) {
    RED("红灯"),
    GREEN("绿灯"),
    YELLOW("黄灯"),
    UNKNOWN("")
}

/**
 * UI 界面模式
 */
enum class ScreenMode {
    /** 主界面 */
    MAIN,

    /** 物品查找输入界面 */
    ITEM_SEARCH_INPUT,

    /** 日志查看界面 */
    LOG_VIEWER
}

/**
 * 物品查找结果
 */
data class ItemSearchResult(
    val targetName: String,
    val found: Boolean,
    val guidance: String = ""
)
