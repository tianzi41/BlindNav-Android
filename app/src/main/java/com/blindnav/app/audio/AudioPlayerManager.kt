/**
 * AudioPlayerManager.kt - 音频播放管理器
 * 管理 SoundPool（短音效）和 MediaPlayer（长音频），
 * 从 assets 加载 wav 文件，根据 map.zh-CN.json 映射文本到音频文件
 */
package com.blindnav.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.blindnav.app.BlindNavApplication
import org.json.JSONObject
import java.io.IOException
import java.util.AbstractMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频播放管理器
 * 负责加载预录音频文件并根据文本关键词播放对应音频
 */
object AudioPlayerManager {

    private const val TAG = "AudioPlayerManager"
    private const val VOICE_MAP_FILE = "voice/map.zh-CN.json"
    private const val MAX_STREAMS = 4

    // SoundPool 用于播放短音效（< 1秒）
    private var soundPool: SoundPool? = null

    // MediaPlayer 用于播放较长的音频片段
    private var mediaPlayer: MediaPlayer? = null

    // 文本到音频文件路径的映射表
    private val textToAudioMap = ConcurrentHashMap<String, AudioEntry>()

    // SoundPool 中已加载的音效 ID 缓存
    private val soundIdCache = ConcurrentHashMap<String, Int>()

    // 是否已初始化
    private val initialized = AtomicBoolean(false)

    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    // 上次播放时间戳，用于节流
    private var lastPlayTimeMs = 0L
    private var lastPlayText = ""

    // 最小播放间隔（毫秒），防止相同语音连续播放
    private const val MIN_PLAY_INTERVAL_MS = 800L

    // 播放状态回调
    var onPlaybackStarted: ((String) -> Unit)? = null
    var onPlaybackCompleted: (() -> Unit)? = null

    // Android TTS 引擎（作为预录音频的回退方案）
    private var ttsEngine: TextToSpeech? = null
    private val ttsReady = AtomicBoolean(false)

    /**
     * 音频条目 - 包含文件路径和时长信息
     */
    data class AudioEntry(
        val filePath: String,
        val durationMs: Int,
        val files: List<String>
    )

    /**
     * 初始化音频系统
     * 加载音频映射表和预加载常用音效
     */
    fun initialize(context: Context) {
        if (initialized.getAndSet(true)) return

        Log.i(TAG, "开始初始化音频系统")

        // 创建 SoundPool
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(audioAttributes)
            .build()

        // 设置 SoundPool 加载完成回调
        soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                Log.d(TAG, "SoundPool 音效加载完成: id=$sampleId")
            } else {
                Log.w(TAG, "SoundPool 音效加载失败: id=$sampleId, status=$status")
            }
        }

        // 加载音频映射表
        loadVoiceMap(context)

        // 预加载常用短音效到 SoundPool
        preloadShortSounds(context)

        // 初始化 Android TTS 作为回退方案
        try {
            ttsEngine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsEngine?.language = Locale.CHINESE
                    ttsEngine?.setSpeechRate(1.2f)
                    ttsReady.set(true)
                    Log.i(TAG, "TTS 引擎初始化成功")
                } else {
                    Log.w(TAG, "TTS 引擎初始化失败: status=$status")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS 引擎创建失败", e)
        }

        Log.i(TAG, "音频系统初始化完成，映射条目数: ${textToAudioMap.size}")
    }

    /**
     * 从 assets/voice/map.zh-CN.json 加载文本到音频的映射表
     */
    private fun loadVoiceMap(context: Context) {
        try {
            val jsonString = context.assets.open(VOICE_MAP_FILE)
                .bufferedReader()
                .use { it.readText() }

            val jsonObject = JSONObject(jsonString)
            val keys = jsonObject.keys()

            while (keys.hasNext()) {
                val text = keys.next()
                val entry = jsonObject.getJSONObject(text)
                val filesArray = entry.getJSONArray("files")
                val durationMs = entry.optInt("duration_ms", 1000)

                val files = mutableListOf<String>()
                for (i in 0 until filesArray.length()) {
                    files.add(filesArray.getString(i))
                }

                // 构建完整的 assets 路径
                val primaryFile = files.firstOrNull() ?: continue
                val assetPath = resolveAssetPath(primaryFile)

                textToAudioMap[text] = AudioEntry(
                    filePath = assetPath,
                    durationMs = durationMs,
                    files = files
                )
            }

            Log.i(TAG, "音频映射表加载完成: ${textToAudioMap.size} 条")
        } catch (e: Exception) {
            Log.e(TAG, "加载音频映射表失败", e)
        }
    }

    /**
     * 解析音频文件的 assets 路径
     * 处理相对路径（如 "../music/xxx.wav"）
     */
    private fun resolveAssetPath(filePath: String): String {
        return when {
            // 处理 ../music/ 相对路径
            filePath.startsWith("../music/") -> {
                "music/${filePath.removePrefix("../music/")}"
            }
            // 处理 ../voice/ 相对路径
            filePath.startsWith("../voice/") -> {
                "voice/${filePath.removePrefix("../voice/")}"
            }
            // 默认在 voice 目录下
            else -> "voice/$filePath"
        }
    }

    /**
     * 预加载短时音频到 SoundPool（时长 < 2秒的音效）
     */
    private fun preloadShortSounds(context: Context) {
        var loadedCount = 0
        for ((text, entry) in textToAudioMap) {
            // 只预加载较短的音效
            if (entry.durationMs < 2000) {
                try {
                    val afd = context.assets.openFd(entry.filePath)
                    val soundId = soundPool?.load(afd, 1)
                    afd.close()
                    if (soundId != null) {
                        soundIdCache[text] = soundId
                        loadedCount++
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "无法预加载音效: $text -> ${entry.filePath}")
                }
            }
        }
        Log.i(TAG, "SoundPool 预加载完成: $loadedCount 个音效")
    }

    /**
     * 根据文本播放对应的音频
     * 自动进行模糊匹配和降级处理
     * 如果音频系统未初始化或找不到匹配音频，使用 Android TTS 作为回退
     */
    fun playText(text: String) {
        if (text.isBlank()) return

        val now = System.currentTimeMillis()

        // 节流：相同文本短时间内不重复播放
        if (text == lastPlayText && (now - lastPlayTimeMs) < MIN_PLAY_INTERVAL_MS) {
            return
        }

        // 检查音频系统是否已初始化
        if (!initialized.get()) {
            Log.w(TAG, "音频系统未初始化，使用 TTS 回退: $text")
            speakWithTts(text)
            lastPlayText = text
            lastPlayTimeMs = now
            return
        }

        // 尝试精确匹配
        val entry = textToAudioMap[text]
        if (entry != null) {
            playAudioEntry(text, entry)
            lastPlayText = text
            lastPlayTimeMs = now
            return
        }

        // 尝试模糊匹配：补全/去除句末标点
        val candidates = generateCandidates(text)
        for (candidate in candidates) {
            val matchedEntry = textToAudioMap[candidate]
            if (matchedEntry != null) {
                playAudioEntry(candidate, matchedEntry)
                lastPlayText = text
                lastPlayTimeMs = now
                return
            }
        }

        // 降级匹配：针对常见模式
        val fallbackEntry = findFallbackMatch(text)
        if (fallbackEntry != null) {
            playAudioEntry(fallbackEntry.first, fallbackEntry.second)
            lastPlayText = text
            lastPlayTimeMs = now
            return
        }

        // 所有匹配失败，使用 TTS 回退
        Log.d(TAG, "预录音频未匹配，使用 TTS: $text")
        speakWithTts(text)
        lastPlayText = text
        lastPlayTimeMs = now
    }

    /**
     * 生成候选文本列表（处理标点变体）
     */
    private fun generateCandidates(text: String): List<String> {
        val candidates = mutableListOf<String>()
        val trimmed = text.trim()

        // 尝试补全句号
        if (!trimmed.endsWith("。") && !trimmed.endsWith("！") && !trimmed.endsWith("？")) {
            candidates.add("$trimmed。")
        } else {
            // 尝试去掉句末标点
            val withoutPunct = trimmed.trimEnd('。', '！', '？', '.', '!', '?')
            if (withoutPunct != trimmed) {
                candidates.add(withoutPunct)
            }
        }

        return candidates
    }

    /**
     * 降级匹配：针对障碍物提示等常见模式
     */
    private fun findFallbackMatch(text: String): Pair<String, AudioEntry>? {
        // "前方有XX，注意避让" 降级到 "前方有障碍物，注意避让。"
        if (text.contains("前方有") && text.contains("注意避让")) {
            val fallback = "前方有障碍物，注意避让。"
            textToAudioMap[fallback]?.let { return Pair(fallback, it) }
        }

        // "前方有XX，停一下" 降级到 "前方有障碍物，停一下。"
        if (text.contains("前方有") && text.contains("停一下")) {
            val fallback = "前方有障碍物，停一下。"
            textToAudioMap[fallback]?.let { return Pair(fallback, it) }
        }

        // "左侧/右侧有XX" 降级到通用版本
        for (direction in listOf("左侧", "右侧")) {
            if (text.startsWith(direction) && text.contains("停一下")) {
                val fallback = "${direction}有障碍物，停一下。"
                textToAudioMap[fallback]?.let { return Pair(fallback, it) }
            }
        }

        return null
    }

    /**
     * 播放音频条目
     * 短音效使用 SoundPool，长音频使用 MediaPlayer
     */
    private fun playAudioEntry(text: String, entry: AudioEntry) {
        onPlaybackStarted?.invoke(text)

        if (entry.durationMs < 2000) {
            playWithSoundPool(text, entry)
        } else {
            playWithMediaPlayer(entry)
        }
    }

    /**
     * 使用 SoundPool 播放短音效
     */
    private fun playWithSoundPool(text: String, entry: AudioEntry) {
        val soundId = soundIdCache[text]
        if (soundId != null) {
            soundPool?.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
            // 模拟播放完成回调
            mainHandler.postDelayed({
                onPlaybackCompleted?.invoke()
            }, entry.durationMs.toLong())
        } else {
            // 如果 SoundPool 中没有，回退到 MediaPlayer
            playWithMediaPlayer(entry)
        }
    }

    /**
     * 使用 MediaPlayer 播放较长音频
     */
    private fun playWithMediaPlayer(entry: AudioEntry) {
        // 停止当前正在播放的音频
        stopCurrentPlayback()

        try {
            val context = BlindNavApplication.getContext()
            mediaPlayer = MediaPlayer().apply {
                val afd = context.assets.openFd(entry.filePath)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()

                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                setOnPreparedListener { mp ->
                    mp.start()
                }

                setOnCompletionListener {
                    onPlaybackCompleted?.invoke()
                    release()
                    mediaPlayer = null
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer 错误: what=$what, extra=$extra")
                    onPlaybackCompleted?.invoke()
                    release()
                    mediaPlayer = null
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建 MediaPlayer 失败", e)
            onPlaybackCompleted?.invoke()
        }
    }

    /**
     * 使用 Android TTS 朗读文本（作为预录音频的回退方案）
     */
    private fun speakWithTts(text: String) {
        if (!ttsReady.get() || ttsEngine == null) {
            Log.w(TAG, "TTS 未就绪，无法播放: $text")
            return
        }
        try {
            ttsEngine?.speak(text, TextToSpeech.QUEUE_ADD, null, "tts_${System.currentTimeMillis()}")
            onPlaybackStarted?.invoke(text)
            // TTS 完成回调（近似延迟）
            mainHandler.postDelayed({
                onPlaybackCompleted?.invoke()
            }, (text.length * 150L).coerceIn(500, 5000))
        } catch (e: Exception) {
            Log.e(TAG, "TTS 播放失败: $text", e)
        }
    }

    /**
     * 停止当前播放
     */
    fun stopCurrentPlayback() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "停止播放时出错", e)
        }
    }

    /**
     * 播放音乐目录下的音效（用于方向引导等）
     */
    fun playMusicSound(text: String) {
        val context = BlindNavApplication.getContext()
        try {
            val assetPath = "music/${text}.wav"
            // 先尝试 converted_ 前缀版本
            val convertedPath = "music/converted_${text}.wav"

            val pathToUse = try {
                context.assets.open(convertedPath).close()
                convertedPath
            } catch (e: IOException) {
                assetPath
            }

            stopCurrentPlayback()
            mediaPlayer = MediaPlayer().apply {
                val afd = context.assets.openFd(pathToUse)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()

                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                setOnPreparedListener { mp -> mp.start() }
                setOnCompletionListener {
                    onPlaybackCompleted?.invoke()
                    release()
                    mediaPlayer = null
                }
                setOnErrorListener { _, _, _ ->
                    release()
                    mediaPlayer = null
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放音乐音效失败: $text", e)
        }
    }

    /**
     * 释放所有资源
     */
    fun release() {
        stopCurrentPlayback()
        soundPool?.release()
        soundPool = null
        soundIdCache.clear()
        textToAudioMap.clear()
        ttsEngine?.stop()
        ttsEngine?.shutdown()
        ttsEngine = null
        ttsReady.set(false)
        initialized.set(false)
    }
}
