/**
 * ControlButton.kt - 控制按钮组件
 * 大按钮组件，高对比度配色，支持无障碍描述
 */
package com.blindnav.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 大控制按钮组件
 * 用于视障用户的导航控制，按钮高度 52dp
 */
@Composable
fun ControlButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    buttonColor: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String = text,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier
            .heightIn(min = 52.dp)
            .semantics {
                this.contentDescription = contentDescription
            },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor,
            contentColor = Color.White,
            disabledContainerColor = buttonColor.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 6.dp,
            pressedElevation = 2.dp,
            disabledElevation = 0.dp
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 图标（如果有）
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 按钮文本
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/**
 * 紧凑型控制按钮
 * 用于辅助控制栏
 */
@Composable
fun CompactControlButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    buttonColor: Color = MaterialTheme.colorScheme.surface,
    contentDescription: String = text,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    OutlinedButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                this.contentDescription = contentDescription
            },
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = buttonColor,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
