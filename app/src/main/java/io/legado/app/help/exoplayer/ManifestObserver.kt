package io.legado.app.help.exoplayer

/**
 * 清单观察器：记录播放链路实际打开的清单 URI（按会话）。
 *
 * 设计依据：docs/specs/video-buffer-prefetch-v2/design.md §1.2 / AD-01
 *
 * 为什么需要它：HLS 可能是 master + 多 variant（还可能有独立音频媒体清单），
 * 客户端自行选「最高码率」不保证与播放器 ABR 选择一致；只有以播放器**实际加载**的
 * 媒体清单为基准，预取分片的缓存键才与播放请求一致。
 *
 * 线程约束：`onManifestOpened` 在播放加载线程调用，只做入队；不访问 lazy 数据源工厂。
 */
object ManifestObserver {

    /** 最多保留的候选清单数（视频 + 音频 + 少量换线残留） */
    private const val MAX_CANDIDATES = 6

    private val lock = Any()
    private val candidates = LinkedHashSet<String>()
    private var sessionId: Long = 0L

    /** 开启新会话，返回递增会话号 */
    fun newSession(): Long = synchronized(lock) {
        sessionId++
        candidates.clear()
        sessionId
    }

    fun currentSession(): Long = synchronized(lock) { sessionId }

    /** 播放链路打开清单（m3u8/mpd）时调用 */
    fun onManifestOpened(uri: String) {
        if (uri.isBlank()) return
        synchronized(lock) {
            // 最近打开的排前（LinkedHashSet 保持插入序，这里重建以维持 LRU 语义）
            candidates.remove(uri)
            candidates.add(uri)
            while (candidates.size > MAX_CANDIDATES) {
                val oldest = candidates.first()
                candidates.remove(oldest)
            }
        }
    }

    /** 候选快照（最近打开优先） */
    fun snapshot(): List<String> = synchronized(lock) {
        candidates.toList().asReversed()
    }

    fun clear() = synchronized(lock) {
        candidates.clear()
    }
}
