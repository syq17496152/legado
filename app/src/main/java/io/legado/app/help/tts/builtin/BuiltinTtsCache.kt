package io.legado.app.help.tts.builtin

import java.io.File
import java.util.Locale

/**
 * 引擎缓存键（C-4 单源函数）：播放（引擎内）与预合成（App 侧）必须共用。
 * 与 http 通道的 `TtsCacheKeys` 是**两套命名空间**（AD-04，有意隔离，防止跨语义碰撞）。
 */
object BuiltinCacheKeys {

    /** key = md5("bt|<profile>|<voiceName>|<rateKey>|<gainKey>|<normalizedText>") */
    fun key(
        profile: String,
        voiceName: String,
        rate: Float,
        text: String,
        gain: Float = 1.0f
    ): String {
        return BuiltinHash.md5Hex(
            "bt|$profile|$voiceName|${rateKey(rate)}|${gainKey(gain)}|${normalize(text)}"
        )
    }

    /** 语速键保留两位（0.50~2.00），避免浮点尾差导致同义不同键 */
    fun rateKey(rate: Float): String = String.format(Locale.US, "%.2f", rate.coerceIn(0.1f, 4.0f))

    /** 音色增益键保留两位（T8.1：改增益必须换缓存键，否则旧音频以旧音量播放） */
    fun gainKey(gain: Float): String = String.format(Locale.US, "%.2f", gain.coerceIn(0.1f, 10.0f))

    /** 空白归一（不含章节信息：跨书复用是特性） */
    fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}

/** 缓存产物：统一 16bit 单声道 PCM + 采样率 */
class CachedAudio(
    val sampleRate: Int,
    val pcm: ByteArray
) {
    val bytes: Int get() = pcm.size
}

/**
 * 两级缓存（AD-04）：
 * - 磁盘 `cacheDir/builtin_tts/<md5>.wav`（WAV 头自描述采样率，便于导出/试听直接播放）
 * - 内存 LRU ≤32MB（重复段加速）
 * - 写盘走 .part + rename（原子）；超水位按 lastModified 升序 LRU 淘汰
 */
class BuiltinTtsCache(
    private val dir: File,
    private val maxDiskBytes: Long = DEFAULT_MAX_DISK_BYTES,
    private val maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES
) {

    companion object {
        const val DEFAULT_MAX_DISK_BYTES = 2L * 1024 * 1024 * 1024
        const val DEFAULT_MAX_MEMORY_BYTES = 32L * 1024 * 1024
    }

    /** 磁盘水位上限（T3.4：管理页显示「用量 / 上限」；超限时按 LRU 淘汰） */
    fun diskLimitBytes(): Long = maxDiskBytes

    private val memory = LinkedHashMap<String, CachedAudio>(16, 0.75f, true)
    private var memoryBytes = 0L

    @Synchronized
    fun get(key: String): CachedAudio? {
        memory[key]?.let { return it }
        val file = fileOf(key)
        if (!file.isFile || file.length() <= 0) return null
        val audio = runCatching { PcmAudioUtils.readWav(file) }.getOrNull() ?: return null
        // 命中同步磁盘时间（LRU 依据）
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        putMemory(key, audio)
        return audio
    }

    @Synchronized
    fun contains(key: String): Boolean {
        if (memory.containsKey(key)) return true
        val file = fileOf(key)
        return file.isFile && file.length() > 0
    }

    @Synchronized
    fun put(key: String, audio: CachedAudio) {
        putMemory(key, audio)
        runCatching {
            dir.mkdirs()
            val target = fileOf(key)
            val part = File(dir, "$key.part")
            part.writeBytes(PcmAudioUtils.wavBytes(audio.pcm, audio.sampleRate))
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                target.writeBytes(part.readBytes())
                part.delete()
            }
            evictDiskIfNeeded()
        }
    }

    @Synchronized
    fun clear() {
        memory.clear()
        memoryBytes = 0
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    @Synchronized
    fun sizeBytes(): Long {
        return runCatching {
            dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L }
        }.getOrNull() ?: 0L
    }

    @Synchronized
    fun memoryBytes(): Long = memoryBytes

    @Synchronized
    fun remove(prefix: String) {
        val keys = memory.keys.filter { it.startsWith(prefix) }
        keys.forEach {
            memoryBytes -= (memory.remove(it)?.bytes ?: 0)
        }
        runCatching { dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() } }
    }

    private fun fileOf(key: String): File = File(dir, "$key.wav")

    private fun putMemory(key: String, audio: CachedAudio) {
        memory.remove(key)?.let { memoryBytes -= it.bytes }
        memory[key] = audio
        memoryBytes += audio.bytes
        // LinkedHashMap 的 removeEldestEntry 在 put 时触发，需同步扣减计量
        while (memoryBytes > maxMemoryBytes && memory.isNotEmpty()) {
            val eldestKey = memory.keys.first()
            val eldest = memory.remove(eldestKey) ?: break
            memoryBytes -= eldest.bytes
        }
    }

    private fun evictDiskIfNeeded() {
        val files = runCatching { dir.listFiles()?.filter { it.isFile } }.getOrNull() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxDiskBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxDiskBytes) return
            total -= file.length()
            runCatching { file.delete() }
        }
    }
}
