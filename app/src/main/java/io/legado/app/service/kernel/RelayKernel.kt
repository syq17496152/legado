package io.legado.app.service.kernel

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.service.WebService
import io.legado.app.service.relay.RelayConfig
import io.legado.app.service.relay.RelayConnectionState
import io.legado.app.service.relay.RelayControlClient
import io.legado.app.service.relay.RelaySecretStore
import io.legado.app.service.relay.RelayService
import io.legado.app.service.relay.RelayStateRepository
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 公网中继域业务内核（web-mcp-productization 四期 · tasks 2.1「S7 一键断电」）。
 *
 * 契约同 [BookKernel]：**只返回结构化 Map**、**全链挂起**、**零 `runBlocking`**、失败抛异常。
 * REST / MCP 工具层只调用本内核，业务编排不落在传输入口（AD-10）。
 *
 * 本内核**只读取并编排既有中继能力**，不新增中继实现（令牌吊销属上层，见 [shutdown]）：
 * - Web 服务：[WebService.stop] / [WebService.isRun]；
 * - 中继本机秘密：`RelaySecretStore.clear()`（位于 `service/relay/RelaySecurity.kt`，轮换身份并置关闭）；
 * - 中继连接：[RelayService.stop] / `RelayStateRepository.state`（`service/relay/RelayState.kt`）；
 * - 中继控制面：`RelayControlClient`（`revokeShare` 逐分享吊销；`close()` 擦除本机身份秘钥）。
 *
 * 已知上限 / 降级（如实声明，禁止臆造 Worker 端点）：
 * 1. **设备级吊销端点未就绪**：`RelayControlClient` 只有 `provision` / `createReadShare` /
 *    `revokeShare`（`DELETE /v1/device/share/{id}`），**没有 `revokeDevice`**（无 `DELETE /v1/device`）
 *    ⇒ 按 tasks 2.1.5 降级：仅逐分享吊销 + 清本机句柄 + 置 [PreferKey.publicWebRelayRevokePending] 待补标记；
 * 2. **分享清单无本机持久化**：`RelaySettingsActivity` 的分享 id 仅存于界面态，Worker 亦无列举端点
 *    ⇒ 本机无法枚举历史分享，`revokeShare` 只能对**已知句柄**执行（当前恒为空，`shareIdsKnown = false`）；
 * 3. 中继控制面为同步 OkHttp 调用，本内核在 `withContext(IO)` 内执行（**不阻塞主线程，无 runBlocking**）；
 *    逐分享 / 逐设备吊销失败一律被 `runCatching` 吞掉并如实回告，**不阻断断电主链路**。
 */
object RelayKernel {

    /** 断电回执用的快照：Web 服务是否在跑 / 中继连接态 / 是否已配对 / 是否留有待补吊销标记。 */
    suspend fun status(): Map<String, Any?> = withContext(IO) {
        snapshot().also { AppLog.put("RelayKernel.status: relay=${it["relay"]}") }
    }

    /**
     * 「一键断电」主链路（tasks 2.1.2 ~ 2.1.5）：② 停止 Web 服务 → ③ 中继在线吊销
     * （须在清本机秘密**之前**，控制证明依赖身份秘钥）→ ④ 停止中继服务并清本机中继秘密。
     *
     * **① 令牌吊销不在此处 —— 由上层（设置页 / 未来端点）在调用本方法之前执行
     * `TokenManager.revokeAll()`**：`TokenManager` 属 `io.legado.app.web` 鉴权层，内核**不得反向依赖**
     * 上层（KernelSweepTest / 门禁 G-22 断言 `service/kernel/` 零 `io.legado.app.web` 依赖）。
     * 调用方漏吊销令牌即"假断电"，故设置页把两步放在同一入口串行执行（见
     * `WebServiceSettingsActivity.shutdownAll`）。
     *
     * 顺序说明：中继吊销使用 `RelayControlClient` 的 `x-legado-signature` 控制证明，而该证明依赖
     * `RelayIdentity.secret`；一旦先执行 `RelaySecretStore.clear()` 则秘钥不可逆丢失 ⇒ 故吊销排在清理之前。
     *
     * @return `{ before, steps, pendingRevoke }`：`before` 为断电前快照，`steps` 为逐项回执，
     *   `pendingRevoke` 为清理后待补标记位。
     */
    suspend fun shutdown(): Map<String, Any?> = withContext(IO) {
        val before = snapshot()
        val steps = linkedMapOf<String, Any?>()

        // ① 停止 Web 服务（HTTP / WebSocket / 端口随服务销毁释放）
        steps["webServiceStopped"] = WebService.isRun
        runCatching { WebService.stop(appCtx) }
            .onFailure { AppLog.put("RelayKernel.shutdown: stop web service failed", it) }

        // ② 中继在线吊销（须在清秘钥前；未配对 / 端点未就绪则降级）
        steps["relay"] = revokeRelayRemote()

        // ③ 停止中继服务 + 清本机中继秘密（轮换身份、置关闭，防旧设备身份被静默复用）
        runCatching { RelayService.stop(appCtx) }
        steps["relaySecretsCleared"] = runCatching { RelaySecretStore(appCtx).clear() }.isSuccess

        AppLog.put("RelayKernel.shutdown: pending=${appCtx.getPrefBoolean(PreferKey.publicWebRelayRevokePending, false)}")
        mapOf(
            "before" to before,
            "steps" to steps,
            "pendingRevoke" to appCtx.getPrefBoolean(PreferKey.publicWebRelayRevokePending, false),
        )
    }

    /**
     * 中继恢复连接后的补吊销（tasks 2.1.4）：读 [PreferKey.publicWebRelayRevokePending] 标记执行，
     * **成功则清标记**。
     *
     * 降级说明：设备级吊销端点未就绪（见类 KDoc 第 1 条）⇒ 本次无法真正完成设备级补吊销，
     * **保留**待补标记并回告 `degraded = true`，待 Worker 侧端点就绪后即可由本方法清位。
     * 未置标记 / 未配对 / 未连接时**不做无意义请求**，直接回告对应原因。
     */
    suspend fun retryPendingRevoke(): Map<String, Any?> = withContext(IO) {
        if (!appCtx.getPrefBoolean(PreferKey.publicWebRelayRevokePending, false)) {
            return@withContext mapOf("attempted" to false, "cleared" to false, "reason" to "no_pending_revoke")
        }
        val connected = RelayStateRepository.state.value is RelayConnectionState.Connected
        val paired = !appCtx.getPrefString(PreferKey.publicWebRelayDeviceHandle, null).isNullOrBlank()
        // 设备级吊销入口不存在 ⇒ 无法投递，保留标记（不臆造端点，不误清位）
        mapOf(
            "attempted" to true,
            "connected" to connected,
            "paired" to paired,
            "cleared" to false,
            "degraded" to true,
            "reason" to "device_revoke_endpoint_unavailable",
        )
    }

    // ------------------------------------------------------------------ 内部

    /** 断电前状态快照（不含任何秘钥 / 句柄明文，也不含令牌 —— 令牌属上层鉴权层，见 [shutdown]）。 */
    private fun snapshot(): Map<String, Any?> = mapOf(
        "webServiceRunning" to WebService.isRun,
        "relay" to relayStateLabel(),
        "relayEnabled" to appCtx.getPrefBoolean(PreferKey.publicWebRelayEnabled, false),
        "relayPaired" to !appCtx.getPrefString(PreferKey.publicWebRelayDeviceHandle, null).isNullOrBlank(),
        // 分享数无本机持久化 / 无 Worker 列举端点 ⇒ 无法给出确定值，如实回告 unknown
        "shareCountKnown" to false,
        "shareCount" to null,
        "revokePending" to appCtx.getPrefBoolean(PreferKey.publicWebRelayRevokePending, false),
    )

    /** 中继连接状态的**技术标签**（不含域名 / URL）。 */
    private fun relayStateLabel(): String = when (RelayStateRepository.state.value) {
        RelayConnectionState.Disabled -> "disabled"
        RelayConnectionState.UnsupportedPlatform -> "unsupported_platform"
        RelayConnectionState.WaitingForNetwork -> "waiting_for_network"
        is RelayConnectionState.Connecting -> "connecting"
        RelayConnectionState.Authenticating -> "authenticating"
        is RelayConnectionState.Connected -> "connected"
        is RelayConnectionState.TestSucceeded -> "test_succeeded"
        is RelayConnectionState.Reconnecting -> "reconnecting"
        is RelayConnectionState.ConfigurationError -> "configuration_error"
        is RelayConnectionState.Failed -> "failed"
    }

    /**
     * 中继在线吊销：逐分享 + 设备级（设备级缺端点 ⇒ 降级并置待补标记）。
     *
     * 返回逐项回执；`paired = false` 时不做任何网络请求（无本机句柄 = 未配对）。
     */
    private fun revokeRelayRemote(): Map<String, Any?> {
        val config = RelayConfig.load(appCtx).getOrElse {
            return mapOf(
                "paired" to false,
                "sharesRevoked" to 0,
                "deviceRevoked" to false,
                "degraded" to false,
                "reason" to "relay_config_unavailable",
            )
        }
        if (config.deviceHandle.isNullOrBlank()) {
            config.identity.secret.fill(0)
            return mapOf(
                "paired" to false,
                "sharesRevoked" to 0,
                "deviceRevoked" to false,
                "degraded" to false,
                "reason" to "not_paired",
            )
        }

        // 本机无持久化分享清单，Worker 亦无列举端点 ⇒ 只能对已知句柄吊销（当前恒为空）
        val shareIds: List<String> = emptyList()
        var sharesRevoked = 0
        runCatching {
            RelayControlClient(config).let { client ->
                try {
                    shareIds.forEach { id ->
                        runCatching { client.revokeShare(id) }.onSuccess { sharesRevoked++ }
                    }
                } finally {
                    client.close() // 擦除本机身份秘钥
                }
            }
        }.onFailure { AppLog.put("RelayKernel.revokeRelayRemote: offline or endpoint error", it) }

        // 设备级吊销端点未就绪 ⇒ 置待补标记（tasks 2.1.5 降级路径）
        appCtx.putPrefBoolean(PreferKey.publicWebRelayRevokePending, true)
        return mapOf(
            "paired" to true,
            "shareIdsKnown" to shareIds.isNotEmpty(),
            "sharesRevoked" to sharesRevoked,
            "deviceRevoked" to false,
            "degraded" to true,
            "reason" to "device_revoke_endpoint_unavailable",
        )
    }
}