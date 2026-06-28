/**
 * Color.kt - 颜色定义
 * 定义应用主题颜色和辅助颜色
 */
package com.blindnav.app.ui.theme

import androidx.compose.ui.graphics.Color

// 主题颜色 - 高对比度，适合视障用户
val Primary = Color(0xFF1976D2)      // 蓝色主色
val PrimaryDark = Color(0xFF0D47A1)  // 深蓝主色
val PrimaryLight = Color(0xFF64B5F6) // 浅蓝主色

val Secondary = Color(0xFFFFA000)    // 琥珀色强调
val SecondaryDark = Color(0xFFFF8F00)
val SecondaryLight = Color(0xFFFFD54F)

val Background = Color(0xFF121212)   // 深色背景
val Surface = Color(0xFF1E1E1E)      // 表面色
val SurfaceVariant = Color(0xFF2D2D2D)

val OnPrimary = Color.White
val OnSecondary = Color.Black
val OnBackground = Color.White
val OnSurface = Color.White

// 状态颜色
val Success = Color(0xFF4CAF50)      // 绿色 - 成功/通行
val Warning = Color(0xFFFF9800)      // 橙色 - 警告
val Error = Color(0xFFF44336)        // 红色 - 错误/停止
val Info = Color(0xFF2196F3)         // 蓝色 - 信息

// 导航按钮颜色
val BlindNavButtonColor = Color(0xFF1976D2)   // 盲道导航 - 蓝色
val CrossStreetButtonColor = Color(0xFFFF9800) // 过马路 - 橙色
val ItemSearchButtonColor = Color(0xFF9C27B0)  // 物品查找 - 紫色
val StopButtonColor = Color(0xFFF44336)        // 停止 - 红色

// 检测叠加颜色
val DetectionBoxColor = Color(0xFF00FF00)      // 检测框 - 绿色
val ObstacleBoxColor = Color(0xFFFF0000)       // 障碍物框 - 红色
val BlindPathMaskColor = Color(0x8000FF00)     // 盲道掩码 - 半透明绿
val CrosswalkMaskColor = Color(0x80FFA000)     // 斑马线掩码 - 半透明橙

// 文本颜色
val TextPrimary = Color.White
val TextSecondary = Color(0xFFB0B0B0)
val TextOnDark = Color.White
val TextOnLight = Color.Black
