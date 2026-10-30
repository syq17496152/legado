package io.legado.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import io.legado.app.utils.LogUtils
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 内置离线 TTS 引擎（AD-01，注册为**系统 TTS 引擎**）。
 *
 * PoC 实证约束（docs/specs/builtin-offline-tts/poc-results.md）：
 * - Manifest 的 intent-filter **必须含 `category DEFAULT`**，否则 `getEngines()` 查不到本引擎
 * - `onGetLanguage()` 必须返回 `"zh"`（返回 `zho` 会让框架 `setLanguage` 得到 -2）
 * - 模型未就绪时返回 NOT_SUPPORTED，让系统回退到其他引擎（而非静默无声）
 *
 * 合成期临时前台化 + PARTIAL_WAKE_LOCK（AD-18）：请求进入进入前台，空闲 10s 退出。
 */
class BuiltinTtsService : TextToSpeechService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var synthJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false
    private val idleExit = Runnable {
        releaseWakeLock()
        exitForeground()
    }

    override fun onCreate() {
        super.onCreate()
        BuiltinTtsRuntime.init(this)
        createNotificationChannel()
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "builtin 引擎服务创建 pkg=$packageName", level = AppLog.Level.INFO
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        return Service.START_NOT_STICKY
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (!BuiltinTtsRuntime.isReady()) return TextToSpeech.LANG_NOT_SUPPORTED
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> {
        // PoC P6：必须返回 "zh"（"zho" 会被框架判 NOT_SUPPORTED）
        return arrayOf("zh", "CHN", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetVoices(): List<Voice> {
        BuiltinTtsRuntime.init(this)
        if (!BuiltinTtsRuntime.isReady()) return emptyList()
        val voices = BuiltinTtsRuntime.voices()
        if (voices.isEmpty()) return emptyList()
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "builtin onGetVoices n=${voices.size} sample=${voices.first().voiceName}",
            level = AppLog.Level.INFO
        )
        // C-1：全语言返回同一集合（英文系统语言下也必须非空）
        val locale = Locale.SIMPLIFIED_CHINESE
        return voices.map { voice ->
            Voice(
                /* name = */ voice.voiceName,
                locale,
                Voice.QUALITY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                setOf("builtin", "legado")
            )
        }
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?
    ): String? {
        return onGetVoices().firstOrNull()?.name
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        BuiltinTtsRuntime.init(this)
        val text = request.text.orEmpty()
        if (text.isBlank()) {
            callback.start(DEFAULT_SAMPLE_RATE, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }
        val voiceName = request.voiceName.orEmpty()
        // 框架下发的是百分比（setSpeechRate(1.5f) → 150），换算为倍率
        val rate = BuiltinTtsSynthesisMath.speedFromPercent(request.speechRate)
        val generation = BuiltinTtsRuntime.currentGeneration()
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "builtin 合成请求 len=${text.length} voice=$voiceName rate=$rate",
            level = AppLog.Level.INFO
        )

        enterForeground()
        acquireWakeLock()
        synthJob?.cancel()
        synthJob = scope.launch {
            val audio = BuiltinTtsRuntime.synthesizeToPcm(
                text = text.take(MAX_TEXT_CHARS),
                voiceName = voiceName,
                rate = rate,
                requestGeneration = generation
            )
            if (audio == null || audio.pcm.isEmpty()) {
                val reason = BuiltinTtsRuntime.stats().lastError.ifBlank { "离线语音包未就绪" }
                AppLog.put("内置TTS合成失败：$reason")
                runCatching { callback.error(TextToSpeech.ERROR) }
                scheduleIdleExit()
                return@launch
            }
            val started = runCatching {
                callback.start(
                    audio.sampleRate,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    CHANNEL_MONO
                )
            }.getOrDefault(TextToSpeech.ERROR)
            if (started != TextToSpeech.SUCCESS) {
                scheduleIdleExit()
                return@launch
            }
            // 按 ~200ms 分块回调，让系统播放器边收边播（降低首字延迟）
            val bytesPerChunk = BuiltinTtsSynthesisMath.chunkBytes(audio.sampleRate)
            var offset = 0
            while (offset < audio.pcm.size) {
                val length = minOf(bytesPerChunk, audio.pcm.size - offset)
                val result = runCatching { callback.audioAvailable(audio.pcm, offset, length) }
                    .getOrDefault(TextToSpeech.ERROR)
                if (result != TextToSpeech.SUCCESS) break
                offset += length
            }
            runCatching { callback.done() }
            AppLog.putDebugWithTag(
                AppLog.TAG_TTS_TRACE,
                "builtin 合成完成 sr=${audio.sampleRate} pcm=${audio.pcm.size} bytes",
                level = AppLog.Level.INFO
            )
            scheduleIdleExit()
        }
    }

    override fun onStop() {
        synthJob?.cancel()
        BuiltinTtsRuntime.bumpGeneration()
        scheduleIdleExit()
    }

    override fun onDestroy() {
        synthJob?.cancel()
        releaseWakeLock()
        exitForeground()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.builtin_tts_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun enterForeground() {
        if (inForeground) return
        inForeground = true
        runCatching {
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                Notification.Builder(this)
            }.apply {
                setContentTitle(getString(R.string.builtin_tts_channel_name))
                setContentText(getString(R.string.builtin_tts_foreground_text))
                setSmallIcon(R.drawable.ic_volume_up)
                setOngoing(true)
            }.build()
            startForeground(NOTIFICATION_ID, notification)
        }.onFailure {
            LogUtils.e(TAG, "前台化失败（降级为普通服务）：${it.message}")
            inForeground = false
        }
    }

    private fun exitForeground() {
        if (!inForeground) return
        inForeground = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun scheduleIdleExit() {
        idleHandler.removeCallbacks(idleExit)
        idleHandler.postDelayed(idleExit, IDLE_EXIT_MS)
    }

    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:builtin_tts").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.onFailure { LogUtils.e(TAG, "唤醒锁申请失败：${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private companion object {
        const val TAG = "BuiltinTtsService"
        const val CHANNEL_ID = "builtin_tts_synthesis"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_MONO = 1
        const val DEFAULT_SAMPLE_RATE = 24000
        const val MAX_TEXT_CHARS = 1200
        const val IDLE_EXIT_MS = 10_000L
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }
}

/** 合成参数换算（纯函数，可单测）：框架以百分比下发语速，回调按 ~200ms 分块 */
internal object BuiltinTtsSynthesisMath {

    fun speedFromPercent(percent: Int): Float =
        percent.takeIf { it > 0 }?.let { it / 100f }?.coerceIn(0.5f, 2.0f) ?: 1f

    fun chunkBytes(sampleRate: Int, chunkMillis: Int = 200): Int =
        (sampleRate * 2 * chunkMillis / 1000).coerceAtLeast(2048)
}
