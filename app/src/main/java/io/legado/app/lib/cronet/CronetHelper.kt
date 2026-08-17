@file:Keep
@file:Suppress("DEPRECATION")

package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.customIp
import io.legado.app.utils.DebugLog
import io.legado.app.utils.externalCache
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import org.chromium.net.CronetEngine.Builder.HTTP_CACHE_DISK
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.chromium.net.X509Util
import org.chromium.net.impl.NativeCronetEngineBuilderImpl
import org.json.JSONObject
import splitties.init.appCtx

internal const val BUFFER_SIZE = 32 * 1024

/**
 * P0-ANR-fix(2026-08-16): cronetEngine 由 lazy(SYNCHRONIZED) 改为 volatile 非阻塞读取
 *
 * 根因铁证（真机日志 2026-08-16 21:20~21:22，进程 5206→8629→15320→18957 连环死亡）:
 *   1. preInit 在 IO 线程触发 lazy 持锁执行 syncEnsureSoFile（GitHub 直连下载 so，
 *      HttpURLConnection 未设超时，真机挂起 17s+ 无下文）
 *   2. 主线程 GSY MediaHandler → ExoPlayerManager.initVideoPlayer → ExoPlayerHelper
 *      .setDefaultHeaders → cronetDataFactory lazy → cronetEngine lazy 等待同一把锁
 *   3. MIUIScout ANR 堆栈 4 次卡在 getCronetEngine(CronetHelper.kt:30)，主线程阻塞 22s
 *      → ANR(SIGABRT) → 进程死亡 → 用户反复打开播放器反复闪退
 *   4. crashCount>=3 降级仅跳过 preInit，但 lazy 仍会被主线程触发，降级机制失效
 *
 * 修复：读取方（主线程/网络线程）未就绪立即拿到 null 回退 OkHttp，绝不阻塞；
 *   初始化仅在 preInitCronetEngine（IO 线程）执行一次，成功后所有读取方拿到实例
 */
@Volatile
private var cachedCronetEngine: ExperimentalCronetEngine? = null

@Volatile
private var cronetInitFailed = false

private val engineInitLock = Any()

val cronetEngine: ExperimentalCronetEngine?
    get() = cachedCronetEngine

/**
 * 仅限 IO 线程（preInitCronetEngine）调用：阻塞执行 so 下载 + 引擎构建
 * 失败后本次进程生命周期内不再重试（与原 lazy 缓存 null 结果的行为一致）
 */
private fun initCronetEngineBlocking(): ExperimentalCronetEngine? {
    synchronized(engineInitLock) {
        cachedCronetEngine?.let { return it }
        if (cronetInitFailed) return null
        val engine = buildCronetEngine()
        if (engine == null) {
            cronetInitFailed = true
        } else {
            cachedCronetEngine = engine
        }
        return engine
    }
}

private fun buildCronetEngine(): ExperimentalCronetEngine? {
    // 防御性 try-catch：覆盖整个初始化，确保任何异常（含 apply 块内方法抛出）都被捕获
    // 铁证：真机日志显示 "All available Cronet providers are disabled" 异常从 lazy 块逃逸，
    //   原因是 try 只包裹 builder.build()，apply 块中的方法异常未被捕获
    return try {
        disableCertificateVerify()
        // P0: 优先动态下载 libcronet.so（减少 APK 体积 6.37MB），失败再尝试 jniLibs 兜底
        // 铁证：2026-07-31 用户决策"优化为动态下载"，移除 jniLibs/libcronet.so
        // 动态下载的 so 文件名带版本号（libcronet.{version}.so），通过 System.load 加载
        // ProGuard 规则（cronet-proguard-rules.pro + proguard-rules.pro）保留所有 Cronet Java 类，确保 JNI 调用正常
        // 2026-07-30 崩溃根因是 R8 移除 Java 类（非 so 文件名问题），ProGuard 规则已修复
        val nativeLoaded = try {
            // P0-fix(2026-08-16): 优先 APK 内置 so（jniLibs）——零网络依赖，打开即用
            // 铁证：私有仓库 Release 匿名不可下载（GitHub API 404，ghproxy 匿名代理同样拿不到私仓资产），
            //   原方案真机上 so 永远装不上 → Cronet 降级 → 视频 CDN TLS 指纹被拒"播放不了"
            try {
                System.loadLibrary("cronet")
                AppLog.put("CronetHelper: System.loadLibrary(cronet) success (from APK jniLibs)")
                true
            } catch (e: Throwable) {
                // 内置缺失（理论上不发生，arm64-v8a 已随 APK 打包）：兜底动态下载
                val soReady = CronetLoader.syncEnsureSoFile()
                AppLog.put("CronetHelper: syncEnsureSoFile()=$soReady, soFile=${CronetLoader.soFileExists()}, md5=${CronetLoader.md5Value().take(8)}")
                if (soReady) {
                    CronetLoader.manualLoad()
                } else {
                    false
                }
            }
        } catch (e: Throwable) {
            AppLog.put("CronetHelper: native load failed", e)
            false
        }

        // P0: 强制使用 NativeCronetEngineBuilderImpl，绕过 ExperimentalCronetEngine.Builder(context) 的 pickBuilderImpl 降级逻辑
        // 根因：ExperimentalCronetEngine.Builder(context) 内部 pickBuilderImpl 尝试创建 NativeCronetEngineBuilderImpl，
        //   但其内部 native 检查（如 Cronet.loadLibrary）可能因时序/条件不满足而失败，降级到 JavaCronetEngineBuilderImpl。
        // 解决：native so 已加载后直接创建 NativeCronetEngineBuilderImpl，绕过 pickBuilderImpl 的降级判断
        val engine = if (nativeLoaded) {
            AppLog.put("CronetHelper: creating NativeCronetEngineBuilderImpl directly (bypass pickBuilderImpl)")
            NativeCronetEngineBuilderImpl(appCtx).apply {
                setLibraryLoader(CronetLoader)//设置自定义so库加载
                setStoragePath(appCtx.externalCache.absolutePath)//设置缓存路径
                enableHttpCache(HTTP_CACHE_DISK, (1024 * 1024 * 50).toLong())//设置50M的磁盘缓存
                enableQuic(true)//设置支持http/3（HTTP/3 = HTTP over QUIC，Cronet 无独立 enableHttp3 方法）
                enableHttp2(true)  //设置支持http/2
                enablePublicKeyPinningBypassForLocalTrustAnchors(true)
                enableBrotli(true)//Brotli压缩
                // P1-1: 启用网络质量评估（2026-07-31）
                // 作用：Cronet 内部测量有效带宽和 RTT，用于 QUIC 协商和连接迁移决策
                // 成熟方案参考：Android CronetEngine 官方文档 enableNetworkQualityEstimator
                enableNetworkQualityEstimator(true)
                setExperimentalOptions(options)
            }.build()
        } else {
            AppLog.put("CronetHelper: native load failed, fallback to default Builder (JavaCronetEngine)")
            ExperimentalCronetEngine.Builder(appCtx).apply {
                setLibraryLoader(CronetLoader)
                setStoragePath(appCtx.externalCache.absolutePath)
                enableHttpCache(HTTP_CACHE_DISK, (1024 * 1024 * 50).toLong())
                enableQuic(true)
                enableHttp2(true)
                enablePublicKeyPinningBypassForLocalTrustAnchors(true)
                enableBrotli(true)
                enableNetworkQualityEstimator(true)
                setExperimentalOptions(options)
            }.build()
        }
        AppLog.put("CronetHelper: engine built, class=${engine.javaClass.simpleName}, version=${engine.versionString}")
        DebugLog.d("Cronet Version:", engine.versionString)
        engine
    } catch (e: Throwable) {
        AppLog.put("初始化cronetEngine出错", e)
        null
    }
}

/**
 * P0-ANR-fix(2026-07-31): 后台预初始化 cronetEngine，避免主线程 lazy 触发导致 ANR
 * P0-ANR-fix(2026-08-16): lazy 已重构为 volatile 非阻塞读取，本方法是唯一初始化入口；
 *   crashCount>=3 降级时直接 return（cachedCronetEngine 保持 null），
 *   主线程/网络线程读取 null 后回退 OkHttp，不再出现降级后主线程仍触发 lazy 的漏洞
 *
 * 根因铁证（extracted_v3 日志 appLog-26-07-31_08-12-49.319.txt）:
 *   08:13:07.240 VideoPlay.startPlay（主线程）
 *   08:13:07.5   触发 cronetEngine lazy 链: cacheDataSourceFactory → cronetDataFactory → cronetEngine
 *   08:13:07.5-10.391 syncEnsureSoFile 执行约3s（主线程阻塞）
 *   08:13:10.391-12.5 manualLoad + build 约2s（主线程阻塞）
 *   08:13:12.565 Dispatchers.Main timed out 5000ms → ANR，App被系统杀死
 *   08:13:13.074 App 重启
 *
 * 修复方案: App.onCreate 后台线程执行 initCronetEngineBlocking 完成全部初始化，
 *   任何线程读取 cronetEngine 均为非阻塞 volatile 字段访问
 *
 * 为什么之前打包so方案没这个问题:
 *   打包so方案 System.loadLibrary 直接从APK加载so（<100ms），
 *   动态下载方案 syncEnsureSoFile 需检查/下载/校验md5（首次数秒）
 */
fun preInitCronetEngine() {
    try {
        // JNI 崩溃监控（初始化标志法）：SIGABRT 是 native 崩溃，Java 层无法捕获
        // 方案：初始化前写入标志，初始化成功后清除；下次启动检测标志判断是否崩溃
        val prefs = appCtx.getSharedPreferences("cronet_safety", android.content.Context.MODE_PRIVATE)

        // 1. 检查上次初始化是否崩溃（"cronet_initializing"=true 意味着上次崩溃了）
        if (prefs.getBoolean("cronet_initializing", false)) {
            val crashCount = prefs.getInt("cronet_crash_count", 0) + 1
            prefs.edit().putInt("cronet_crash_count", crashCount).putBoolean("cronet_initializing", false).apply()
            AppLog.put("CronetHelper: 检测到上次初始化崩溃(SIGABRT), crashCount=$crashCount")

            if (crashCount >= 3) {
                // 累计崩溃达阈值，本次启动降级到 OkHttp，跳过 Cronet 初始化
                AppLog.put("CronetHelper: JNI崩溃次数达阈值3, 本次启动降级到OkHttp")
                return
            }
        }

        // 2. 设置初始化标志（如果初始化过程中 SIGABRT 崩溃，标志不会被清除，下次启动可检测）
        prefs.edit().putBoolean("cronet_initializing", true).apply()

        val startMs = System.currentTimeMillis()
        // 触发阻塞初始化（在调用线程执行，App.onCreate 中应在 IO 线程调用）
        // P0-ANR-fix(2026-08-16): 主线程不再有路径触发此初始化（cronetEngine 改为非阻塞读取）
        val engine = initCronetEngineBlocking()
        val costMs = System.currentTimeMillis() - startMs

        // 3. 初始化成功，清除初始化标志
        prefs.edit().putBoolean("cronet_initializing", false).apply()

        // 4. 初始化成功后重置 crash_count（如果之前有崩溃记录，初始化成功说明问题已解决）
        if (prefs.getInt("cronet_crash_count", 0) > 0) {
            prefs.edit().putInt("cronet_crash_count", 0).apply()
            AppLog.put("CronetHelper: 初始化成功, 重置crashCount=0")
        }

        AppLog.put("CronetHelper: preInitCronetEngine done, engine=${engine?.javaClass?.simpleName}, costMs=$costMs")
    } catch (e: Throwable) {
        // 初始化失败（非 SIGABRT，如 Java 异常），清除初始化标志
        appCtx.getSharedPreferences("cronet_safety", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("cronet_initializing", false).apply()
        AppLog.put("CronetHelper: preInitCronetEngine failed", e)
    }
}

val options by lazy {
    val options = JSONObject()

    //设置域名映射规则
    //MAP hostname ip,MAP hostname ip
//    val host = JSONObject()
//    host.put("host_resolver_rules","")
//    options.put("HostResolverRules", host)

    //启用DnsHttpsSvcb更容易迁移到http3
    val dnsSvcb = JSONObject()
    dnsSvcb.put("enable", true)
    dnsSvcb.put("enable_insecure", true)
    dnsSvcb.put("use_alpn", true)
    options.put("UseDnsHttpsSvcb", dnsSvcb)

    options.put("AsyncDNS", JSONObject("{'enable':true}"))


    options.toString()
}

fun buildRequest(request: Request, callback: UrlRequest.Callback): UrlRequest? {
    val url = request.url.toString()
    val headers: Headers = request.headers
    val requestBody = request.body
    return cronetEngine?.newUrlRequestBuilder(
        customHost(url),
        callback,
        okHttpClient.dispatcher.executorService
    )?.apply {
        setHttpMethod(request.method)//设置
        allowDirectExecutor()
        headers.forEachIndexed { index, _ ->
            if (headers.name(index) == cookieJarHeader) return@forEachIndexed
            addHeader(headers.name(index), headers.value(index))
        }
        if (requestBody != null) {
            val contentType: MediaType? = requestBody.contentType()
            if (contentType != null) {
                addHeader("Content-Type", contentType.toString())
            } else {
                addHeader("Content-Type", "text/plain")
            }
            val provider: UploadDataProvider = if (requestBody.contentLength() > BUFFER_SIZE) {
                LargeBodyUploadProvider(requestBody, okHttpClient.dispatcher.executorService)
            } else {
                BodyUploadProvider(requestBody)
            }
            provider.use {
                this.setUploadDataProvider(it, okHttpClient.dispatcher.executorService)
            }
        }
    }?.build()
}

private fun customHost(url: String): String {
    val urlIp = customIp.remove(url)
    if (AppConfig.hostMap.isEmpty() && urlIp == null) return url
    val host = AppPattern.domainRegex.find(url)?.groupValues?.getOrNull(1) ?: return url
    if (urlIp != null) return url.replaceFirst(host, urlIp)
    val ip = when (val configIps = AppConfig.hostMap[host]) {
        is String -> configIps.splitToSequence(',')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
        is List<*> -> configIps.firstOrNull()
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.trim()
        else -> null
    } ?: return url
    return url.replaceFirst(host, ip)
}

private fun disableCertificateVerify() {
    runCatching {
        val sDefaultTrustManager = X509Util::class.java.getDeclaredField("sDefaultTrustManager")
        sDefaultTrustManager.isAccessible = true
        sDefaultTrustManager.set(null, SSLHelper.unsafeTrustManagerExtensions)
    }
    runCatching {
        val sTestTrustManager = X509Util::class.java.getDeclaredField("sTestTrustManager")
        sTestTrustManager.isAccessible = true
        sTestTrustManager.set(null, SSLHelper.unsafeTrustManagerExtensions)
    }
}
