package io.legado.app.lib.cronet

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.DohDns
import io.legado.app.utils.printOnDebug
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

@Keep
@Suppress("unused")
class CronetInterceptor(private val cookieJar: CookieJar) : Interceptor {

    companion object {
        // app-stability-round2 P2-1: 运行时降级机制
        // 根因：3287 次 protocol=unknown httpCode=-1（QUIC/HTTP3 协议协商未完成，疑似 UDP 被网络阻断）
        // 现有 catch 回退 OkHttp 保证功能可用，但每次请求都走 Cronet 失败再回退，浪费无效往返 + 日志噪音
        // 方案：连续协议错误达阈值后，本次会话内降级到 OkHttp，减少无效 Cronet 尝试
        private const val DEGRADE_THRESHOLD = 5
        @Volatile private var protocolErrorCount = 0
        @Volatile private var degradedForSession = false

        // T4.3: 启动宽限期——类加载（≈App 启动后首次网络请求）后 300ms 内的协议错误不累计降级计数
        // 根因：启动初期 Cronet 引擎冷启动未完成，失败被累计导致误降级（日志实证）
        private val classLoadTimeMs = System.currentTimeMillis()
        private const val STARTUP_GRACE_MS = 300L

        // T4.3: 恢复探测（熔断器 half-open 模式）——降级后每 5 分钟放行一次真实请求走 Cronet
        // 探测成功自动切回 Cronet（Cronet 优势不丧失）；探测失败刷新计时，下一个 5 分钟再探测
        // 工程折中：复用真实请求做探测，无后台定时器/无额外流量/无定时器泄漏风险
        @Volatile private var degradedTimeMs = 0L
        private const val RECOVERY_PROBE_INTERVAL_MS = 5 * 60 * 1000L

        // N-P1-1: 恢复迟滞——连续 2 次探测成功才切回（原一次成功即切回，弱网抖动 2 分钟 6 轮乒乓实证）
        // 连续成功门槛：失败即清零；最短降级保持由 RECOVERY_PROBE_INTERVAL_MS(5min) ≥ 60s 满足
        private const val RECOVERY_SUCCESS_THRESHOLD = 2
        @Volatile private var recoverySuccessCount = 0
        // N-P1-1: 探测请求超时收紧 ≤3s，避免 Cronet 卡死时探测阻塞正常请求过久
        private const val RECOVERY_PROBE_TIMEOUT_MS = 3000

        // BUG6-V2 fix: 恢复后震荡抑制——切回 Cronet 后短时间内再次降级，说明 Cronet 仍不稳定
        // 方案：记录最近一次切回 Cronet 的时间，如果 30 秒内再次降级，延长降级间隔（5min→15min）
        // 铁证：真机日志显示 5 轮"降级→恢复探测→切回→又降级"震荡，根因是部分 host 可达部分不可达
        @Volatile private var lastRecoveryTimeMs = 0L
        private const val UNSTABLE_RECOVERY_WINDOW_MS = 30_000L // 30秒内再次降级视为震荡
        private const val EXTENDED_DEGRADE_INTERVAL_MS = 15 * 60 * 1000L // 震荡后延长到15分钟

        // P1-2（2026-07-31）：HTTP/2 协议错误降级优化
        // 根因：HTTP/2 协议错误（ERR_HTTP2_PROTOCOL_ERROR）通常是服务端 HTTP/2 实现问题，
        //   非 Cronet 本身故障，1 分钟后重试可能已恢复（服务端重启/负载均衡切换）
        // 方案：HTTP/2 协议错误降级时长从 5 分钟缩短到 1 分钟，其他协议错误保持 5 分钟
        private const val HTTP2_PROTOCOL_ERROR_DEGRADE_INTERVAL_MS = 60 * 1000L // 1 分钟

        // 日志去重：相同错误消息 60 秒内只记一次，避免高频失败刷屏
        @Volatile private var lastLoggedError: String? = null
        @Volatile private var lastLoggedErrorTime = 0L
        private const val LOG_DEDUP_INTERVAL_MS = 60_000L

        // V3-FR-6: 独立常量，仅用于恢复探测触发检查（L96 currentIntervalMs 计算）
        // 原因：RECOVERY_PROBE_INTERVAL_MS 被 L96/L239/L261/L277 共4处使用
        // 修改会影响所有非HTTP/2、非震荡的降级时长，弱网下加剧乒乓
        // 方案：新增独立常量仅用于恢复探测触发检查，保持 RECOVERY_PROBE_INTERVAL_MS 不变
        private const val RECOVERY_PROBE_CHECK_INTERVAL_MS = 3 * 60 * 1000L  // 3分钟

        // V3-FR-2: 证书错误单独去重状态（不与协议错误共享 lastLoggedError）
        // 原因：证书错误降级 OkHttp 后不累计降级计数，日志频率可能与协议错误不同
        @Volatile private var lastCertError: String? = null
        @Volatile private var lastCertErrorTime = 0L

        // BUG6-V2 fix: 记录最近一次协议错误对应的 host（路径模式化存储，仅保留域名哈希前缀）
        // 恢复探测时优先放行该 host 的请求，避免可达 host 探测成功但失败 host 仍不可达导致震荡
        @Volatile private var lastFailedHostHint: String? = null

        // sniff-result-pipeline-fix FR-4: lastFailedHostHint 超时清除
        // 根因：lastFailedHostHint 对应 host 长时间无请求时，探测永远不触发，降级状态持续
        // 铁证：263 次"探测跳过非失败 host"日志，全部针对同一对 host，10 秒内重复 15+ 次
        // 方案：hint 赋值 5 分钟后自动清除，允许任意 host 探测
        @Volatile private var lastFailedHostHintTimeMs = 0L
        private const val HINT_TIMEOUT_MS = 5 * 60 * 1000L  // 5 分钟超时

        /**
         * V3-FR-2: 证书错误判定（前缀匹配，覆盖20+错误码）
         *
         * 覆盖范围：
         * - ERR_CERT_AUTHORITY_INVALID/ERR_CERT_COMMON_NAME_INVALID/ERR_CERT_DATE_INVALID 等
         * - ERR_SSL_PROTOCOL_ERROR/ERR_SSL_DECRYPT_ERROR 等
         *
         * 注意：此分支必须在 isProtocolError/isHttp2ProtocolError 判定之前插入并 return
         * 原因：ERR_SSL_PROTOCOL_ERROR 含 PROTOCOL_ERROR 会匹配 isHttp2ProtocolError
         */
        private fun isCertificateError(errorMsg: String): Boolean =
            errorMsg.contains("ERR_CERT_", true) || errorMsg.contains("ERR_SSL_", true)

        /**
         * V3-FR-3: NAME_NOT_RESOLVED 判定（DoH 失败导致，非 Cronet 问题）
         *
         * 此类错误降级 OkHttp 也会因相同 DNS 失败，不累计降级计数
         * 同时清理 DoH 负缓存 + 熔断状态，下次请求重新尝试 DoH
         */
        private fun isNameNotResolvedError(errorMsg: String): Boolean =
            errorMsg.contains("ERR_NAME_NOT_RESOLVED", true)

        /**
         * V3-FR-2: 证书错误日志去重（60s 内相同错误只记一次）
         *
         * 使用 lastCertError/lastCertErrorTime（不与协议错误共享 lastLoggedError）
         * 日志明确标识"证书错误降级 OkHttp（复用 SSLHelper 信任所有证书），不累计降级计数"
         */
        private fun logCertError(errMsg: String) {
            val now = System.currentTimeMillis()
            val errorKey = "CERT:${errMsg.take(50)}"
            if (errorKey != lastCertError || now - lastCertErrorTime > LOG_DEDUP_INTERVAL_MS) {
                AppLog.put("Cronet 证书错误, 降级 OkHttp (复用 SSLHelper 信任所有证书), 不累计降级计数: error=${errMsg.take(80)}")
                lastCertError = errorKey
                lastCertErrorTime = now
            }
        }

        /**
         * V3-FR-3: NAME_NOT_RESOLVED 日志去重（60s 内相同 host 只记一次）
         *
         * 复用 lastLoggedError/lastLoggedErrorTime（与协议错误共享，因为 NAME_NOT_RESOLVED 不累计降级计数）
         * 日志明确标识"DoH failure, not Cronet issue"
         */
        private fun logNameNotResolved(errMsg: String, host: String) {
            val now = System.currentTimeMillis()
            val errorKey = "NAME_NOT_RESOLVED:$host"
            if (errorKey != lastLoggedError || now - lastLoggedErrorTime > LOG_DEDUP_INTERVAL_MS) {
                AppLog.put("Cronet NAME_NOT_RESOLVED (DoH failure, not Cronet issue), 降级 OkHttp, 不累计降级计数: host=${host.take(3)}***, error=${errMsg.take(60)}")
                lastLoggedError = errorKey
                lastLoggedErrorTime = now
            }
        }

        /**
         * 降级状态查询（供 HttpHelper 等外部诊断使用）
         */
        fun isDegraded(): Boolean = degradedForSession
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.call().isCanceled()) {
            throw IOException("Canceled")
        }
        val original: Request = chain.request()
        // P2-1: 运行时降级——会话内连续协议错误达阈值后直接走 OkHttp，避免无效 Cronet 往返
        // N-P1-1: 标记本次是否为恢复探测请求（探测时超时收紧 ≤3s + 连续成功门槛计数）
        var isRecoveryProbe = false
        if (degradedForSession) {
            // T4.3: 半开恢复探测——降级满间隔时间放行一次真实请求走 Cronet，成功自动恢复
            // BUG6-V2 fix: 间隔时间动态计算——震荡抑制后延长到 15 分钟，正常 5 分钟
            val elapsed = System.currentTimeMillis() - degradedTimeMs
            // 判断是否处于震荡抑制期：如果有 lastRecoveryTimeMs 且最近降级与恢复间隔很短
            val currentIntervalMs = if (lastRecoveryTimeMs > 0
                && (degradedTimeMs - lastRecoveryTimeMs) < UNSTABLE_RECOVERY_WINDOW_MS
            ) {
                EXTENDED_DEGRADE_INTERVAL_MS
            } else {
                // V3-FR-6: 用独立常量 RECOVERY_PROBE_CHECK_INTERVAL_MS（3分钟）
                // 原因：RECOVERY_PROBE_INTERVAL_MS 被 L239/L261/L277 共3处降级时长计算使用
                // 修改会影响所有非HTTP/2、非震荡的降级时长，弱网下加剧乒乓
                RECOVERY_PROBE_CHECK_INTERVAL_MS
            }
            if (elapsed < currentIntervalMs) {
                return chain.proceed(original)
            }
            // BUG6-V2: 如果有失败 host 提示，优先放行该 host 的请求（避免可达 host 探测成功但失败 host 仍不可达）
            val requestHost = original.url.host
            // sniff-result-pipeline-fix FR-4: hint 超时清除
            // 根因：hint 对应 host 长时间无请求时，探测永远不触发，降级状态持续
            if (lastFailedHostHint != null && System.currentTimeMillis() - lastFailedHostHintTimeMs > HINT_TIMEOUT_MS) {
                AppLog.put("Cronet hint 超时清除 (${HINT_TIMEOUT_MS / 60000} 分钟), 放行任意 host 探测")
                lastFailedHostHint = null
                lastFailedHostHintTimeMs = 0L
            }
            val hint = lastFailedHostHint
            if (hint != null && requestHost != hint) {
                // 当前请求不是失败 host，跳过探测走 OkHttp，等待失败 host 的请求到来
                AppLog.putDebug("Cronet 探测跳过非失败host: requestHost=${requestHost.take(3)}***, hintHost=${hint.take(3)}***")
                return chain.proceed(original)
            }
            isRecoveryProbe = true
            AppLog.put("Cronet 降级满 ${currentIntervalMs / 60000} 分钟, 放行失败host请求探测恢复")
        }
        //Cronet未初始化（try-catch 防御 lazy 初始化异常逃逸）
        //铁证：真机日志显示 cronetEngine lazy 初始化抛出 RuntimeException 后直接逃逸到 intercept，
        //  原代码 L54 不在 try 块内，异常未被捕获。即使 CronetHelper.kt 已扩展 try-catch，
        //  此处仍保留防御性 try-catch，确保任何情况下都不会因 lazy 异常导致 intercept 抛出
        val engine = try {
            if (!CronetLoader.install()) null
            else cronetEngine
        } catch (e: Throwable) {
            AppLog.put("getCronetEngine触发异常", e)
            null
        }
        if (engine == null) {
            return chain.proceed(original)
        }
        val cronetException: Exception
        try {
            val builder: Request.Builder = original.newBuilder()
            //移除Keep-Alive,手动设置会导致400 BadRequest
            builder.removeHeader("Keep-Alive")
            builder.removeHeader("Accept-Encoding")

            // https://github.com/gedoor/legado/issues/5025#issuecomment-2851156500
            if (!original.isHttps &&
                original.header("User-Agent")?.startsWith("Mozilla", true) == true
            ) {
                val referer = original.header("Referer")
                if (referer != null && referer.startsWith("https:", true)) {
                    builder.header("Referer", "http" + referer.substring(5))
                }
            }

            var newReq = builder.build()

            if (newReq.header(cookieJarHeader) != null) {
                newReq = CookieManager.loadRequest(newReq)
            }

            // N-P1-1: 探测请求超时收紧 ≤3s（避免 Cronet 卡死时探测阻塞正常请求过久）；正常请求用原超时
            val readTimeout = if (isRecoveryProbe) {
                minOf(chain.readTimeoutMillis(), RECOVERY_PROBE_TIMEOUT_MS)
            } else {
                chain.readTimeoutMillis()
            }
            val response = proceedWithCronet(newReq, chain.call(), readTimeout)!!
            // N-P1-1: 连续成功门槛——连续 2 次探测成功才切回（原一次成功即切回，弱网抖动乒乓实证）
            if (degradedForSession) {
                recoverySuccessCount++
                if (recoverySuccessCount >= RECOVERY_SUCCESS_THRESHOLD) {
                    degradedForSession = false
                    protocolErrorCount = 0
                    recoverySuccessCount = 0
                    lastRecoveryTimeMs = System.currentTimeMillis() // BUG6-V2: 记录切回时间
                    lastFailedHostHint = null // BUG6-V2: 切回后清除失败 host 提示
                    AppLog.put("Cronet 恢复探测连续成功 $RECOVERY_SUCCESS_THRESHOLD 次, 自动切回 Cronet")
                } else {
                    // 首次成功：保持降级态，刷新降级计时期待下次探测确认（防单点抖动误判恢复）
                    degradedTimeMs = System.currentTimeMillis()
                    AppLog.put("Cronet 恢复探测成功 $recoverySuccessCount/$RECOVERY_SUCCESS_THRESHOLD, 待再次确认")
                }
            }
            return response
        } catch (e: Exception) {
            cronetException = e
            //不能抛出错误,抛出错误会导致应用崩溃
            //遇到Cronet处理有问题时的情况，如证书过期等等，回退到okhttp处理
            // app-stability-round2 P2-1: 协议错误计数 + 运行时降级 + 日志去重
            val errMsg = e.message.toString()

            // V-004-P1-2: Request Canceled 是用户切换视频时的正常取消，降级为 DEBUG 日志
            // 根因：004 日志显示用户切换视频时 Cronet 请求被取消，日志输出 ERROR 级别干扰分析
            // 方案：识别 Canceled 关键词，跳过 printOnDebug + 协议错误计数，输出 DEBUG 级别日志
            val isCanceled = errMsg.contains("Canceled", true) || errMsg.contains("Cancelled", true)
            if (isCanceled) {
                AppLog.putDebug("Cronet request canceled (normal): ${errMsg.take(60)}")
                // Canceled 是正常取消，不累计协议错误计数，不调用 printOnDebug
                try {
                    return chain.proceed(original)
                } catch (e2: Exception) {
                    e2.addSuppressed(cronetException)
                    throw e2
                }
            }

            // V3-FR-2: 证书错误降级 OkHttp（复用 SSLHelper 信任所有证书），不累计降级计数
            // 必须在 isProtocolError 之前插入并 return（避免 ERR_SSL_PROTOCOL_ERROR 误匹配 isHttp2ProtocolError）
            // OkHttp 的 okHttpClient 已配置 SSLHelper.unsafeSSLSocketFactory + unsafeTrustManager + unsafeHostnameVerifier
            if (isCertificateError(errMsg)) {
                logCertError(errMsg)
                try {
                    return chain.proceed(original)
                } catch (e2: Exception) {
                    e2.addSuppressed(cronetException)
                    throw e2
                }
            }

            // V3-FR-3: NAME_NOT_RESOLVED 降级 OkHttp（DoH 失败导致，非 Cronet 问题），不累计降级计数
            // 同时清理 DoH 负缓存 + 熔断状态，下次请求重新尝试 DoH
            if (isNameNotResolvedError(errMsg)) {
                val failedHost = original.url.host
                logNameNotResolved(errMsg, failedHost)
                DohDns.clearNegativeCache(failedHost)
                try {
                    return chain.proceed(original)
                } catch (e2: Exception) {
                    e2.addSuppressed(cronetException)
                    throw e2
                }
            }

            // 协议错误判断：扩展覆盖连接层失败（protocol=unknown 对应 QUIC/连接被拒/Socket 异常）
            val isProtocolError = errMsg.contains("PROTOCOL_ERROR", true)
                || errMsg.contains("StreamReset", true)
                || errMsg.contains("System error", true)
                || errMsg.contains("ERR_QUIC", true)
                || errMsg.contains("ERR_CONNECTION", true)
                || errMsg.contains("ERR_SOCKET", true)
            // P1-2（2026-07-31）：细分错误类型，差异化降级策略
            // - HTTP/2 协议错误：服务端 HTTP/2 实现问题，1 分钟后重试可能已恢复
            // - 连接拒绝错误：可能是 DoH 失败导致 DNS 解析到不可达 IP，降级无意义（OkHttp 也会失败）
            val isHttp2ProtocolError = errMsg.contains("ERR_HTTP2_PROTOCOL_ERROR", true)
                || errMsg.contains("PROTOCOL_ERROR", true)
            val isConnectionRefused = errMsg.contains("ERR_CONNECTION_REFUSED", true)
            // V3-FR-2: 证书错误已在 isCertificateError 分支前置 return，不会到达此处
            // 原 ERR_CERT_/ERR_SSL_ 跳过 printOnDebug 判断已删除（冗余代码）
            e.printOnDebug()
            // P1-2: 连接拒绝错误不累计降级计数（DoH 失败导致，降级 OkHttp 也会因相同 DNS 失败）
            if (isConnectionRefused) {
                AppLog.putDebug("Cronet 连接拒绝(可能是DoH失败), 不累计降级: error=${errMsg.take(80)}")
            } else if (isProtocolError) {
                val now = System.currentTimeMillis()
                // BUG6-V2: 记录失败 host 提示，恢复探测时优先放行该 host 的请求
                val failedHost = original.url.host
                if (failedHost != lastFailedHostHint) {
                    lastFailedHostHint = failedHost
                    // sniff-result-pipeline-fix FR-4: 记录赋值时间戳
                    lastFailedHostHintTimeMs = now
                }
                // T4.3: 启动 300ms 宽限期内的协议错误不累计降级计数（冷启动失败属正常，日志实证误降级）
                val inStartupGrace = now - classLoadTimeMs < STARTUP_GRACE_MS
                if (inStartupGrace) {
                    AppLog.put("Cronet 启动宽限期内协议错误, 不累计: error=${errMsg.take(80)}")
                } else {
                    protocolErrorCount++
                    // 日志去重：相同错误 60 秒内只记一次，避免高频失败刷屏
                    if (errMsg != lastLoggedError || now - lastLoggedErrorTime > LOG_DEDUP_INTERVAL_MS) {
                        AppLog.put("Cronet 协议错误，回退到 OkHttp: error=${errMsg.take(80)} (累计 $protocolErrorCount 次)")
                        lastLoggedError = errMsg
                        lastLoggedErrorTime = now
                    }
                    if (degradedForSession) {
                        // T4.3: 恢复探测失败——刷新降级计时，下一个 5 分钟再探测（half-open → open）
                        // N-P1-1: 失败即清零连续成功计数（重新累计 2 次才切回）
                        // P1-2: HTTP/2 协议错误用 1 分钟降级时长，其他用 5 分钟
                        val recoveryIntervalMs = if (isHttp2ProtocolError) HTTP2_PROTOCOL_ERROR_DEGRADE_INTERVAL_MS else RECOVERY_PROBE_INTERVAL_MS
                        degradedTimeMs = now
                        recoverySuccessCount = 0
                        AppLog.put("Cronet 恢复探测失败, 继续降级 OkHttp (${recoveryIntervalMs / 60000} 分钟后再探测, isHttp2=$isHttp2ProtocolError)")
                    } else if (protocolErrorCount >= DEGRADE_THRESHOLD) {
                        // 达阈值降级：避免后续请求继续无效 Cronet 往返
                        degradedForSession = true
                        // BUG6-V2 fix: 震荡抑制——如果距离上次切回 Cronet 不到 30 秒就再次降级，
                        // 说明 Cronet 仍不稳定（部分 host 可达部分不可达），延长降级间隔到 15 分钟
                        val now = System.currentTimeMillis()
                        val isUnstableRecovery = lastRecoveryTimeMs > 0 && (now - lastRecoveryTimeMs) < UNSTABLE_RECOVERY_WINDOW_MS
                        // P1-2: 降级时长按错误类型区分（HTTP/2 协议错误 1 分钟，其他 5 分钟，震荡 15 分钟）
                        val intervalMs = when {
                            isUnstableRecovery -> {
                                AppLog.put("Cronet 恢复后 ${UNSTABLE_RECOVERY_WINDOW_MS / 1000} 秒内再次降级, 延长降级间隔到 ${EXTENDED_DEGRADE_INTERVAL_MS / 60000} 分钟 (震荡抑制)")
                                EXTENDED_DEGRADE_INTERVAL_MS
                            }
                            isHttp2ProtocolError -> {
                                AppLog.put("Cronet HTTP/2 协议错误达 $DEGRADE_THRESHOLD 次, 降级到 OkHttp (${HTTP2_PROTOCOL_ERROR_DEGRADE_INTERVAL_MS / 1000} 秒后自动探测恢复)")
                                HTTP2_PROTOCOL_ERROR_DEGRADE_INTERVAL_MS
                            }
                            else -> {
                                RECOVERY_PROBE_INTERVAL_MS
                            }
                        }
                        degradedTimeMs = now
                        lastRecoveryTimeMs = 0 // 清除恢复时间，避免误判
                        if (!isUnstableRecovery && !isHttp2ProtocolError) {
                            AppLog.put("Cronet 连续协议错误达 $DEGRADE_THRESHOLD 次, 降级到 OkHttp (${intervalMs / 60000} 分钟后自动探测恢复)")
                        }
                    }
                }
            } else if (degradedForSession) {
                // T4.3: 非协议错误（如目标服务器故障）的探测失败同样刷新降级计时
                // 否则降级态下每个请求都立即重探测，白白增加一次 Cronet 尝试延迟
                // N-P1-1: 失败即清零连续成功计数（重新累计 2 次才切回）
                degradedTimeMs = System.currentTimeMillis()
                recoverySuccessCount = 0
                AppLog.put("Cronet 恢复探测失败(非协议错误), ${RECOVERY_PROBE_INTERVAL_MS / 60000} 分钟后再探测")
            }
        }
        try {
            return chain.proceed(original)
        } catch (e: Exception) {
            e.addSuppressed(cronetException)
            throw e
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    @Throws(IOException::class)
    private fun proceedWithCronet(request: Request, call: Call, readTimeoutMillis: Int): Response? {
        val callBack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NewCallBack(request, call, readTimeoutMillis)
        } else {
            OldCallback(request, call, readTimeoutMillis)
        }
        buildRequest(request, callBack)?.let {
            return callBack.waitForDone(it)
        }
        return null
    }


    /** Returns a 'Cookie' HTTP request header with all cookies, like `a=b; c=d`. */
    private fun getCookie(url: HttpUrl): String = buildString {
        val cookies = cookieJar.loadForRequest(url)
        cookies.forEachIndexed { index, cookie ->
            if (index > 0) append("; ")
            append(cookie.name).append('=').append(cookie.value)
        }
    }

}
