/**
 * ItemSearchScreen.kt - 物品查找输入界面
 * 输入目标物品名称，显示快捷按钮
 */
package com.blindnav.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blindnav.app.ui.theme.*

/**
 * 物品查找输入界面
 * 大字体输入框 + 常用物品快捷按钮
 *
 * @param onBack 返回按钮回调
 * @param onStartSearch 开始查找回调
 */
@Composable
fun ItemSearchScreen(
    onBack: () -> Unit,
    onStartSearch: (String) -> Unit
) {
    var searchText by remember { mutableStateOf("") }

    // 常用物品快捷列表
    val quickItems = listOf(
        "红牛", "矿泉水", "手机", "钥匙",
        "钱包", "眼镜", "书", "杯子",
        "雨伞", "背包", "耳机", "充电器"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 顶部栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回按钮
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics {
                    contentDescription = "返回主界面"
                }
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 标题
            Text(
                text = "物品查找",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 说明文本
        Text(
            text = "请输入要查找的物品：",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics {
                contentDescription = "请输入要查找的物品名称"
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 输入框
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .semantics {
                    contentDescription = "物品名称输入框，当前输入: $searchText"
                },
            placeholder = {
                Text(
                    text = "例：红牛、矿泉水、手机",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 20.sp
            ),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 常用物品标题
        Text(
            text = "常用物品（快捷按钮）：",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 常用物品快捷按钮网格
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 每行4个按钮
            for (rowIndex in 0 until quickItems.size step 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (colIndex in 0 until 4) {
                        val itemIndex = rowIndex + colIndex
                        if (itemIndex < quickItems.size) {
                            val item = quickItems[itemIndex]
                            OutlinedButton(
                                onClick = { searchText = item },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .semantics {
                                        contentDescription = "快捷选择: $item"
                                    },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text(
                                    text = item,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 开始查找按钮
        Button(
            onClick = {
                if (searchText.isNotBlank()) {
                    onStartSearch(searchText.trim())
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .semantics {
                    contentDescription = "开始查找 $searchText"
                },
            enabled = searchText.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ItemSearchButtonColor,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "开始查找",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
