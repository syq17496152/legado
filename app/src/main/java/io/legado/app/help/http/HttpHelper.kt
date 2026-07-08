package io.legado.app.help.http

import io.legado.app.constant.AppConst
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.progress.ProgressManager.LISTENER
import io.legado.app.help.glide.progress.ProgressResponseBody
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.model.ReadManga
import io.legado.app.utils.NetworkUtils
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
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
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(OkHttpExceptionInterceptor)
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
    if (AppConfig.addressCache.isNotEmpty()) {
        builder.dns { hostname ->
            val cachedAddress = AppConfig.addressCache[hostname]
            cachedAddress ?: Dns.SYSTEM.lookup(hostname)
        }
    }
    if (AppConfig.isCronet) {
        if (Cronet.loader?.install() == true) {
            Cronet.interceptor?.let {
                builder.addInterceptor(it)
            }
        }
    }
    builder.addInterceptor(DecompressInterceptor)
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