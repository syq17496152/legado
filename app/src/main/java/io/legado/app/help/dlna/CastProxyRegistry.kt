package io.legado.app.help.dlna

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * add-dlna-cast：代理条目背后的真实数据源（design AD-03 三态投递的落点）。
 */
sealed interface CastSource {
    /** 回给渲染端的 MIME */
    val mime: String

    /**
     * 带鉴权头的 HTTP 上游。
     *
     * @property headers 会话 headers（Referer/Cookie/UA…）——**渲染端带不了，只能由代理代传**
     */
    data class Http(
        override val mime: String,
        val url: String,
        val headers: Map<String, String>
    ) : CastSource

    /** 本地已下载视频（`file://`），直接以文件流响应 */
    data class LocalFile(
        override val mime: String,
        val path: String
    ) : CastSource
}

/**
 * add-dlna-cast：一次投屏会话的代理注册表（design AD-08 白名单模型）。
 *
 * 安全模型：代理**只服务已注册条目**，绝不接受"URL 在请求参数里"的形式 ——
 * 否则手机就变成了同网段任何人可用的开放代理（盗用流量 + 暴露面放大）。
 * 请求方拿到一个 32 位随机 token，token 之外没有任何可用凭据；
 * 会话结束 [CastProxyRegistry.clear] 后，残留请求一律 404 且不发起上游请求。
 *
 * HLS 的分片是**在重写清单时惰性登记**的：直播清单每次刷新都会出现新分片，
 * 预先登记不可能，而"登记发生在重写时"天然覆盖新出现的 URI。
 * 同时这也解决了"把原 URI base64 塞进路径导致 URL 长度爆炸"的问题（红队 P2-2）——
 * 输出只需一个短 key（`s` + 递增序号）。
 *
 * 线程安全：NanoHTTPD 每请求一线程，HLS 分片并发拉取是常态，所有映射用
 * `ConcurrentHashMap`（红队第 2 轮 P1）。
 */
class CastProxySession internal constructor(
    val token: String,
    baseName: String,
    val baseSource: CastSource
) {
    private val entries = ConcurrentHashMap<String, CastSource>()
    private val shortIdCounter = AtomicInteger(0)

    /** 基础条目的路径 key（投递给渲染端的那个地址） */
    val baseKey: String = CastProxyRegistry.sanitizeName(baseName)

    init {
        entries[baseKey] = baseSource
    }

    /**
     * 惰性登记一个上游 URI，返回短 key。
     *
     * 同一 URI 重复登记会命中已有 key（用 `putIfAbsent` 语义避免长播放列表里
     * 同一分片被反复登记导致表无限增长）。
     */
    fun registerShort(source: CastSource): String {
        val key = "${DlnaConstants.PROXY_SHORT_ID_SEGMENT}${shortIdCounter.incrementAndGet()}"
        entries[key] = source
        return key
    }

    /** 按路径 key 取源；未登记返回 null（调用方回 404，且不得发起上游请求） */
    fun lookup(key: String?): CastSource? {
        if (key.isNullOrBlank()) return null
        return entries[key]
    }

    /** 当前登记条目数（诊断/日志用） */
    val size: Int get() = entries.size
}

/**
 * add-dlna-cast：会话级注册表容器。
 *
 * 只持有**当前会话**（单会话模型，AD-05/REQ-05 已明确不支持多设备同时投屏），
 * 因此不需要按设备分桶；多会话会引入状态机爆炸而无实际收益。
 */
object CastProxyRegistry {

    private val random = SecureRandom()
    private val tokenAlphabet = "abcdefghijklmnopqrstuvwxyz0123456789"

    @Volatile
    private var current: CastProxySession? = null

    /** 创建新会话（会覆盖上一会话，调用方负责先 teardown） */
    fun createSession(baseName: String, source: CastSource): CastProxySession {
        val session = CastProxySession(newToken(), baseName, source)
        current = session
        return session
    }

    fun find(token: String?): CastProxySession? {
        if (token.isNullOrBlank()) return null
        return current?.takeIf { it.token == token }
    }

    /** 会话结束时清空 —— 之后所有残留请求都拿不到条目 */
    fun clear() {
        current = null
    }

    fun newToken(): String {
        val sb = StringBuilder(DlnaConstants.PROXY_TOKEN_LENGTH)
        repeat(DlnaConstants.PROXY_TOKEN_LENGTH) {
            sb.append(tokenAlphabet[random.nextInt(tokenAlphabet.length)])
        }
        return sb.toString()
    }

    /**
     * 路径段卫生处理（红队第 2 轮 P1）。
     *
     * 只取最后一段、只保留安全字符、限长 —— 该值**不参与任何上游地址拼接**，
     * 纯粹用于让投递地址看起来像个文件名（部分渲染端对无扩展名地址不友好）。
     */
    fun sanitizeName(raw: String?): String {
        if (raw.isNullOrBlank()) return "media"
        val last = raw.substringAfterLast('/').substringAfterLast('\\')
        // 路径穿越字符一律丢弃
        val cleaned = last.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            .removePrefix(".")
        val truncated = cleaned.take(DlnaConstants.PROXY_NAME_MAX_LENGTH)
        return truncated.ifBlank { "media" }
    }
}
