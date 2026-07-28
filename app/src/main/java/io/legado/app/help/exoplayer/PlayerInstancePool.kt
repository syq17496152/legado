package io.legado.app.help.exoplayer

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.legado.app.constant.AppLog
import splitties.init.appCtx

/**
 * T5.1: 播放器实例池（P2-20，对齐 GSYVideoPlayer 实例复用思想 + 抖音/快手播放器池化实践）
 *
 * 解决根因：抖音风格 ViewPager2 垂直滑动场景，GSY 全局单 manager 模型下每次切视频都
 * release 旧 ExoPlayer 实例 + Builder().build() 新建实例（渲染器初始化/解码器查询/线程启动
 * 约 30-100ms），快速滑动时内存抖动 + 起播延迟。
 *
 * 池化策略：
 * - 池化对象：mInternalPlayer（ExoPlayer 重量级实例），非 Exo2MediaPlayer（轻量字段容器）
 * - 池大小：3 个（offscreenPageLimit=1 保留当前+前后各 1 视图，全局单 manager 稳态仅 0-1 空闲，
 *   MAX=3 为极端场景上限），LRU 淘汰（队首=最近归还，队尾=最久未用）
 * - 生命周期：= VideoPlayerActivity 生命周期，onDestroy 调 clear() 全量释放（避免后台占用解码器）
 *
 * 共享策略（V-P0-1 修正：TrackSelector 改为每实例独立）：
 * - sharedRendererFactory：DefaultRenderersFactory 无状态，官方可共享
 * - TrackSelector：每实例独立（DefaultTrackSelector.init() 有 checkState 校验，
 *   一个 selector 同一时刻只能绑定一个存活 Player；共享单例在并发 acquire 场景
 *   二次 init 抛 IllegalStateException——2026-07-27 真机 5 次 FATAL 实证。
 *   原注释"官方 demo 即 app 级共享"系错误类推，demo 为单实例播放器场景）
 * - LoadControl：工厂方法按带宽档位新建（共享 DefaultAllocator 内存池，保留内存收益）
 *
 * 线程约束：acquire/recycle 全链路在 Exo2MediaPlayer 创建线程（GSY 默认主线程），
 * 池内实例 Looper 与 acquire 传入 Looper 一致；@Synchronized 双保险防极端并发。
 */
@UnstableApi
object PlayerInstancePool {

    private const val MAX_POOL_SIZE = 3

    /**
     * 池内空闲实例队列（队首=最近归还，队尾=最久未用）
     */
    private val pool = ArrayDeque<ExoPlayer>()

    /**
     * V-P0-1: TrackSelector 工厂——每实例独立
     *
     * 根因：共享单例在并发 acquire（R5 双命中/ViewPager2 双 Fragment 并发 prepare）时
     * 二次 init 触发 DefaultTrackSelector checkState → IllegalStateException（5 次 FATAL）。
     * 音轨 override 状态仍由 recycle 时 clearOverrides 重置，不受独立化影响。
     */
    fun createTrackSelector(): DefaultTrackSelector {
        return DefaultTrackSelector(appCtx)
    }

    /**
     * 实例 → 其独立 TrackSelector 的映射（IdentityHashMap：按引用相等）
     * 用途：EventLogger 等需要与 player 同一 selector 实例的场景（Exo2MediaPlayer.attachToPlayer）
     */
    private val selectorMap = java.util.IdentityHashMap<ExoPlayer, DefaultTrackSelector>()

    /**
     * 获取 player 实例关联的 TrackSelector（新建时登记，release 时移除）
     */
    @Synchronized
    fun trackSelectorOf(player: ExoPlayer): DefaultTrackSelector? {
        return selectorMap[player]
    }

    /**
     * 共享渲染器工厂（无状态，EXTENSION_RENDERER_MODE_PREFER 与原 Exo2MediaPlayer 配置一致）
     */
    val sharedRendererFactory: DefaultRenderersFactory by lazy {
        DefaultRenderersFactory(appCtx).setExtensionRendererMode(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        )
    }

    /**
     * 共享内存分配器（3 实例共享同一缓冲内存池，保留原 sharedLoadControl 的内存收益）
     */
    private val sharedAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    /**
     * V-003-P2-1: LoadControl 按 tier 缓存（避免每次 acquire 重复创建）
     *
     * 不用 lazy 单例的原因：lazy 只初始化一次，档位会"进程级终身不变"；
     * 不用每次新建的原因：同 tier 重复创建浪费资源。
     * 折中：按 tier.name 缓存，不同 tier 不同实例，同 tier 复用。
     */
    private val loadControlCache = java.util.concurrent.ConcurrentHashMap<String, DefaultLoadControl>()

    /**
     * LoadControl 工厂：按当前带宽档位获取（同 tier 缓存复用，共享 allocator）
     */
    fun createLoadControl(): DefaultLoadControl {
        val tier = ExoPlayerHelper.getCurrentBandwidthTier()
        return loadControlCache.computeIfAbsent(tier.name) {
            AppLog.put(
                "PlayerPool: createLoadControl (new), tier=$tier, " +
                    "bitrateEstimate=${ExoPlayerHelper.bandwidthMeter.bitrateEstimate}bps"
            )
            ExoPlayerHelper.createLoadControlByTier(tier, sharedAllocator)
        }
    }

    /**
     * 获取播放器实例（命中复用 / 未命中新建）
     *
     * @param looper 播放器工作 Looper（仅新建时生效；复用实例沿用其创建时 Looper，全链路同线程约束保证一致）
     * @return 可用实例（池内取出已做状态重置，调用方需重新 addListener + setVideoSurface + setMediaItem）
     */
    @Synchronized
    fun acquire(looper: Looper): ExoPlayer {
        val reused = pool.removeFirstOrNull()
        if (reused != null) {
            AppLog.put(
                "PlayerPool: acquire hit (reuse), poolSize=${pool.size}, " +
                    "selector=${System.identityHashCode(selectorMap[reused])}"
            )
            return reused
        }
        // V-P0-1: 每实例独立 TrackSelector（共享单例并发二次 init 崩溃）
        val selector = createTrackSelector()
        val player = ExoPlayer.Builder(appCtx, sharedRendererFactory)
            .setLooper(looper)
            .setTrackSelector(selector)
            .setLoadControl(createLoadControl())
            .setMediaSourceFactory(
                // 与原 Exo2MediaPlayer 配置一致：resolvingDataSource 支持 SPLIT_TAG per-request Header 注入
                DefaultMediaSourceFactory(ExoPlayerHelper.resolvingDataSource)
                    .setLiveTargetOffsetMs(5000) // 直播时延 5 秒
            )
            .build()
        selectorMap[player] = selector
        AppLog.put(
            "PlayerPool: acquire miss (create new), " +
                "selector=${System.identityHashCode(selector)}"
        )
        return player
    }

    /**
     * 归还实例到池中（LRU：池满淘汰最久未用实例）
     *
     * 状态重置清单（防止跨视频状态污染）：
     * - stop + clearMediaItems：清除播放内容/timeline
     * - clearVideoSurface：解除 surface 绑定（旧 surface 可能已销毁）
     * - clearOverrides：清除音轨选择 override（selectAudioTrack 残留）
     * - playbackParameters=DEFAULT：重置倍速
     * - repeatMode=REPEAT_MODE_OFF：重置循环模式
     * - playWhenReady=false：重置自动播放标志
     *
     * 注意：listener 移除由调用方（Exo2MediaPlayer.detachFromPlayer）在 recycle 前完成，
     * ExoPlayer 无批量移除 listener API，池无法感知外部挂了哪些 listener。
     *
     * @param player 待归还实例（必须已从所有 listener 解绑）
     */
    @Synchronized
    fun recycle(player: ExoPlayer) {
        kotlin.runCatching {
            player.stop()
            player.clearMediaItems()
            player.clearVideoSurface()
            player.trackSelectionParameters =
                player.trackSelectionParameters.buildUpon().clearOverrides().build()
            player.playbackParameters = PlaybackParameters.DEFAULT
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.playWhenReady = false
        }.onFailure {
            // 状态重置失败（极端场景）——直接销毁不入池，避免污染池
            AppLog.put("PlayerPool: recycle reset failed, release directly", it)
            selectorMap.remove(player)  // player 已 release，移除映射防泄漏
            kotlin.runCatching { player.release() }
            return
        }
        if (pool.size >= MAX_POOL_SIZE) {
            val oldest = pool.removeLast()
            selectorMap.remove(oldest)  // evict 的 player 即将 release，移除映射防泄漏
            kotlin.runCatching { oldest.release() }
            AppLog.put("PlayerPool: evict oldest (LRU), poolSize=${pool.size}")
        }
        pool.addFirst(player)
        AppLog.put("PlayerPool: recycled, poolSize=${pool.size}")
    }

    /**
     * 清空池（VideoPlayerActivity.onDestroy 调用）
     * 池生命周期 = Activity 生命周期，避免 App 后台时池内实例占用解码器/缓冲区资源
     */
    @Synchronized
    fun clear() {
        var count = 0
        while (pool.isNotEmpty()) {
            kotlin.runCatching { pool.removeFirst().release() }
            count++
        }
        selectorMap.clear()  // 池内实例全部 release，清空映射防泄漏
        if (count > 0) {
            AppLog.put("PlayerPool: cleared $count instances on activity destroy")
        }
    }
}
