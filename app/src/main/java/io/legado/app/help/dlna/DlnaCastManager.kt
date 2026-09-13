package io.legado.app.help.dlna

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.VideoPlay
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * add-dlna-cast：投屏会话状态机的阶段。
 */
enum class CastPhase {
    IDLE,
    DISCOVERING,
    DEVICE_LIST_READY,
    CONNECTING,
    CASTING,
    FAILED
}

/**
 * add-dlna-cast：投屏失败原因。
 *
 * 刻意用枚举而非字符串：`DlnaCastManager` 是长生命周期单例，**不应持有 Context 取文案**
 * （会泄漏），文案由 UI 层按本枚举映射 string 资源。
 */
enum class CastError {
    NO_WIFI,
    NO_LAN_IP,
    NO_DEVICE_FOUND,
    CONNECT_FAILED,
    DEVICE_REJECTED,
    DEVICE_LOST,
    NETWORK_CHANGED,
    LOCAL_FILE_MISSING,
    START_FAILED
}

/**
 * add-dlna-cast：投屏 UI 状态（唯一数据源，见 design「UI 实现约定」）。
 */
data class DlnaCastUiState(
    val phase: CastPhase = CastPhase.IDLE,
    val devices: List<DlnaDevice> = emptyList(),
    val currentDevice: DlnaDevice? = null,
    val lastDeviceName: String? = null,
    /** 上次设备是否在本次发现中在线 */
    val lastDeviceOnline: Boolean = false,
    val title: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val paused: Boolean = false,
    /** 设备支持的音量；null = 不支持（UI 隐藏音量控件） */
    val volume: Int? = null,
    /** 设备是否支持进度控制（Seek 失败或时长恒为 0 时置 false） */
    val supportSeek: Boolean = true,
    /** 是否使用代理投递（UI 显示「代理转发中」） */
    val usingProxy: Boolean = false,
    val error: CastError? = null,
    /** 多网卡环境下的提示（实际使用的地址），null = 无需提示 */
    val multiAddressHint: String? = null,
    /** 是否提示"可能与电视不在同一网络" */
    val subnetMismatchHint: Boolean = false
)

/**
 * add-dlna-cast：投屏会话编排（design AD-03 / AD-06 / AD-11）。
 *
 * 单一职责：把"发现 → 投递决策 → 建立会话 → 轮询 → 收尾"串成一条可中断的链路，
 * 并保证**任何失败路径都不残留代理/服务/状态**。
 *
 * 线程模型：内部协程统一跑在 IO；状态用 `MutableStateFlow`（线程安全），
 * UI 只读 [state]。本类**不持有 Activity**，对本地播放器的控制在
 * [PlayerControl] 通道上（AD-14）。
 */
object DlnaCastManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(DlnaCastUiState())
    val state: StateFlow<DlnaCastUiState> = _state.asStateFlow()

    /** 本地播放器控制通道；未注册时为 null（见 PlayerControl KDoc） */
    @Volatile
    var playerControl: PlayerControl? = null

    /** 前台服务命令回调（由 DlnaCastService 注入，避免 Manager 反向依赖 Service） */
    @Volatile
    var serviceCommand: ((CastServiceCommand) -> Unit)? = null

    private var proxyServer: CastProxyServer? = null
    private var pollJob: Coroutine<*>? = null
    private var discoveryJob: Coroutine<*>? = null

    @Volatile
    private var session: CastProxySession? = null

    private val pollFailures = AtomicInteger(0)

    // ==================== 状态查询 ====================

    fun isCastingActive(): Boolean = _state.value.phase == CastPhase.CASTING

    /**
     * 会话是否存活（含建立中）。REQ-08 / AD-07「禁止第二路播放」用：
     * CONNECTING 阶段本地播放尚未暂停，切集放行会造成"投递旧集 + 本地播新集"双路，
     * 因此切集拦截必须覆盖 CONNECTING。
     */
    fun isSessionBusy(): Boolean =
        _state.value.phase == CastPhase.CASTING || _state.value.phase == CastPhase.CONNECTING

    /** 投屏入口是否可用（REQ-01 双门控） */
    fun isEntryAvailable(): Boolean =
        VideoPlay.dlnaCastEnabled && !VideoPlay.videoUrl.isNullOrBlank()

    // ==================== 生命周期复位 ====================

    /**
     * 服务创建时复位（红队第 5 轮）。
     *
     * `object` 会在进程存活期内一直存在，服务重启时可能残留上一轮的 registry/token/状态，
     * 必须先无副作用地清干净，否则会出现"状态显示正在投屏但其实没有会话"。
     */
    fun resetOnServiceCreate() {
        cancelPolling()
        discoveryJob?.cancel()
        discoveryJob = null
        proxyServer?.stopServer()
        proxyServer = null
        CastProxyRegistry.clear()
        session = null
        pollFailures.set(0)
        _state.value = DlnaCastUiState(
            phase = CastPhase.IDLE,
            lastDeviceName = VideoPlay.dlnaLastDeviceName
        )
    }

    // ==================== 发现 ====================

    /**
     * 开始搜索设备（REQ-02）。
     *
     * 前置校验：非 WLAN 直接拦下（REQ-02 Scenario「网络不可用」），不发起发现。
     */
    fun startDiscovery(context: Context) {
        val appContext = context.applicationContext
        val pick = CastNetworkHelper.pickLanAddress(appContext)
        if (pick.address == null) {
            _state.value = _state.value.copy(
                phase = CastPhase.FAILED,
                error = CastError.NO_WIFI,
                devices = emptyList()
            )
            return
        }
        discoveryJob?.cancel()
        _state.value = _state.value.copy(
            phase = CastPhase.DISCOVERING,
            devices = emptyList(),
            error = null,
            multiAddressHint = if (pick.ambiguous) pick.address else null
        )
        discoveryJob = Coroutine.async(scope, Dispatchers.IO) {
            SsdpDiscovery.discover(appContext)
        }.onSuccess { devices ->
            val lastUdn = VideoPlay.dlnaLastDeviceUdn
            _state.value = _state.value.copy(
                phase = if (devices.isEmpty()) CastPhase.FAILED else CastPhase.DEVICE_LIST_READY,
                devices = devices,
                lastDeviceOnline = lastUdn != null && devices.any { it.udn == lastUdn },
                error = if (devices.isEmpty()) CastError.NO_DEVICE_FOUND else null
            )
        }.onError { error ->
            AppLog.put("DlnaCast 设备发现失败: ${error.message}", error)
            _state.value = _state.value.copy(
                phase = CastPhase.FAILED,
                error = CastError.NO_DEVICE_FOUND
            )
        }
    }

    // ==================== 投屏建立 ====================

    /**
     * 投屏到指定设备（REQ-04 / REQ-06 / AD-03）。
     *
     * 顺序铁律（AD-07 + 红队）：**先投递成功、再暂停本地播放**；
     * 若投递失败而本地已被暂停，需回滚恢复播放。
     */
    fun castTo(context: Context, device: DlnaDevice) {
        val appContext = context.applicationContext
        val url = VideoPlay.videoUrl
        if (url.isNullOrBlank()) {
            _state.value = _state.value.copy(phase = CastPhase.FAILED, error = CastError.START_FAILED)
            return
        }
        if (_state.value.phase == CastPhase.CONNECTING) return // 防重复投递（REQ-04）
        _state.value = _state.value.copy(
            phase = CastPhase.CONNECTING,
            currentDevice = device,
            error = null
        )
        Coroutine.async(scope, Dispatchers.IO) {
            establish(appContext, device, url)
        }.onError { error ->
            AppLog.put("DlnaCast 投屏建立失败: ${error.message}", error)
            teardown()
            _state.value = _state.value.copy(
                phase = CastPhase.FAILED,
                error = CastError.START_FAILED
            )
        }
    }

    private suspend fun establish(appContext: Context, device: DlnaDevice, url: String) {
        val headers = (VideoPlay.currentPlayHeaders ?: emptyMap())
            .filterValues { it.isNotBlank() }

        // ① 投递决策（AD-03 三态）
        val mode = decideMode(url, headers)
        // MIME 三级推断：扩展名 → HEAD（**必须带会话 headers**，否则防盗链源回 403 会误判）
        // → 兜底。file:// 不做 HEAD（本地路径发 HTTP 请求必然失败，直接走扩展名）。
        val mime = MimeSniffer.resolve(url) {
            if (mode == DeliveryMode.FILE_PROXY) null
            else DlnaHttp.headContentType(url, headers)
        }

        // ② 代理模式先起代理并登记，拿到投递地址
        var castUrl = url
        if (mode != DeliveryMode.DIRECT) {
            val source = if (mode == DeliveryMode.FILE_PROXY) {
                val path = url.removePrefix("file://")
                if (!java.io.File(path).exists()) {
                    _state.value = _state.value.copy(
                        phase = CastPhase.FAILED,
                        error = CastError.LOCAL_FILE_MISSING
                    )
                    teardown()
                    return
                }
                CastSource.LocalFile(mime, path)
            } else {
                CastSource.Http(mime, url, headers)
            }
            val pick = CastNetworkHelper.pickLanAddress(appContext)
            val lanIp = pick.address
            if (lanIp == null) {
                _state.value = _state.value.copy(
                    phase = CastPhase.FAILED,
                    error = CastError.NO_LAN_IP
                )
                teardown(notifyDevice = false)
                return
            }
            val server = proxyServer ?: CastProxyServer().also { proxyServer = it }
            // startServer 返回 null=启动失败，统一按 -1 走 START_FAILED 分支
            val port = server.startServer() ?: -1
            if (port <= 0) {
                _state.value = _state.value.copy(
                    phase = CastPhase.FAILED,
                    error = CastError.START_FAILED
                )
                teardown(notifyDevice = false)
                return
            }
            val newSession = CastProxyRegistry.createSession(
                CastProxyRegistry.sanitizeName(url),
                source
            )
            session = newSession
            castUrl = "http://$lanIp:$port${DlnaConstants.PROXY_PATH_PREFIX}" +
                "/${newSession.token}/${newSession.baseKey}"
            _state.value = _state.value.copy(
                usingProxy = true,
                subnetMismatchHint = !CastNetworkHelper.sameSubnet24(
                    lanIp,
                    device.location.substringAfter("//").substringBefore('/').substringBefore(':')
                ),
                multiAddressHint = if (pick.ambiguous) lanIp else null
            )
        } else {
            _state.value = _state.value.copy(usingProxy = false)
        }

        // ③ SetAVTransportURI（带 DIDL；已知"无元数据兼容"的设备直接跳过元数据）
        val metadata = if (VideoPlay.isNoMetaDevice(device.udn)) {
            DidlLiteBuilder.EMPTY_METADATA
        } else {
            DidlLiteBuilder.build(castUrl, MimeSniffer.protocolInfo(mime), VideoPlay.videoTitle)
        }
        var result = AvTransportClient.setAvTransportUri(device, castUrl, metadata)
        if (!result.success && metadata != DidlLiteBuilder.EMPTY_METADATA) {
            // 降级链：老设备拒收 DIDL → 空元数据重试一次，并记住该设备
            LogUtils.d(DlnaConstants.TAG) {
                "带元数据投递被拒（${result.errorText}），改用空元数据重试"
            }
            result = AvTransportClient.setAvTransportUri(
                device,
                castUrl,
                DidlLiteBuilder.EMPTY_METADATA
            )
            if (result.success) {
                VideoPlay.markNoMetaDevice(device.udn)
            }
        }
        if (!result.success) {
            _state.value = _state.value.copy(phase = CastPhase.FAILED, error = CastError.DEVICE_REJECTED)
            teardown(notifyDevice = false)
            return
        }

        // ④ Play
        if (!AvTransportClient.play(device).success) {
            _state.value = _state.value.copy(phase = CastPhase.FAILED, error = CastError.CONNECT_FAILED)
            teardown()
            return
        }

        // ⑤ 首帧判定（AD-11）：8s 内仍 STOPPED 视为拉流失败
        delay(2_000)
        val stateText = AvTransportClient.transportState(device)
        if (stateText == AvTransportClient.STATE_STOPPED ||
            stateText == AvTransportClient.STATE_NO_MEDIA
        ) {
            delay(DlnaConstants.TIMEOUT_FIRST_FRAME_MS - 2_000)
            val again = AvTransportClient.transportState(device)
            if (again == AvTransportClient.STATE_STOPPED || again == AvTransportClient.STATE_NO_MEDIA) {
                _state.value = _state.value.copy(
                    phase = CastPhase.FAILED,
                    error = CastError.DEVICE_REJECTED
                )
                teardown()
                return
            }
        }

        // ⑥ 投递成功后才暂停本地播放（AD-07 顺序铁律）
        playerControl?.pauseLocal()

        // ⑦ 记忆设备（REQ-10）
        VideoPlay.dlnaLastDeviceUdn = device.udn
        VideoPlay.dlnaLastDeviceName = device.displayName

        pollFailures.set(0)
        _state.value = _state.value.copy(
            phase = CastPhase.CASTING,
            currentDevice = device,
            title = VideoPlay.videoTitle,
            paused = false,
            supportSeek = true,
            volume = RenderingControlClient.getVolume(device),
            error = null
        )
        startPolling(device)
    }

    /** 三态投递决策（REQ-06 / AD-03） */
    private fun decideMode(url: String, headers: Map<String, String>): DeliveryMode {
        if (url.startsWith("file://")) return DeliveryMode.FILE_PROXY
        if (VideoPlay.dlnaForceProxy) return DeliveryMode.HTTP_PROXY
        return if (headers.isEmpty()) DeliveryMode.DIRECT else DeliveryMode.HTTP_PROXY
    }

    // ==================== 轮询（AD-06）====================

    private fun startPolling(device: DlnaDevice) {
        cancelPolling()
        pollJob = Coroutine.async(scope, Dispatchers.IO) {
            while (true) {
                val paused = _state.value.paused
                delay(
                    if (paused) DlnaConstants.POLL_INTERVAL_PAUSED_MS
                    else DlnaConstants.POLL_INTERVAL_PLAYING_MS
                )
                val snapshot = AvTransportClient.positionInfo(device)
                if (snapshot == null) {
                    if (pollFailures.incrementAndGet() >= DlnaConstants.POLL_FAILURE_THRESHOLD) {
                        handleDeviceLost()
                        return@async
                    }
                    continue
                }
                if (snapshot.state == AvTransportClient.STATE_ERROR) {
                    if (pollFailures.incrementAndGet() >= DlnaConstants.POLL_FAILURE_THRESHOLD) {
                        handleDeviceLost()
                        return@async
                    }
                    continue
                }
                pollFailures.set(0)
                // 时长为 0 说明设备不上报时长，进度控制无意义 → 隐藏进度条
                val seekSupported = snapshot.durationMs > 0L
                _state.value = _state.value.copy(
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                    supportSeek = seekSupported,
                    paused = snapshot.state == AvTransportClient.STATE_PAUSED
                )
            }
        }
    }

    private fun cancelPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun handleDeviceLost() {
        _state.value = _state.value.copy(error = CastError.DEVICE_LOST)
        teardown(notifyDevice = false)
        _state.value = _state.value.copy(phase = CastPhase.FAILED, error = CastError.DEVICE_LOST)
    }

    // ==================== 控制 ====================

    /** 暂停/继续 */
    fun togglePause() {
        val device = _state.value.currentDevice ?: return
        val wantPause = !_state.value.paused
        // 乐观更新，轮询回来再校正（AD-06）
        _state.value = _state.value.copy(paused = wantPause)
        Coroutine.async(scope, Dispatchers.IO) {
            if (wantPause) AvTransportClient.pause(device) else AvTransportClient.play(device)
        }.onError { error ->
            AppLog.put("DlnaCast 暂停/继续失败: ${error.message}", error)
        }
    }

    /** 拖动进度 */
    fun seekTo(positionMs: Long) {
        val device = _state.value.currentDevice ?: return
        _state.value = _state.value.copy(positionMs = positionMs)
        Coroutine.async(scope, Dispatchers.IO) {
            AvTransportClient.seek(device, positionMs)
        }.onError { error ->
            AppLog.put("DlnaCast seek 失败: ${error.message}", error)
        }
    }

    /** 音量 */
    fun setVolume(volume: Int) {
        val device = _state.value.currentDevice ?: return
        _state.value = _state.value.copy(volume = volume.coerceIn(0, 100))
        Coroutine.async(scope, Dispatchers.IO) {
            RenderingControlClient.setVolume(device, volume)
        }.onError { error ->
            AppLog.put("DlnaCast 设置音量失败: ${error.message}", error)
        }
    }

    /** 用户主动结束投屏（REQ-05 / REQ-08） */
    fun stopByUser() {
        val device = _state.value.currentDevice
        if (device != null) {
            Coroutine.async(scope, Dispatchers.IO) {
                AvTransportClient.stop(device)
            }.onError { error ->
                // Stop 失败不阻塞用户退出（AD-11）
                LogUtils.d(DlnaConstants.TAG) { "Stop 下发失败，忽略: ${error.message}" }
            }
        }
        teardown(notifyDevice = false)
        _state.value = _state.value.copy(
            phase = CastPhase.IDLE,
            currentDevice = null,
            positionMs = 0L,
            durationMs = 0L,
            paused = false,
            usingProxy = false
        )
    }

    /** 网络断开/切换（AD-15 由 DlnaCastService 回调） */
    fun onNetworkLost() {
        if (_state.value.phase != CastPhase.CASTING &&
            _state.value.phase != CastPhase.CONNECTING
        ) {
            return
        }
        teardown(notifyDevice = true)
        _state.value = _state.value.copy(phase = CastPhase.FAILED, error = CastError.NETWORK_CHANGED)
    }

    /**
     * 统一收尾（AD-11 顺序铁律）。
     *
     * ① 停轮询 → ② 清注册表（阻断新请求）→ ③ 停代理 → ④ 停前台服务 → ⑤ 状态复位。
     * 顺序不可颠倒：先停服务再停轮询，轮询会对已停服务写状态。
     *
     * @param notifyDevice 是否向当前设备下发 Stop。投递尚未成功（连接失败/设备离线/
     *   用户已单独下发 Stop）时传 false，避免多余请求；默认 true 兜底清理。
     */
    fun teardown(notifyDevice: Boolean = true) {
        cancelPolling()
        if (notifyDevice) {
            _state.value.currentDevice?.let { device ->
                Coroutine.async(scope, Dispatchers.IO) {
                    AvTransportClient.stop(device)
                }.onError { error ->
                    // Stop 失败不阻塞收尾（AD-11）
                    LogUtils.d(DlnaConstants.TAG) { "teardown Stop 下发失败，忽略: ${error.message}" }
                }
            }
        }
        CastProxyRegistry.clear()
        session = null
        proxyServer?.stopServer()
        proxyServer = null
        pollFailures.set(0)
        serviceCommand?.invoke(CastServiceCommand.STOP_SELF)
    }

    /** 内部投递模式 */
    private enum class DeliveryMode { DIRECT, HTTP_PROXY, FILE_PROXY }
}

/** add-dlna-cast：Manager → 前台服务的命令 */
enum class CastServiceCommand { STOP_SELF }
