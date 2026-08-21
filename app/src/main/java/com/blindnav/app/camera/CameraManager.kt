/**
 * CameraManager.kt - 相机管理器
 * 封装 CameraX，获取实时帧用于推理
 */
package com.blindnav.app.camera

import android.content.Context
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Rational
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ViewPort
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 相机管理器
 * 负责 CameraX 初始化、预览和帧分析
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraManager"
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480
    }

    // 相机提供者
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    // 图像分析执行器
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 是否使用前置摄像头
    private var useFrontCamera = false

    // 是否使用广角摄像头（false=主摄）
    private var useUltraWide = false
    // 是否检测到可用的广角摄像头
    private var hasUltraWide = false
    // 广角摄像头的 CameraInfo（用于构建 Selector）
    private var ultraWideCameraInfo: CameraInfo? = null
    // 当前绑定的 PreviewView（用于切换时重启）
    private var currentPreviewView: PreviewView? = null

    // 帧回调
    private var onFrameCallback: ((ImageProxy) -> Unit)? = null

    // 相机是否正在运行
    private var isRunning = false

    // 待应用的曝光补偿值（相机就绪后延迟应用）
    private var pendingExposureCompensation: Int? = null

    /**
     * 设置帧回调
     * 每当有新帧可用于分析时调用
     */
    fun setFrameCallback(callback: (ImageProxy) -> Unit) {
        onFrameCallback = callback
    }

    /**
     * 启动相机预览和分析
     * @param previewView 用于显示预览的 PreviewView
     */
    fun startCamera(previewView: PreviewView) {
        currentPreviewView = previewView
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 绑定相机用例（预览 + 分析）
     * 使用 ViewPort API 确保预览和分析共享相同的视野范围，
     * 解决 Preview 和 ImageAnalysis 分辨率/宽高比不同导致的检测坐标偏移问题
     */
    private fun bindCameraUseCases(previewView: PreviewView) {
        val provider = cameraProvider ?: return

        // 选择前置或后置摄像头
        val cameraSelector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            buildBackCameraSelector()
        }

        // 预览用例（不指定分辨率，让 CameraX 自动匹配显示）
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // 图像分析用例
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor) { imageProxy ->
                    onFrameCallback?.invoke(imageProxy)
                }
            }

        try {
            // 解绑所有用例
            provider.unbindAll()

            // 使用 ViewPort 确保预览和分析的视野范围一致
            // 关键修复：当 Preview 选择 16:9 分辨率而 ImageAnalysis 使用 4:3 时，
            // 两者视野不同导致检测坐标与预览画面不匹配。ViewPort 统一裁剪到相同区域。
            val viewWidth = previewView.width.coerceAtLeast(1)
            val viewHeight = previewView.height.coerceAtLeast(1)
            val viewPort = ViewPort.Builder(
                Rational(viewWidth, viewHeight),
                previewView.display?.rotation ?: 0
            ).build()

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageAnalysis)
                .setViewPort(viewPort)
                .build()

            camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)

            isRunning = true
            Log.i(TAG, "相机启动成功（ViewPort模式），使用${if (useFrontCamera) "前置" else "后置"}摄像头，视野=${viewWidth}x${viewHeight}")
            applyPendingExposureCompensation()

        } catch (e: Exception) {
            Log.e(TAG, "ViewPort 绑定失败，回退到普通绑定", e)
            // 回退：不使用 ViewPort
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                isRunning = true
                Log.i(TAG, "相机启动成功（回退模式），使用${if (useFrontCamera) "前置" else "后置"}摄像头")
                applyPendingExposureCompensation()
            } catch (e2: Exception) {
                Log.e(TAG, "相机绑定失败", e2)
                isRunning = false
            }
        }
    }

    /**
     * 切换前后摄像头
     */
    fun switchCamera(previewView: PreviewView) {
        useFrontCamera = !useFrontCamera
        startCamera(previewView)
    }

    /**
     * 切换广角/主摄（仅后置生效）
     * 需要 Provider 已初始化，否则静默保存设置下次启动生效
     */
    fun setUltraWide(enabled: Boolean) {
        if (useUltraWide == enabled) return
        useUltraWide = enabled
        Log.i(TAG, "广角模式: ${if (enabled) "开启" else "关闭"} (可用=$hasUltraWide)")
        // 如果相机已启动，立即重启以应用切换
        currentPreviewView?.let { startCamera(it) }
    }

    /**
     * 是否检测到可切换的广角摄像头
     */
    fun isUltraWideAvailable(): Boolean = hasUltraWide

    /**
     * 当前是否使用广角
     */
    fun isUsingUltraWide(): Boolean = useUltraWide

    /**
     * 构建后置摄像头选择器
     * 扫描所有后置摄像头，按焦距筛选广角镜头，切换到主摄时回退默认
     */
    private fun buildBackCameraSelector(): CameraSelector {
        val provider = cameraProvider ?: return CameraSelector.DEFAULT_BACK_CAMERA

        try {
            val backCameras = provider.availableCameraInfos.filter {
                Camera2CameraInfo.from(it).getCameraCharacteristic(
                    CameraCharacteristics.LENS_FACING
                ) == CameraCharacteristics.LENS_FACING_BACK
            }

            // 扫描广角摄像头：焦距最短的非主摄
            var minFocal = Float.MAX_VALUE
            var secondMinFocal = Float.MAX_VALUE
            var wideInfo: CameraInfo? = null

            for (info in backCameras) {
                val focalLengths = Camera2CameraInfo.from(info).getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                ) ?: continue
                val fl = focalLengths.firstOrNull() ?: continue
                if (fl < minFocal) {
                    secondMinFocal = minFocal
                    minFocal = fl
                    wideInfo = info
                } else if (fl < secondMinFocal) {
                    secondMinFocal = fl
                }
            }

            // 判断是否有真正的广角：焦距差 > 40% 才算独立广角
            if (wideInfo != null && secondMinFocal < Float.MAX_VALUE &&
                minFocal / secondMinFocal < 0.6f) {
                hasUltraWide = true
                ultraWideCameraInfo = wideInfo!!
                Log.i(TAG, "检测到广角摄像头: focal=${minFocal}mm vs 主摄=${secondMinFocal}mm")
            } else {
                hasUltraWide = false
                ultraWideCameraInfo = null
                Log.i(TAG, "未检测到独立广角摄像头 (minFocal=$minFocal, second=$secondMinFocal)")
            }

            if (useUltraWide && hasUltraWide && ultraWideCameraInfo != null) {
                return CameraSelector.Builder()
                    .addCameraFilter { cameras ->
                        cameras.filter { it == ultraWideCameraInfo }
                    }
                    .build()
            }
        } catch (e: Exception) {
            Log.w(TAG, "扫描广角摄像头失败: ${e.message}")
        }

        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    /**
     * 停止相机
     */
    fun stopCamera() {
        cameraProvider?.unbindAll()
        isRunning = false
        currentPreviewView = null
        Log.i(TAG, "相机已停止")
    }

    /**
     * 释放资源
     */
    fun release() {
        stopCamera()
        analysisExecutor.shutdown()
    }

    /**
     * 获取当前是否使用前置摄像头
     */
    fun isUsingFrontCamera(): Boolean = useFrontCamera

    /**
     * 获取相机是否正在运行
     */
    fun isCameraRunning(): Boolean = isRunning

    /**
     * 设置曝光补偿
     * @param enabled true = 锁 EV -0.5 (补偿值 -2)，false = 恢复自动
     * 自动曝光基础上偏移，不会完全锁死，保留光照自适应
     */
    fun setExposureCompensation(enabled: Boolean) {
        val cam = camera ?: run {
            pendingExposureCompensation = if (enabled) -3 else 0
            Log.w(TAG, "\uD83D\uDFE1 曝光补偿: camera 未就绪，暂存待应用值=$pendingExposureCompensation")
            return
        }
        val exposureState = cam.cameraInfo.exposureState
        val range = exposureState.exposureCompensationRange
        Log.i(TAG, "\uD83D\uDCF7 曝光补偿范围: [${range.lower}, ${range.upper}]")

        val target = if (enabled) (-3).coerceIn(range.lower, range.upper) else 0
        pendingExposureCompensation = target
        applyExposureCompensationInternal(cam, target)
    }

    private fun applyExposureCompensationInternal(cam: Camera, target: Int) {
        try {
            // CameraX bind 后 HAL 需要一点时间就绪，延迟 300ms
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    cam.cameraControl.setExposureCompensationIndex(target)
                    val actual = cam.cameraInfo.exposureState.exposureCompensationIndex
                    Log.i(TAG, if (target != 0) "\uD83D\uDD12 曝光补偿: 目标=$target 实际=$actual" else "\uD83D\uDD13 曝光补偿: 已恢复 0")
                } catch (e: Exception) {
                    Log.w(TAG, "\uD83D\uDFE1 延迟应用曝光补偿失败，将在相机就绪后重试: ${e.message}")
                }
            }, 300)
        } catch (e: Exception) {
            Log.w(TAG, "\u274C 曝光补偿失败: ${e.message}")
        }
    }

    private fun applyPendingExposureCompensation() {
        val target = pendingExposureCompensation ?: return
        val cam = camera ?: return
        applyExposureCompensationInternal(cam, target)
    }

    fun isExposureCompensationActive(): Boolean {
        return try {
            camera?.cameraInfo?.exposureState?.exposureCompensationIndex?.let { it != 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
