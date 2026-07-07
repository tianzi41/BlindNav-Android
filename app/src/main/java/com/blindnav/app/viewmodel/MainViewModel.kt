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
import com.blindnav.app.navigation.CrossStreetManager
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

    // 导航主控制器（盲道导航专用）
    private val navigationMaster = NavigationMaster(yoloEngine)

    // 过马路控制器（独立于盲道导航）
    private val crossStreetManager = CrossStreetManager(yoloEngine)

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

    // 模型加载状态
    private val _modelsLoaded = MutableStateFlow(false)
    val modelsLoaded: StateFlow<Boolean> = _modelsLoaded.asStateFlow()

    // 模型加载状态文本
    private val _modelLoadStatus = MutableStateFlow("加载中...")
    val modelLoadStatus: StateFlow<String> = _modelLoadStatus.asStateFlow()

    // 相机是否正在运行
    private val _cameraRunning = MutableStateFlow(false)
    val cameraRunning: StateFlow<Boolean> = _cameraRunning.asStateFlow()

    // 曝光补偿是否锁定
    private val _exposureCompensationLocked = MutableStateFlow(false)
    val exposureCompensationLocked: StateFlow<Boolean> = _exposureCompensationLocked.asStateFlow()

    // 帧处理锁：防止协程堆积导致处理旧帧（状态锁死的根因）
    // 当正在处理时跳过新帧，CameraX 的 STRATEGY_KEEP_ONLY_LATEST 保证下次回调拿到最新帧
    @Volatile
    private var isProcessing = false

    @Volatile
    private var isProcessingCross = false

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
     * 启动时只加载分割模型（盲道/斑马线），其他模型按需加载
     * 将启动加载时间从 ~60s 降低到 ~15s
     */
    private fun loadModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始加载分割模型（懒加载模式）...")
                withContext(Dispatchers.Main) { _modelLoadStatus.value = "加载分割模型..." }

                val segLoaded = yoloEngine.loadSegModel("yolo_seg.onnx")

                // NNAPI warmup：用一张空白图跑一次推理，强制 NPU 编译模型图
                // 把 2-10 秒的首次推理延迟移到加载阶段（用户已看到"加载中"提示）
                if (segLoaded) {
                    withContext(Dispatchers.Main) { _modelLoadStatus.value = "初始化硬件加速..." }
                    val warmupStart = System.currentTimeMillis()
                    try {
                        val warmupBitmap = android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.ARGB_8888)
                        yoloEngine.runSegmentation(warmupBitmap)
                        warmupBitmap.recycle()
                        val warmupElapsed = System.currentTimeMillis() - warmupStart
                        Log.w(TAG, "=== NNAPI warmup 推理完成 === 耗时=${warmupElapsed}ms (${if (warmupElapsed < 1000) "NNAPI生效" else "可能跑在CPU上"})")
                    } catch (e: Exception) {
                        val warmupElapsed = System.currentTimeMillis() - warmupStart
                        Log.e(TAG, "!!! NNAPI warmup 推理失败 !!! 耗时=${warmupElapsed}ms, 错误: ${e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    _modelsLoaded.value = segLoaded
                    if (segLoaded) {
                        _modelLoadStatus.value = "分割模型就绪"
                        Log.i(TAG, "分割模型加载成功（其他模型按需加载）")
                    } else {
                        _modelLoadStatus.value = "模型加载失败"
                        _errorMessage.value = "分割模型加载失败，导航功能不可用"
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
     * 按需加载红绿灯模型（进入过马路模式时调用）
     */
    private fun ensureTrafficModelLoaded() {
        if (yoloEngine.isClaModelLoaded()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { _modelLoadStatus.value = "加载红绿灯模型..." }
                val loaded = yoloEngine.loadClaModel("pedestrian_light_classifier.onnx")
                withContext(Dispatchers.Main) {
                    _modelLoadStatus.value = if (loaded) "红绿灯模型就绪" else "红绿灯模型加载失败"
                }
            } catch (e: Exception) {
                Log.e(TAG, "红绿灯模型加载失败", e)
                withContext(Dispatchers.Main) { _modelLoadStatus.value = "红绿灯模型加载失败" }
            }
        }
    }

    /**
     * 按需加载 LYTNetV2 红绿灯模型
     */
    private fun ensureLytModelLoaded() {
        if (yoloEngine.isLytModelLoaded()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { _modelLoadStatus.value = "加载LYTNetV2模型..." }
                val loaded = yoloEngine.loadLytModel("pedestrian_light_lytnet.onnx")
                withContext(Dispatchers.Main) {
                    _modelLoadStatus.value = if (loaded) "LYTNetV2模型就绪" else "LYTNetV2加载失败"
                }
            } catch (e: Exception) {
                Log.e(TAG, "LYTNetV2模型加载失败", e)
                withContext(Dispatchers.Main) { _modelLoadStatus.value = "LYTNetV2加载失败" }
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
        // 过马路模式激活时，路由到 CrossStreetManager
        if (crossStreetManager.isActive) {
            processCrossStreetFrame(bitmap)
            return
        }

        if (isProcessing) {
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
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    /**
     * 处理过马路帧（独立于盲道导航，使用独立的帧丢弃锁）
     */
    private fun processCrossStreetFrame(bitmap: Bitmap) {
        if (isProcessingCross) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        isProcessingCross = true

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = crossStreetManager.processFrame(bitmap)

                Log.d(TAG, "processCrossStreetFrame: state=${result.state}, guidance=${result.guidance}")

                withContext(Dispatchers.Main) {
                    _navigationState.value = result.state
                    _statusText.value = result.statusText
                    _detections.value = result.detections

                    if (result.guidance.isNotEmpty()) {
                        _guidanceText.value = result.guidance
                        AudioPlayerManager.playText(result.guidance)
                    }

                    // 过马路结束后自动回到 IDLE
                    if (!crossStreetManager.isActive) {
                        _navigationState.value = NavigationState.IDLE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "过马路帧处理异常", e)
            } finally {
                isProcessingCross = false
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
     * 启动过马路模式（独立于盲道导航）
     */
    fun startCrossStreet() {
        if (!_modelsLoaded.value) {
            _errorMessage.value = "模型尚未加载完成，请稍候"
            return
        }
        // 先停止盲道导航（如果正在进行）
        if (navigationMaster.currentState != NavigationState.IDLE) {
            navigationMaster.stopNavigation()
        }
        ensureTrafficModelLoaded()
        crossStreetManager.start()
        _navigationState.value = NavigationState.SEEKING_CROSSWALK
        _statusText.value = "过马路模式"
        _guidanceText.value = "过马路模式已启动。"
        AudioPlayerManager.playText("过马路模式已启动。")
    }

    /**
     * 启动斑马线测试模式
     */
    fun startCrosswalkTest() {
        if (!_modelsLoaded.value) {
            _errorMessage.value = "模型尚未加载完成，请稍候"
            return
        }
        if (crossStreetManager.isActive) crossStreetManager.stop()
        navigationMaster.startCrosswalkTest()
        _navigationState.value = NavigationState.CROSSWALK_TEST
        _statusText.value = "斑马线测试"
        AudioPlayerManager.playText("斑马线测试已启动。")
    }

    /**
     * 启动红绿灯测试模式
     */
    fun startTrafficLightTest() {
        if (!_modelsLoaded.value) {
            _errorMessage.value = "模型尚未加载完成，请稍候"
            return
        }
        if (crossStreetManager.isActive) crossStreetManager.stop()
        ensureTrafficModelLoaded()
        navigationMaster.startTrafficLightTest()
        _navigationState.value = NavigationState.TRAFFIC_LIGHT_TEST
        _statusText.value = "红绿灯测试"
        AudioPlayerManager.playText("红绿灯测试已启动。")
    }

    /**
     * 启动红绿灯测试模式 (LYTNetV2)
     */
    fun startLytTrafficLightTest() {
        if (!_modelsLoaded.value) {
            _errorMessage.value = "模型尚未加载完成，请稍候"
            return
        }
        if (crossStreetManager.isActive) crossStreetManager.stop()
        ensureLytModelLoaded()
        navigationMaster.startLytTrafficLightTest()
        _navigationState.value = NavigationState.LYT_TRAFFIC_LIGHT_TEST
        _statusText.value = "LYTNetV2红绿灯测试"
    }

    /**
     * 停止所有导航（盲道 + 过马路）
     */
    fun stopNavigation() {
        Log.i(TAG, "stopNavigation")
        navigationMaster.stopNavigation()
        crossStreetManager.stop()

        _navigationState.value = NavigationState.IDLE
        _statusText.value = "就绪 - 请选择导航模式"
        _guidanceText.value = "导航已被取消。"
        _detections.value = emptyList()

        AudioPlayerManager.playText("导航已被取消。")
        AudioPlayerManager.stopCurrentPlayback()
    }

    /**
     * 设置相机运行状态
     */
    fun setCameraRunning(running: Boolean) {
        _cameraRunning.value = running
    }

    /**
     * 返回主界面
     */
    fun showMainScreen() {
        _screenMode.value = ScreenMode.MAIN
    }

    fun showSettings() {
        _screenMode.value = ScreenMode.SETTINGS
    }

    fun toggleExposureCompensation(cameraManager: com.blindnav.app.camera.CameraManager) {
        val newState = !_exposureCompensationLocked.value
        _exposureCompensationLocked.value = newState
        cameraManager.setExposureCompensation(newState)
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
