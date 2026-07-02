/**
 * StatusBar.kt - 状态栏组件
 * 显示当前导航模式、状态和引导文本
 */
package com.blindnav.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blindnav.app.data.NavigationState
import com.blindnav.app.ui.theme.*

/**
 * 状态栏组件
 * 显示当前导航模式和引导文本
 *
 * @param navigationState 当前导航状态
 * @param statusText 状态描述文本
 * @param guidanceText 引导文本
 * @param modifier Modifier
 */
@Composable
fun StatusBar(
    navigationState: NavigationState,
    statusText: String,
    guidanceText: String,
    modifier: Modifier = Modifier
) {
    // 根据导航状态选择颜色
    val statusBarColor by animateColorAsState(
        targetValue = getStatusBarColor(navigationState),
        animationSpec = tween(durationMillis = 300),
        label = "statusBarColor"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(statusBarColor)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 状态模式指示
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标和模式名称
            Text(
                text = getNavigationModeName(navigationState),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            // 状态描述
            if (statusText.isNotEmpty()) {
                Text(
                    text = statusText,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 引导文本（大字体，居中）
        if (guidanceText.isNotEmpty()) {
            Text(
                text = guidanceText,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2
            )
        }
    }
}

/**
 * 获取状态栏颜色
 */
private fun getStatusBarColor(state: NavigationState): Color {
    return when (state) {
        NavigationState.IDLE -> SurfaceVariant
        NavigationState.BLIND_NAV -> BlindNavButtonColor
        NavigationState.CROSS_STREET -> CrossStreetButtonColor
        NavigationState.ITEM_SEARCH -> ItemSearchButtonColor
        NavigationState.OBSTACLE_AVOID -> Error
        NavigationState.CROSSWALK_TEST -> CrossStreetButtonColor
        NavigationState.TRAFFIC_LIGHT_TEST -> Warning
        NavigationState.WAIT_TRAFFIC_LIGHT -> Warning
        NavigationState.SEEKING_CROSSWALK -> CrossStreetButtonColor
        NavigationState.RECOVERY -> Error
    }
}

/**
 * 获取导航模式名称
 */
private fun getNavigationModeName(state: NavigationState): String {
    return when (state) {
        NavigationState.IDLE -> "就绪"
        NavigationState.BLIND_NAV -> "盲道导航"
        NavigationState.CROSS_STREET -> "过马路"
        NavigationState.ITEM_SEARCH -> "物品查找"
        NavigationState.OBSTACLE_AVOID -> "避障"
        NavigationState.CROSSWALK_TEST -> "斑马线测试"
        NavigationState.TRAFFIC_LIGHT_TEST -> "红绿灯测试"
        NavigationState.WAIT_TRAFFIC_LIGHT -> "等待绿灯"
        NavigationState.SEEKING_CROSSWALK -> "对准斑马线"
        NavigationState.RECOVERY -> "恢复中"
    }
}
