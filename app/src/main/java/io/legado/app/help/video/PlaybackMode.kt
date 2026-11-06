package io.legado.app.help.video

/**
 * 播放模式（design §1.13）：由入口一次性判定，页面内禁止 FEED↔SERIES 双向互切。
 */
enum class PlaybackMode {
    /** 列表/推荐流上下文：上滑 = 下一个作品 */
    FEED,

    /** 本部作品上下文：上滑 = 本剧下一集 */
    SERIES,

    /** 单片上下文：上滑 = 停止并提示 */
    SINGLE
}

/**
 * 模式状态：末集只允许 `SERIES → SINGLE` 单向降级，且只触发一次。
 */
data class PlaybackModeState(
    val mode: PlaybackMode,
    val degraded: Boolean = false
) {
    /** 单向降级：非 SERIES 或已降级时返回自身（幂等） */
    fun degradeToSingle(): PlaybackModeState =
        if (mode == PlaybackMode.SERIES && !degraded) {
            PlaybackModeState(PlaybackMode.SINGLE, degraded = true)
        } else {
            this
        }
}

/**
 * 入口上下文（design §1.13 入口矩阵）：一次性判定初始模式。
 */
data class EntryContext(
    /** 0=无列表, 1=单部, >1=多部 */
    val listSize: Int,
    /** 详情页/下载/外部 URL/单元素列表等「本剧入口」 */
    val isSeriesEntry: Boolean,
    /** 显式 hint 优先（Intent extra），null=按默认规则 */
    val modeHint: PlaybackMode? = null
)

/**
 * 入口模式判定纯函数（design §1.13 / AD-10）。
 */
object EntryModeResolver {

    fun resolve(context: EntryContext): PlaybackMode {
        context.modeHint?.let { return it }
        if (context.isSeriesEntry) return PlaybackMode.SERIES
        return if (context.listSize > 1) PlaybackMode.FEED else PlaybackMode.SERIES
    }
}
