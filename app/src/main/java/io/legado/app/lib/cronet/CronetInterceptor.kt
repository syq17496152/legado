package io.legado.app.lib.cronet

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieManager.cookieJarHeader
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

        // 日志去重：相同错误消息 60 秒内只记一次，避免高频失败刷屏
        @Volatile private var lastLoggedError: String? = null
        @Volatile private var lastLoggedErrorTime = 0L
        private const val LOG_DEDUP_INTERVAL_MS = 60_000L

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
            // T4.3: 半开恢复探测——降级满 5 分钟放行一次真实请求走 Cronet，成功自动恢复
            val elapsed = System.currentTimeMillis() - degradedTimeMs
            if (elapsed < RECOVERY_PROBE_INTERVAL_MS) {
                return chain.proceed(original)
            }
            isRecoveryProbe = true
            AppLog.put("Cronet 降级满 ${RECOVERY_PROBE_INTERVAL_MS / 60000} 分钟, 放行一次请求探测恢复")
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

            // 协议错误判断：扩展覆盖连接层失败（protocol=unknown 对应 QUIC/连接被拒/Socket 异常）
            val isProtocolError = errMsg.contains("PROTOCOL_ERROR", true)
                || errMsg.contains("StreamReset", true)
                || errMsg.contains("System error", true)
                || errMsg.contains("ERR_QUIC", true)
                || errMsg.contains("ERR_CONNECTION", true)
                || errMsg.contains("ERR_SOCKET", true)
            if (!errMsg.contains("ERR_CERT_", true)
                && !errMsg.contains("ERR_SSL_", true)
            ) {
                e.printOnDebug()
            }
            if (isProtocolError) {
                val now = System.currentTimeMillis()
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
                        degradedTimeMs = now
                        recoverySuccessCount = 0
                        AppLog.put("Cronet 恢复探测失败, 继续降级 OkHttp (${RECOVERY_PROBE_INTERVAL_MS / 60000} 分钟后再探测)")
                    } else if (protocolErrorCount >= DEGRADE_THRESHOLD) {
                        // 达阈值降级：避免后续请求继续无效 Cronet 往返
                        degradedForSession = true
                        degradedTimeMs = now
                        AppLog.put("Cronet 连续协议错误达 $DEGRADE_THRESHOLD 次, 降级到 OkHttp (${RECOVERY_PROBE_INTERVAL_MS / 60000} 分钟后自动探测恢复)")
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
