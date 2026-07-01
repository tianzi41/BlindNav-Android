/**
 * MainActivity.kt - 主 Activity
 * 负责权限申请和 Jetpack Compose 入口
 */
package com.blindnav.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.blindnav.app.ui.screens.MainScreen
import com.blindnav.app.ui.theme.BlindNavTheme

class MainActivity : ComponentActivity() {

    // 权限请求回调
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            // 相机权限已授予，正常启动
            setupUI()
        } else {
            // 相机权限被拒绝，显示提示
            Toast.makeText(
                this,
                "相机权限是导航功能必需的，请在设置中授予",
                Toast.LENGTH_LONG
            ).show()
            // 仍然启动 UI，但会显示权限提示
            setupUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 保持屏幕常亮，导航期间不自动息屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        checkAndRequestPermissions()
    }

    /**
     * 检查并请求必要的运行时权限
     */
    private fun checkAndRequestPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        val allGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                    PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            setupUI()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    /**
     * 设置 Jetpack Compose UI
     */
    private fun setupUI() {
        setContent {
            BlindNavTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
