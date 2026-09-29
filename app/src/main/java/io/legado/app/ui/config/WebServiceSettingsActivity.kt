package io.legado.app.ui.config

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.service.WebService
import io.legado.app.service.kernel.RelayKernel
import io.legado.app.service.relay.RelayConfig
import io.legado.app.service.relay.RelayConnectionState
import io.legado.app.service.relay.RelayService
import io.legado.app.service.relay.RelayStateRepository
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.web.TokenManager
import io.legado.app.web.WebPortPolicy
import kotlinx.coroutines.launch

/**
 * 「Web 服务与 AI 接入」设置页宿主（web-mcp-productization 一期 · §6.1 / 任务 6.1–6.8）。
 *
 * 职责边界：**只做编排**（服务启停 / 端口保存与重启联动 / 令牌生成撤销 / 剪贴板与跳转），
 * 渲染全部交给 [WebServiceSettingsScreen]（纯读状态 + 回调）。
 *
 * 消除的中间态：本次改动前鉴权已生效，但令牌**没有任何 UI 可生成** ⇒ 写端点必定 401 且用户无解。
 */
class WebServiceSettingsActivity : BaseActivity<ViewBinding>() {

    override val binding: ViewBinding by lazy { composeShell(this) }

    private val uiState = mutableStateOf(WebServiceSettingsState())

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // S2 首启引导（§6.3）：判据 = 标志未置位 + 服务运行中；**展示即置位** ⇒ 首次弹、二次不弹。
        val firstLaunchDone = getPrefBoolean(PreferKey.webServiceFirstLaunchDone, false)
        val showGuide = WebServiceSettingsLogic.shouldShowFirstLaunchGuide(
            firstLaunchDone = firstLaunchDone,
            serviceRunning = WebService.isRun
        )
        if (showGuide) {
            putPrefBoolean(PreferKey.webServiceFirstLaunchDone, true)
        }
        refresh(showGuide)

        binding.root.attachComposeContent {
            val state = uiState.value
            WebServiceSettingsScreen(
                state = state,
                onBack = { finish() },
                onServiceToggle = ::setServiceEnabled,
                onEditPort = ::editPort,
                onWakeLockToggle = ::setWakeLock,
                onStrictToggle = ::setStrictMode,
                onCopyAddress = ::copyAddress,
                onOpenBrowser = ::openAddressInBrowser,
                onGenerateToken = ::generateToken,
                onRevokeToken = ::revokeToken,
                onDismissFirstLaunchGuide = {
                    uiState.value = uiState.value.copy(showFirstLaunchGuide = false)
                },
                onGuideCopyAddress = ::copyAddress,
                onGuideOpenBrowser = ::openAddressInBrowser,
                onGuideGenerateReadonlyToken = { generateToken(TokenManager.Level.READONLY) },
                onRelayToggle = ::setRelayEnabled,
                onOpenRelaySettings = ::openRelaySettings,
                onOpenHelp = { showHelp("webMcpHelp") },
                onShutdownAll = ::shutdownAll
            )
        }

        // S1 地址活字牌：服务内网络回调已把地址同步进 StateFlow ⇒ 页面订阅即随网络自动刷新（不轮询）
        lifecycleScope.launch {
            WebService.hostAddressFlow.collect { address ->
                uiState.value = uiState.value.copy(
                    hostAddress = address,
                    serviceRunning = WebService.isRun
                )
            }
        }
        // 四期 §3.2：中继卡片连接态真源 = RelayState（不引入第二份配置），状态变化即同步渲染
        lifecycleScope.launch {
            RelayStateRepository.state.collect { relayState ->
                uiState.value = uiState.value.copy(
                    relayConnected = relayState is RelayConnectionState.Connected
                )
            }
        }
        // 服务启停（含「我的」页快捷开关）经 EventBus 广播 ⇒ 两处开关状态恒同步（§6.7）
        observeEventSticky<String>(EventBus.WEB_SERVICE) {
            uiState.value = uiState.value.copy(serviceRunning = WebService.isRun)
        }
    }

    /** 从真实来源重建页面状态；[guideVisible] 缺省沿用当前值（避免无谓地把引导卡重新弹出来）。 */
    private fun refresh(guideVisible: Boolean = uiState.value.showFirstLaunchGuide) {
        uiState.value = WebServiceSettingsState(
            serviceRunning = WebService.isRun,
            hostAddress = WebService.hostAddress,
            port = AppConfig.webPort,
            wakeLock = getPrefBoolean(PreferKey.webServiceWakeLock, false),
            strict = TokenManager.strict,
            showFirstLaunchGuide = guideVisible,
            tokenStatuses = TokenManager.listStatus(),
            // 四期 §3.2：中继状态真源 = PreferKey + RelayState（本页只读，不写第二份配置）
            relayEnabled = getPrefBoolean(PreferKey.publicWebRelayEnabled, false),
            relayPaired = !getPrefString(PreferKey.publicWebRelayDeviceHandle).isNullOrBlank(),
            relayConnected = RelayStateRepository.state.value is RelayConnectionState.Connected
        )
    }

    /**
     * 四期 §3.1 中继开关：与 [RelaySettingsActivity] 同语义（开关即启停中继服务），
     * 开启前必须能取到已配对句柄，否则回执并保持关闭（避免"开关开着却连不上"的假象）。
     */
    private fun setRelayEnabled(enabled: Boolean) {
        if (!enabled) {
            putPrefBoolean(PreferKey.publicWebRelayEnabled, false)
            RelayService.stop(this)
            refresh()
            return
        }
        val config = RelayConfig.load(this).getOrElse {
            toastOnUi(it.message ?: getString(R.string.public_web_relay_invalid_config))
            return
        }
        runCatching { config.requireDeviceHandle() }.getOrElse {
            config.identity.secret.fill(0)
            toastOnUi(it.message ?: getString(R.string.public_web_relay_invalid_config))
            return
        }
        config.identity.secret.fill(0)
        putPrefBoolean(PreferKey.publicWebRelayEnabled, true)
        RelayService.start(this)
        refresh()
    }

    /** §3.3：**只加导航**，原中继设置页与其路由不变（配对/分享等完整操作仍在原页）。 */
    private fun openRelaySettings() {
        startActivity(Intent(this, RelaySettingsActivity::class.java))
    }

    /**
     * 四期 §2.1 S7 一键断电：红色二次确认后才执行 [RelayKernel.shutdown]。
     *
     * 确认弹窗取消 ⇒ 无任何副作用（不可逆动作必须先确认）。
     */
    private fun shutdownAll() {
        showComposeConfirmDialog(
            title = getString(R.string.web_shutdown_confirm_title),
            message = getString(R.string.web_shutdown_confirm_message),
            positiveText = getString(R.string.web_shutdown_confirm_positive),
            dangerPositive = true,
            onPositive = {
                lifecycleScope.launch {
                    // 断电①：先吊销三级全部令牌（TokenManager 属 web 鉴权层，Kernel 不得反向依赖 ⇒ 由本层执行）
                    TokenManager.revokeAll()
                    // 断电②③④：停 Web 服务 → 中继远端吊销 → 停中继服务并清本机身份
                    RelayKernel.shutdown()
                    refresh()
                    uiState.value = uiState.value.copy(serviceRunning = false)
                    toastOnUi(getString(R.string.web_shutdown_done))
                }
            }
        )
    }

    /**
     * §6.7 服务总开关。
     *
     * 先乐观置位再发广播校正：`startService` 是异步的，`WebService.isRun` 在返回瞬间仍为 false，
     * 若只按真实值渲染，开关会先弹回"关"再被广播推回"开"（可见抖动）。
     */
    private fun setServiceEnabled(enabled: Boolean) {
        putPrefBoolean(PreferKey.webService, enabled)
        if (enabled) {
            WebService.start(this)
        } else {
            WebService.stop(this)
        }
        uiState.value = uiState.value.copy(serviceRunning = enabled)
    }

    /**
     * 端口编辑。**迁移自 `OtherConfigFragment` 的「改端口 → 停+重启」联动**（§6.6 风险点）：
     * HttpServer 在启动时绑定端口，不重启则监听端口不变 ⇒ 改端口看似不生效。
     */
    private fun editPort() {
        showComposeNumberPickerDialog(
            title = getString(R.string.web_port_title),
            value = WebPortPolicy.normalize(AppConfig.webPort),
            minValue = WebPortPolicy.RANGE.first,
            maxValue = WebPortPolicy.RANGE.last,
            // ⚠ 必须具名：该重载的末位参数是 onCustom（可空），尾随 lambda 会绑到它而不是 onValue
            onValue = { picked ->
                val port = WebPortPolicy.normalize(picked)
                if (port != AppConfig.webPort) {
                    AppConfig.webPort = port
                    if (WebService.isRun) {
                        WebService.stop(this)
                        WebService.start(this)
                        toastOnUi(getString(R.string.web_port_updated_restart))
                    }
                    refresh()
                }
            }
        )
    }

    /** 唤醒锁：与旧入口同语义（服务 `onCreate` 读取）⇒ 下次启动服务时生效，不触发重启。 */
    private fun setWakeLock(enabled: Boolean) {
        putPrefBoolean(PreferKey.webServiceWakeLock, enabled)
        refresh()
    }

    /** §6.5 严格模式：`TokenManager.strict` 每次请求实时读 Preferences ⇒ 切换即时生效，无需重启。 */
    private fun setStrictMode(enabled: Boolean) {
        putPrefBoolean(PreferKey.webAuthStrict, enabled)
        refresh()
    }

    private fun copyAddress() {
        val address = WebService.hostAddress
        if (address.isBlank()) return
        sendToClip(address)
        toastOnUi(getString(R.string.web_address_copied))
    }

    private fun openAddressInBrowser() {
        val address = WebService.hostAddress
        if (address.isBlank()) return
        openUrl(address)
    }

    /**
     * §6.4 生成令牌：明文**只在本次对话框出现一次**（[TokenManager] 不保存明文）。
     * 关闭对话框后无法再查看 —— 文案已明示，只能重新生成。
     */
    private fun generateToken(level: TokenManager.Level) {
        val plain = TokenManager.generate(level)
        refresh()
        val levelTitle = getString(WebServiceSettingsLogic.levelTitleRes(level))
        showComposeConfirmDialog(
            title = getString(R.string.web_token_plain_dialog_title, levelTitle),
            message = getString(R.string.web_token_plain_warning) + "\n\n" + plain,
            positiveText = getString(R.string.web_token_copy),
            showNegative = false,
            messageInContent = true,
            onPositive = {
                sendToClip(plain)
                toastOnUi(getString(R.string.web_token_copied))
            }
        )
    }

    /** §6.4 撤销令牌：二次确认（撤销不可逆，且会让在用客户端立即失去访问）。 */
    private fun revokeToken(level: TokenManager.Level) {
        val levelTitle = getString(WebServiceSettingsLogic.levelTitleRes(level))
        showComposeConfirmDialog(
            title = getString(R.string.web_token_revoke_title, levelTitle),
            message = getString(R.string.web_token_revoke_message),
            positiveText = getString(R.string.web_token_revoke),
            dangerPositive = true,
            onPositive = {
                TokenManager.revoke(level)
                refresh()
                toastOnUi(getString(R.string.web_token_revoked, levelTitle))
            }
        )
    }
}
