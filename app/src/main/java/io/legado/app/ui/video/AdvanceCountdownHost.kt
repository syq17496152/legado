package io.legado.app.ui.video

/**
 * 续播倒计时宿主的生命周期协调（design §1.7 / tasks §7.1）
 *
 * 只做「前台注册 / 离屏注销」的幂等：
 * - `onResume` 重复回调不会重复注册（避免监听集合泄漏与重复提示）；
 * - `onPause` 未注册时不做任何事；
 * - 状态可被测试直接断言（[isRegistered]）。
 */
internal class AdvanceCountdownHost(
    private val register: () -> Unit,
    private val unregister: () -> Unit
) {

    var isRegistered: Boolean = false
        private set

    fun onResume() {
        if (isRegistered) return
        register()
        isRegistered = true
    }

    fun onPause() {
        if (!isRegistered) return
        unregister()
        isRegistered = false
    }
}
