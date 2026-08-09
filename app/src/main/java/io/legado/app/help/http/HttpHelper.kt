package io.legado.app.help.http

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.progress.ProgressManager.LISTENER
import io.legado.app.help.glide.progress.ProgressResponseBody
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.model.ReadManga
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import splitties.init.appCtx
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 代理 OkHttpClient 缓存（LRU + 上限 20）
 *
 * 原实现用 ConcurrentHashMap 无上限，长跑会无限增长导致内存泄漏
 * （每个 OkHttpClient 含独立连接池/调度器，泄漏代价高）
 * 改用 LinkedHashMap(accessOrder=true) + removeEldestEntry 实现 LRU 淘汰
 *
 * 已知上限：synchronized 粒度为整个 map，代理书源访问频率不高，性能可接受 | 升级路径：如需更高并发可改用 java.util.concurrent.ConcurrentLinkedHashMap
 */
private const val PROXY_CLIENT_CACHE_MAX_SIZE = 20

private val proxyClientLock = Any()

private val proxyClientCache: java.util.LinkedHashMap<String, OkHttpClient> = object : java.util.LinkedHashMap<String, OkHttpClient>(
    16, 0.75f, true  // accessOrder=true：最近访问的放末尾，淘汰最久未访问
) {
    override fun removeEldestEntry(eldest: Map.Entry<String, OkHttpClient>?): Boolean {
        return size > PROXY_CLIENT_CACHE_MAX_SIZE
    }
}

val cookieJar by lazy {
    object : CookieJar {

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return emptyList()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            //临时保存 书源启用cookie选项再添加到数据库
            val cookieBuilder = StringBuilder()
            cookies.forEachIndexed { index, cookie ->
                if (index > 0) cookieBuilder.append(";")
                cookieBuilder.append(cookie.name).append('=').append(cookie.value)
            }
            val domain = NetworkUtils.getSubDomain(url.toString())
            CacheManager.putMemory("${domain}_cookieJar", cookieBuilder.toString())
        }

    }
}

val okHttpClient: OkHttpClient by lazy {
    val specs = arrayListOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.CLEARTEXT
    )

    // F-P1-D HTTP 响应缓存：复用重复请求结果，减少网络往返
    // 已知上限：50MB 磁盘缓存（OkHttp LRU 策略自动淘汰） | 升级路径：如磁盘紧张可降至 20MB
    val cacheDir = File(appCtx.cacheDir, "okhttp_cache").apply { mkdirs() }
    val httpCache = Cache(cacheDir, 50L * 1024 * 1024)

    val builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        //.cookieJar(cookieJar = cookieJar)
        .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
        .retryOnConnectionFailure(true)
        .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
        .connectionSpecs(specs)
        // 连接池调优：50 个空闲连接（默认 5），5 分钟保活
        // 提升多书源并发访问时的连接复用率，减少 TCP/TLS 握手开销
        // 派生客户端（okHttpClientManga / proxyClient）通过 newBuilder() 继承此连接池
        // 已知上限：50 个连接约 2.5MB 内存（每连接 ~50KB） | 升级路径：如内存紧张可降至 20
        .connectionPool(okhttp3.ConnectionPool(50, 5, TimeUnit.MINUTES))
        // HTTP 响应缓存：仅缓存 GET 请求且响应含 Cache-Control/Expires 的资源
        // 派生客户端（okHttpClientManga / proxyClient）通过 newBuilder() 继承此缓存
        .cache(httpCache)
        .followRedirects(true)
        .followSslRedirects(true)
        // FR-4: favicon.ico 缓存拦截器（必须放在拦截器链最前面，缓存命中直接返回）
        // 根因：137 次 favicon.ico 请求，每次 400-600ms，无缓存机制
        // 方案：内存 LruCache + 磁盘 24h + 并行请求合并
        .addInterceptor { chain ->
            val request = chain.request()
            // 仅缓存 GET /favicon.ico 请求
            if (request.method == "GET" && request.url.encodedPath == "/favicon.ico") {
                val host = request.url.host
                // 缓存命中直接返回
                FaviconCache.getCachedResponse(request)?.let { return@addInterceptor it }
                // 缓存未命中：放行请求，响应写入缓存（并行请求合并）
                synchronized(FaviconCache.getLock(host)) {
                    // 双重检查：等待锁期间可能已被其他请求缓存
                    FaviconCache.getCachedResponse(request)?.let { return@synchronized it }
                    val response = chain.proceed(request)
                    if (response.isSuccessful && response.body != null) {
                        try {
                            val bodyBytes = response.body!!.bytes()
                            FaviconCache.put(host, bodyBytes)
                            // 重新构建 Response（body 已消费，需用缓存数据重建）
                            return@synchronized response.newBuilder()
                                .body(bodyBytes.toResponseBody(response.body?.contentType()))
                                .build()
                        } catch (e: Exception) {
                            AppLog.putDebug("FaviconCache: cache write failed, host=${host.take(3)}***")
                        }
                    }
                    return@synchronized response
                }
            } else {
                chain.proceed(request)
            }
        }
        .addInterceptor(OkHttpExceptionInterceptor)
        // B4 网络日志（敏感头脱敏，开关 recordNetworkLog）
        .addInterceptor(NetworkLogInterceptor)
        // T4.2: 302 重定向缓存（避免重复请求重定向链，提高抓取成功率）
        .addInterceptor(RedirectCacheInterceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            if (request.header(AppConst.UA_NAME) == null) {
                builder.addHeader(AppConst.UA_NAME, AppConfig.userAgent)
            } else if (request.header(AppConst.UA_NAME) == "null") {
                builder.removeHeader(AppConst.UA_NAME)
            }
            builder.addHeader("Keep-Alive", "300")
            builder.addHeader("Connection", "Keep-Alive")
            builder.addHeader("Cache-Control", "no-cache")
            chain.proceed(builder.build())
        }
        .addNetworkInterceptor { chain ->
            var request = chain.request()
            val enableCookieJar = request.header(cookieJarHeader) != null

            if (enableCookieJar) {
                val requestBuilder = request.newBuilder()
                requestBuilder.removeHeader(cookieJarHeader)
                request = CookieManager.loadRequest(requestBuilder.build())
            }

            val networkResponse = chain.proceed(request)

            if (enableCookieJar) {
                CookieManager.saveResponse(networkResponse)
            }
            networkResponse
        }
    // P2-B 修复：始终使用 RetryableDns，提供重试 + 负缓存 + addressCache 优先
    // T4.1: 使用 DohDns 替代 RetryableDns，绕过本地 DNS 污染（SNI 阻断/本地 DNS 过滤）
    // 已知上限：DoH 服务器不可用时会回退到系统 DNS，不影响正常解析 | 升级路径：可根据用户反馈调整 DoH 服务器优先级
    builder.dns(DohDns)
    if (AppConfig.isCronet) {
        if (Cronet.loader?.install() == true) {
            Cronet.interceptor?.let {
                builder.addInterceptor(it)
            }
        } else {
            // P2-3.3: Cronet 加载失败，回退到 OkHttp
            AppLog.put("Cronet install failed, fallback to OkHttp")
        }
    }
    builder.addInterceptor(DecompressInterceptor)
    // precise-manage: 网址记录采集（开关 AppConfig.recordUrl）
    builder.addInterceptor(UrlRecordInterceptor)
    // sniff-result-pipeline-fix FR-3: HTTP/2 StreamReset 容错
    // 根因：OkHttp retryOnConnectionFailure(true) 对 HTTP/2 流重置无效
    // 服务端发送 RST_STREAM 帧 → OkHttp 抛 StreamResetException → 连接池连接未淘汰 → 下次复用仍失败
    // 方案：捕获 StreamResetException → 淘汰连接池连接 → 重试一次
    builder.addInterceptor(StreamResetRetryInterceptor)
    builder.build().apply {
        val okHttpName =
            OkHttpClient::class.java.name.removePrefix("okhttp3.").removeSuffix("Client")
        val executor = dispatcher.executorService as ThreadPoolExecutor
        val threadName = "$okHttpName Dispatcher"
        executor.threadFactory = ThreadFactory { runnable ->
            Thread(runnable, threadName).apply {
                isDaemon = false
                uncaughtExceptionHandler = OkhttpUncaughtExceptionHandler
            }
        }
    }
}

val okHttpClientManga by lazy {
    okHttpClient.newBuilder().run {
        val interceptors = interceptors()
        interceptors.add(1) { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val url = request.url.toString()
            response.newBuilder()
                .body(ProgressResponseBody(url, LISTENER, response.body))
                .build()
        }
        interceptors.add(1) { chain ->
            ReadManga.rateLimiter.withLimitBlocking {
                chain.proceed(chain.request())
            }
        }
        build()
    }
}

/**
 * FR-3: 视频流专用 OkHttpClient（强制 HTTP/1.1）
 *
 * 根因：ExoPlayerHelper L417/L740 Range 嗅探请求用 okHttpClient（含 CronetInterceptor），
 *   Cronet 引擎走 HTTP/2 可能触发 ERR_HTTP2_PROTOCOL_ERROR（日志铁证 3 次）
 *
 * 方案：新增 videoStreamClient 强制 HTTP/1.1，ExoPlayerHelper Range 嗅探改用此 client
 *   - 基于 okHttpClient.newBuilder() 继承所有拦截器（SSLHelper/DohDns/缓存等）
 *   - protocols=listOf(Protocol.HTTP_1_1) 强制 HTTP/1.1，规避 HTTP/2 协议错误
 *   - 仍含 CronetInterceptor：但 Cronet 失败会降级到 OkHttp（HTTP/1.1），不会触发 HTTP/2 错误
 *
 * 已知上限：HTTP/1.1 不支持多路复用，并发性能略低于 HTTP/2
 * 升级路径：如需 HTTP/2 可改为按 host 记忆协议错误，仅对失败 host 降级 HTTP/1.1
 */
val videoStreamClient: OkHttpClient by lazy {
    okHttpClient.newBuilder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()
}

/**
 * 缓存代理okHttp
 */
fun getProxyClient(proxy: String? = null): OkHttpClient {
    if (proxy.isNullOrBlank()) {
        return okHttpClient
    }
    synchronized(proxyClientLock) {
        proxyClientCache[proxy]?.let { return it }
        val r = Regex("(http|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?")
        val ms = r.findAll(proxy)
        val group = ms.first()
        var username = ""       //代理服务器验证用户名
        var password = ""       //代理服务器验证密码
        val type = if (group.groupValues[1] == "http") "http" else "socks"
        val host = group.groupValues[2]
        val port = group.groupValues[3].toInt()
        if (group.groupValues[4] != "") {
            username = group.groupValues[4].split("@")[1]
            password = group.groupValues[4].split("@")[2]
        }
        if (host != "") {
            val builder = okHttpClient.newBuilder()
            if (type == "http") {
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
            } else {
                builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
            }
            if (username != "" && password != "") {
                builder.proxyAuthenticator { _, response -> //设置代理服务器账号密码
                    val credential: String = Credentials.basic(username, password)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
            val proxyClient = builder.build()
            proxyClientCache[proxy] = proxyClient  // 写入触发 removeEldestEntry 自动 LRU 淘汰
            return proxyClient
        }
        return okHttpClient
    }
}

/**
 * P2-B 修复：可重试的 DNS 解析器
 *
 * 根因：Dns.SYSTEM 无重试无负缓存，特定书源域名解析失败时直接抛 UnknownHostException
 * 证据：appLog 中 343 条 UnknownHostException（部分书源域名 252 次、96 次重复失败）
 * 方案：addressCache 优先 + 重试2次 + 负缓存60秒 + 失败日志
 * 影响范围：全局 OkHttp 请求的 DNS 解析
 * 已知上限：负缓存 60 秒内相同域名直接失败，可能延迟恢复 | 升级路径：可配置 TTL
 */
private object RetryableDns : Dns {
    private const val MAX_RETRY = 2
    private const val NEGATIVE_CACHE_TTL_MS = 60_000L
    private val negativeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun lookup(hostname: String): List<InetAddress> {
        // 负缓存检查：失败过的域名 60 秒内直接抛异常，避免反复 DNS 查询
        val expireAt = negativeCache[hostname]
        if (expireAt != null && System.currentTimeMillis() < expireAt) {
            AppLog.put("DNS negative cache hit: host=${hostname.take(50)}")
            throw UnknownHostException("Negative cached: $hostname")
        }

        // addressCache 优先（用户手动配置的 IP 映射）
        // addressCache 值类型是 List<InetAddress>，直接返回
        AppConfig.addressCache[hostname]?.let { cachedAddress ->
            return cachedAddress
        }

        // 重试机制
        var lastException: UnknownHostException? = null
        for (attempt in 1..MAX_RETRY) {
            try {
                val addresses = Dns.SYSTEM.lookup(hostname)
                // P1-D 修复：过滤本地/无效 IP（DNS 劫持/污染或 adblock hosts 屏蔽）
                // 证据：796 次 ECONNREFUSED，部分域名解析到 127.0.0.1/0.0.0.0/::1/::
                // 方案：过滤 isLoopbackAddress(127.x.x.x/::1) 和 isAnyLocalAddress(0.0.0.0/::)
                val filtered = addresses.filterNot { addr ->
                    addr.isLoopbackAddress || addr.isAnyLocalAddress
                }
                if (filtered.isNotEmpty()) {
                    return filtered
                }
                // 全部被过滤，视为 DNS 污染，快速失败避免 15 秒连接超时
                AppLog.put("DNS 解析到本地/无效地址，已过滤: host=${hostname.take(50)}, originalCount=${addresses.size}")
                throw UnknownHostException("Filtered local/invalid addresses: $hostname")
            } catch (e: UnknownHostException) {
                lastException = e
                AppLog.put("DNS retry: host=${hostname.take(50)}, attempt=$attempt/$MAX_RETRY")
            }
        }

        // 全部失败：写入负缓存 + 永久日志
        negativeCache[hostname] = System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS
        AppLog.put("DNS lookup failed after $MAX_RETRY retries: host=${hostname.take(50)}")
        throw lastException ?: UnknownHostException(hostname)
    }
}

/**
 * F-P1-D 预连接/DNS 预解析：HEAD 请求提前建立 TCP+TLS 连接，复用到后续 GET 请求
 *
 * 适用场景：列表解析完成后，对前 N 篇文章域名发起预连接，点击文章内容页时减少 300-1000ms
 * 失败处理：kotlin.runCatching 捕获异常，失败不影响列表显示
 * 已知上限：HEAD 请求无 body 开销，复用连接池 50 连接 | 升级路径：无
 */
suspend fun warmUpConnection(url: String) = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext
    kotlin.runCatching {
        val request = okhttp3.Request.Builder()
            .url(url)
            .head()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            // 仅触发连接建立，不关心响应内容
            AppLog.put("HttpHelper 预连接完成: code=${response.code}")
        }
    }.onFailure {
        AppLog.put("HttpHelper 预连接失败: ${it.message?.take(100)}")
    }
}