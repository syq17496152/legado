package io.legado.app.help.dlna

/**
 * add-dlna-cast AD-14：投屏会话对**本地播放器**的控制通道。
 *
 * 为什么不直接持有播放器引用：`DlnaCastManager` 是 `object` 单例，直接存
 * Activity/Fragment 会内存泄漏；而且播放器句柄来源不唯一（ViewPager2 模式取
 * `currentFragment?.playerView?.currentPlayer`，悬浮窗模式另有链路）。
 *
 * 因此改为**回调注册**：`VideoPlayerActivity` 在 `onResume` 注册、`onPause` 注销，
 * 单例只通过接口调用。任何方法都允许在未注册时被调用（此时整个接口为 null，
 * 调用方直接跳过）—— 例如从通知启动投屏时播放页根本没在前台，本地本来也没在播。
 */
interface PlayerControl {

    /** 暂停本地播放（不改变已记录的播放进度） */
    fun pauseLocal()

    /** 恢复本地播放（投屏失败回滚用） */
    fun resumeLocal()

    /** 本地播放器当前进度（毫秒）；拿不到返回 0 */
    fun currentPositionMs(): Long
}
