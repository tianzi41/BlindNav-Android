/**
 * MainViewModel.kt - 主 ViewModel
 * 管理导航状态、相机帧处理、检测结果和 UI 状态
 */
package com.blindnav.app.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blindnav.app.audio.AudioPlayerManager
import com.blindnav.app.data.*
import com.blindnav.app.ml.YoloOnnxEngine
import com.blindnav.app.navigation.NavigationMaster
import com.blindnav.app.navigation.ItemSearchWorkflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主 ViewModel
 * 管理应用状态、导航逻辑和 UI 数据
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    // YOLO 推理引擎
    private val yoloEngine = YoloOnnxEngine(application.applicationContext)

    // 导航主控制器
    private val navigationMaster = NavigationMaster(yoloEngine)

    // 物品查找工作流
    private val itemSearchWorkflow = ItemSearchWorkflow(
        com.blindnav.app.detector.ItemDetector(yoloEngine)
    )

    // ========== UI 状态 ==========

    // 当前界面模式
    private val _screenMode = MutableStateFlow(ScreenMode.MAIN)
    val screenMode: StateFlow<ScreenMode> = _screenMode.asStateFlow()

    // 导航状态
    private val _navigationState = MutableStateFlow(NavigationState.IDLE)
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    // 状态描述文本
    private val _statusText = MutableStateFlow("就绪 - 请选择导航模式")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    // 引导文本
    private val _guidanceText = MutableStateFlow("")
    val guidanceText: StateFlow<String> = _guidanceText.asStateFlow()

    // 检测结果（用于叠加绘制）
    private val _detections = MutableStateFlow<List<DetectionResult>>(emptyList())
    val detections: StateFlow<List<DetectionResult>> = _detections.asStateFlow()

    // 当前引导方向
    private val _guidanceDirection = MutableStateFlow(GuidanceDirection.NONE)
    val guidanceDirection: StateFlow<GuidanceDirection> = _guidanceDirection.asStateFlow()

    // 物品查找目标名称
    private val _itemSearchTarget = MutableStateFlow("")
    val itemSearchTarget: StateFlow<String> = _itemSearchTarget.asStateFlow()

    // 模型加载状态
    private val _modelsLoaded = MutableStateFlow(false)
    val modelsLoaded: StateFlow<Boolean> = _modelsLoaded.asStateFlow()

    // 模型加载状态文本
    private val _modelLoadStatus = MutableStateFlow("加载中...")
    val modelLoadStatus: StateFlow<String> = _modelLoadStatus.asStateFlow()

    // 摄像头是否翻转
    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    // 相机是否正在运行
    private val _cameraRunning = MutableStateFlow(false)
    val cameraRunning: StateFlow<Boolean> = _cameraRunning.asStateFlow()

    // 帧处理锁：防止协程堆积导致处理旧帧（状态锁死的根因）
    // 当正在处理时跳过新帧，CameraX 的 STRATEGY_KEEP_ONLY_LATEST 保证下次回调拿到最新帧
    @Volatile
    private var isProcessing = false

    @Volatile
    private var isProcessingItem = false

    // 错误消息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        // 会话标记，方便区分日志
        Log.i(TAG, "========== App 启动 (PID=${android.os.Process.myPid()}) ==========")

        // 初始化音频系统
        AudioPlayerManager.onPlaybackStarted = { text ->
            Log.d(TAG, "音频播放开始: $text")
        }
        AudioPlayerManager.onPlaybackCompleted = {
            Log.d(TAG, "音频播放完成")
        }

        // 加载模型（在后台线程）
        loadModels()
    }

    /**
     * 加载 ONNX 模型（后台线程，不阻塞 UI）
     */
    private fun loadModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始加载模型...")
                withContext(Dispatchers.Main) { _modelLoadStatus.value = "复制模型文件..." }

                // 先加载检测模型（较小，108MB）
                val detLoaded = yoloEngine.loadDetectModel("yoloe_detect.onnx")

                withContext(Dispatchers.Main) { _modelLoadStatus.value = "加载分割模型..." }

                // 再加载分割模型（较大，274MB）
                val segLoaded = yoloEngine.loadSegModel("yolo_seg.onnx")

                withContext(Dispatchers.Main) { _modelLoadStatus.value = "加载红绿灯模型..." }

                // 加载红绿灯专用模型
                val tlLoaded = yoloEngine.loadTrafficModel("trafficlight.onnx")

                withContext(Dispatchers.Main) { _modelLoadStatus.value = "加载商品模型..." }

                // 加载商品识别模型
                val shopLoaded = yoloEngine.loadShoppingModel("shopping.onnx")

                withContext(Dispatchers.Main) {
                    _modelsLoaded.value = segLoaded || detLoaded
                    if (segLoaded && detLoaded && tlLoaded) {
                        _modelLoadStatus.value = "全部就绪"
                        Log.i(TAG, "所有模型加载成功（含红绿灯模型）")
                    } else if (segLoaded && detLoaded) {
                        _modelLoadStatus.value = "基础就绪（红绿灯模型未加载）"
                        Log.i(TAG, "基础模型加载成功，红绿灯模型未加载")
                    } else if (segLoaded) {
                        _modelLoadStatus.value = "仅分割模型就绪"
                    } else if (detLoaded) {
                        _modelLoadStatus.value = "仅检测模型就绪"
                    } else {
                        _modelLoadStatus.value = "模型加载失败"
                        _errorMessage.value = "模型加载失败，导航功能不可用"
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "模型加载 OOM", e)
                withContext(Dispatchers.Main) {
                    _modelLoadStatus.value = "内存不足"
                    _errorMessage.value = "内存不足，请关闭其他应用重试"
                }
            } catch (e: Exception) {
                Log.e(TAG, "模型加载异常", e)
                withContext(Dispatchers.Main) {
                    _modelLoadStatus.value = "加载失败"
                    _errorMessage.value = "模型加载失败: ${e.message}"
                }
            }
        }
    }

    /**
     * 处理相机帧
     * 由 CameraManager 的帧回调调用
     *
     * 帧丢弃机制：如果上一帧仍在处理中，直接丢弃当前帧并回收 bitmap。
     * CameraX 使用 STRATEGY_KEEP_ONLY_LATEST，下一次回调会自动拿到最新画面，
     * 从而确保推理的始终是近期帧，解决"状态锁死"问题。
     */
    fun processFrame(bitmap: Bitmap) {
        if (isProcessing) {
            // 上一帧还在处理中，丢弃当前帧
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        isProcessing = true

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = navigationMaster.processFrame(bitmap)

                Log.d(TAG, "processFrame: state=${result.state}, guidance=${result.guidance}, detections=${result.detections.size}")

                withContext(Dispatchers.Main) {
                    _navigationState.value = result.state
                    _statusText.value = result.statusText

                    // 更新检测结果用于界面叠加绘制
                    _detections.value = result.detections

                    if (result.guidance.isNotEmpty()) {
                        _guidanceText.value = result.guidance
                        AudioPlayerManager.playText(result.guidance)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "帧处理异常", e)
            } finally {
                isProcessing = false
                // 回收相机帧位图，避免内存泄漏
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    /**
     * 处理物品查找帧（同样使用帧丢弃机制）
     */
    fun processItemSearchFrame(bitmap: Bitmap) {
        if (isProcessingItem) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        isProcessingItem = true

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = itemSearchWorkflow.processFrame(bitmap)

                withContext(Dispatchers.Main) {
                    _statusText.value = result.statusText

                    if (result.guidance.isNotEmpty()) {
                        _guidanceText.value = result.guidance
                        AudioPlayerManager.playText(result.guidance)
                    }

                    // 更新检测结果
                    result.searchResult?.detection?.let {
                        _detections.value = listOfNotNull(it)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "物品查找帧处理异常", e)
            } finally {
                isProcessingItem = false
                // 回收相机帧位图，避免内存泄漏
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    // ========== 导航控制命令 ==========

    /**
     * 启动盲道导航
     */
    fun startBlindPathNavigation() {
        if (!_modelsLoaded.value) {
            _errorMessage.value = "模型尚未加载完成，请稍候"
            return
        }
        navigationMaster.startBlindPathNavigation()
        _navigationState.value = NavigationState.BLIND_NAV
        _statusText.value = "盲道导航中"
        _guidanceText.value = "切换到盲道导航。"
        AudioPlayerManager.playText("切换到盲道导航。")
    }

    /**
     * 启动过马路模式
     */
    fun startCrossStreet() {
        if (!_modelsLoaded.value) {
            _errorMessage.value = "模型尚未加载完成，请稍候"
            return
        }
        navigationMaster.startCrossStreet()
        _navigationState.value = NavigationState.SEEKING_CROSSWALK
        _statusText.value = "过马路模式"
        _guidanceText.value = "过马路模式已启动。"
        AudioPlayerManager.playText("过马路模式已启动。")
    }

    /**
     * 启动物品查找模式
     */
    fun startItemSearch(targetName: String) {
        if (targetName.isBlank()) return
        if (!_modelsLoaded.value) {
            _errorMessage.value = "模型尚未加载完成，请稍候"
            return
        }
        Log.i(TAG, "startItemSearch: $targetName")

        _itemSearchTarget.value = targetName
        _screenMode.value = ScreenMode.MAIN
        navigationMaster.startItemSearch()
        itemSearchWorkflow.startSearch(targetName)

        _navigationState.value = NavigationState.ITEM_SEARCH
        _statusText.value = "物品查找 - $targetName"
        _guidanceText.value = "正在搜索 $targetName..."
        AudioPlayerManager.playText("正在搜索 $targetName...")
    }

    /**
     * 停止物品查找
     */
    fun stopItemSearch() {
        itemSearchWorkflow.stopSearch()
        navigationMaster.stopItemSearch(restoreNav = true)
        _itemSearchTarget.value = ""
    }

    /**
     * 停止导航
     */
    fun stopNavigation() {
        Log.i(TAG, "stopNavigation")
        navigationMaster.stopNavigation()
        itemSearchWorkflow.stopSearch()

        _navigationState.value = NavigationState.IDLE
        _statusText.value = "就绪 - 请选择导航模式"
        _guidanceText.value = "导航已被取消。"
        _detections.value = emptyList()
        _itemSearchTarget.value = ""

        AudioPlayerManager.playText("导航已被取消。")
        AudioPlayerManager.stopCurrentPlayback()
    }

    /**
     * 确认找到物品
     */
    fun confirmItemFound() {
        itemSearchWorkflow.confirmFound()
        _guidanceText.value = "找到啦！"
        AudioPlayerManager.playMusicSound("找到啦")
    }

    /**
     * 切换前后摄像头
     */
    fun switchCamera() {
        _isFrontCamera.value = !_isFrontCamera.value
    }

    /**
     * 设置相机运行状态
     */
    fun setCameraRunning(running: Boolean) {
        _cameraRunning.value = running
    }

    /**
     * 显示物品查找输入界面
     */
    fun showItemSearchInput() {
        _screenMode.value = ScreenMode.ITEM_SEARCH_INPUT
    }

    /**
     * 返回主界面
     */
    fun showMainScreen() {
        _screenMode.value = ScreenMode.MAIN
    }

    /**
     * 显示日志查看界面
     */
    fun showLogViewer() {
        _screenMode.value = ScreenMode.LOG_VIEWER
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        yoloEngine.release()
        AudioPlayerManager.release()
    }
}
