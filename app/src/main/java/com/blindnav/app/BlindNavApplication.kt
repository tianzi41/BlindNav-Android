/**
 * BlindNavApplication.kt - 应用入口 Application 类
 * 负责全局初始化，包括音频系统预加载
 */
package com.blindnav.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.blindnav.app.audio.AudioPlayerManager

class BlindNavApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 音频初始化放到后台线程，避免阻塞主线程导致 ANR/闪退
        Thread {
            try {
                AudioPlayerManager.initialize(this)
            } catch (e: Exception) {
                Log.e("BlindNavApp", "音频系统初始化失败", e)
            }
        }.start()
    }

    companion object {
        @Volatile
        private var instance: BlindNavApplication? = null

        /** 获取 Application 实例 */
        fun getInstance(): BlindNavApplication {
            return instance ?: throw IllegalStateException(
                "BlindNavApplication 尚未初始化"
            )
        }

        /** 获取应用上下文 */
        fun getContext(): Context {
            return instance ?: throw IllegalStateException(
                "BlindNavApplication 尚未初始化"
            )
        }
    }
}
