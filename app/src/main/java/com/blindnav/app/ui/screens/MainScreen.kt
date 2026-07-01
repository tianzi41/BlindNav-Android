/**
 * MainScreen.kt - 主界面
 * 全屏相机预览 + 底部导航按钮 + 状态栏 + 运行状态
 */
package com.blindnav.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blindnav.app.data.NavigationState
import com.blindnav.app.data.ScreenMode
import com.blindnav.app.ui.components.*
import com.blindnav.app.ui.theme.*
import com.blindnav.app.viewmodel.MainViewModel
import com.blindnav.app.camera.CameraManager

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val navigationState by viewModel.navigationState.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val guidanceText by viewModel.guidanceText.collectAsState()
    val detections by viewModel.detections.collectAsState()
    val guidanceDirection by viewModel.guidanceDirection.collectAsState()
    val screenMode by viewModel.screenMode.collectAsState()
    val cameraRunning by viewModel.cameraRunning.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val modelsLoaded by viewModel.modelsLoaded.collectAsState()
    val modelLoadStatus by viewModel.modelLoadStatus.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraManager = remember(lifecycleOwner) {
        CameraManager(context, lifecycleOwner)
    }

    val currentNavState = rememberUpdatedState(navigationState)
    val onFrameAvailable: (Bitmap) -> Unit = remember {
        { bitmap ->
            if (currentNavState.value == NavigationState.ITEM_SEARCH) {
                viewModel.processItemSearchFrame(bitmap)
            } else {
                viewModel.processFrame(bitmap)
            }
        }
    }

    when (screenMode) {
        ScreenMode.MAIN -> {
            MainContent(
                navigationState = navigationState,
                statusText = statusText,
                guidanceText = guidanceText,
                detections = detections,
                guidanceDirection = guidanceDirection,
                cameraRunning = cameraRunning,
                errorMessage = errorMessage,
                modelsLoaded = modelsLoaded,
                modelLoadStatus = modelLoadStatus,
                onStartBlindNav = { viewModel.startBlindPathNavigation() },
                onStartCrossStreet = { viewModel.startCrossStreet() },
                onStartItemSearch = { viewModel.showItemSearchInput() },
                onStop = { viewModel.stopNavigation() },
                onConfirmFound = { viewModel.confirmItemFound() },
                onShowLog = { viewModel.showLogViewer() },
                onFrameAvailable = onFrameAvailable,
                cameraManager = cameraManager,
                onCameraRunningChanged = { viewModel.setCameraRunning(it) },
                onClearError = { viewModel.clearError() }
            )
        }
        ScreenMode.ITEM_SEARCH_INPUT -> {
            ItemSearchScreen(
                onBack = { viewModel.showMainScreen() },
                onStartSearch = { itemName -> viewModel.startItemSearch(itemName) }
            )
        }
        ScreenMode.LOG_VIEWER -> {
            LogViewerScreen(
                onBack = { viewModel.showMainScreen() }
            )
        }
    }
}

@Composable
private fun MainContent(
    navigationState: NavigationState,
    statusText: String,
    guidanceText: String,
    detections: List<com.blindnav.app.data.DetectionResult>,
    guidanceDirection: com.blindnav.app.data.GuidanceDirection,
    cameraRunning: Boolean,
    errorMessage: String?,
    modelsLoaded: Boolean,
    modelLoadStatus: String,
    onStartBlindNav: () -> Unit,
    onStartCrossStreet: () -> Unit,
    onStartItemSearch: () -> Unit,
    onStop: () -> Unit,
    onConfirmFound: () -> Unit,
    onShowLog: () -> Unit,
    onFrameAvailable: (Bitmap) -> Unit,
    cameraManager: CameraManager,
    onCameraRunningChanged: (Boolean) -> Unit,
    onClearError: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 相机预览（全屏）
        CameraPreview(
            cameraManager = cameraManager,
            detections = detections,
            guidanceDirection = guidanceDirection,
            onFrameAvailable = onFrameAvailable,
            onCameraRunningChanged = onCameraRunningChanged,
            modifier = Modifier.fillMaxSize()
        )

        // 底部控制区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            // 引导文本状态栏
            StatusBar(
                navigationState = navigationState,
                statusText = statusText,
                guidanceText = guidanceText,
                modifier = Modifier.fillMaxWidth()
            )

            // 运行状态信息栏
            RuntimeStatusBar(
                modelsLoaded = modelsLoaded,
                modelLoadStatus = modelLoadStatus,
                cameraRunning = cameraRunning,
                errorMessage = errorMessage,
                navigationState = navigationState,
                onClearError = onClearError
            )

            // 导航按钮区域 - 缩小尺寸
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ControlButton(
                        text = "盲道导航",
                        onClick = onStartBlindNav,
                        icon = Icons.Default.DirectionsWalk,
                        buttonColor = BlindNavButtonColor,
                        contentDescription = "开始盲道导航模式",
                        enabled = modelsLoaded && (navigationState == NavigationState.IDLE || navigationState == NavigationState.BLIND_NAV),
                        modifier = Modifier.weight(1f)
                    )

                    ControlButton(
                        text = "过马路",
                        onClick = onStartCrossStreet,
                        icon = Icons.Default.Traffic,
                        buttonColor = CrossStreetButtonColor,
                        contentDescription = "启动过马路辅助模式",
                        enabled = modelsLoaded && (navigationState == NavigationState.IDLE || navigationState == NavigationState.BLIND_NAV),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ControlButton(
                        text = "物品查找",
                        onClick = onStartItemSearch,
                        icon = Icons.Default.Search,
                        buttonColor = ItemSearchButtonColor,
                        contentDescription = "启动物品查找模式",
                        enabled = modelsLoaded && (navigationState == NavigationState.IDLE || navigationState == NavigationState.ITEM_SEARCH),
                        modifier = Modifier.weight(1f)
                    )

                    ControlButton(
                        text = "停止",
                        onClick = onStop,
                        icon = Icons.Default.Stop,
                        buttonColor = StopButtonColor,
                        contentDescription = "停止当前导航",
                        enabled = navigationState != NavigationState.IDLE,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 日志查看按钮
                    CompactControlButton(
                        text = "日志",
                        onClick = onShowLog,
                        icon = Icons.Default.List,
                        contentDescription = "查看应用日志"
                    )

                    if (navigationState == NavigationState.ITEM_SEARCH) {
                        CompactControlButton(
                            text = "找到了",
                            onClick = onConfirmFound,
                            icon = Icons.Default.CheckCircle,
                            buttonColor = Success,
                            contentDescription = "确认找到目标物品"
                        )
                    }
                }
            }
        }
    }
}

/**
 * 运行状态信息栏 - 显示模型加载状态、相机状态、错误信息
 */
@Composable
private fun RuntimeStatusBar(
    modelsLoaded: Boolean,
    modelLoadStatus: String,
    cameraRunning: Boolean,
    errorMessage: String?,
    navigationState: NavigationState,
    onClearError: () -> Unit
) {
    val bgColor = when {
        errorMessage != null -> Color(0xCCD32F2F)
        !modelsLoaded -> Color(0xCCFF8F00)
        else -> Color(0x99000000)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // 状态指示器
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 模型状态
            val modelIcon = if (modelsLoaded) "●" else "○"
            val modelText = if (modelsLoaded) "模型就绪" else modelLoadStatus.ifEmpty { "模型加载中..." }
            Text(
                text = "$modelIcon $modelText",
                color = Color.White,
                fontSize = 11.sp
            )

            // 相机状态
            val camIcon = if (cameraRunning) "●" else "○"
            Text(
                text = "$camIcon 相机${if (cameraRunning) "运行" else "未启动"}",
                color = Color.White,
                fontSize = 11.sp
            )

            // 导航状态
            val navName = when (navigationState) {
                NavigationState.IDLE -> "待命"
                else -> "导航中"
            }
            Text(
                text = "◆ $navName",
                color = Color.White,
                fontSize = 11.sp
            )
        }

        // 错误信息
        errorMessage?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClearError() }
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚠ $msg",
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "✕",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )
            }
        }
    }
}
