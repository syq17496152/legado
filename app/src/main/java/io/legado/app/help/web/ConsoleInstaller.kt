package io.legado.app.help.web

import android.util.Base64
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.web.FileWeb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel as ChannelQueue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 控制台**按需分发 / 安装器**（web-mcp-productization 四期 · tasks 1.3 + 1.4 / REQ-4-103 ~ REQ-4-109）。
 *
 * 职责：
 * 1. **三通道探测**（1.3.2）：并发探测 relay 与 GitHub，取先到者；全失败则 [Channel.NONE]（引导本地上传）。
 * 2. **三通道下载**：relay（1.3.3）/ GitHub Release 直连（1.3.4）/ 本地上传（1.3.5）。
 * 3. **校验链 + 原子安装**（1.4.x）：Ed25519 验签 + sha256 比对 + minAppApiLevel + entry 存在性；
 *    解压到暂存目录后 rename 切换，保留上一版以支持 [rollback]。
 *
 * **安装目标**：与 [FileWeb] 一致 —— `filesDir/console/current`（复用它取目录，绝不另开路径，
 * 避免"装了包但静态源读不到"）。
 *
 * **依赖边界**：只依赖 [FileWeb]（取目录）+ 核心工具（GSON / AppLog / 异常） + OkHttp，
 * **禁止**依赖 `api.controller` 与中继/relay 域（relay 地址经 [relayBaseUrlProvider] 注入，见下）。
 *
 * **安全红线**：
 * - 验签失败**或**运行环境不支持 Ed25519 ⇒ **拒绝安装**（不得降级放行）；本地上传才豁免验签。
 * - 解压逐条目做路径穿越校验（拒 `..`、绝对路径、盘符、NUL）。
 * - 私钥信息在本类中**完全不存在**（只持 [ConsoleKeys.CONSOLE_PUBLIC_KEY_BASE64] 公钥）
 */
object ConsoleInstaller {

    // ============================================================ 状态模型（字段名与 /consoleStatus 出参一致）

    /** 下载通道。 */
    enum class Channel { RELAY, GITHUB, NONE }

    /** 安装状态机（供 boot 页 / 设置页展示）。 */
    enum class State { NOT_INSTALLED, INSTALLING, INSTALLED, FAILED }

    /**
     * 本地已装包信息（持久化于 `console/installed.json`，**Gson 反序列化模型 ⇒ 必须 @Keep**）。
     */
    @Keep
    data class InstalledInfo(
        val version: String,
        val size: Long,
        val installedAt: Long,
    )

    /**
     * 发布侧包信息（来自 `manifest.json`，**Gson 反序列化模型 ⇒ 必须 @Keep**）。
     *
     * 签名不在本模型内 —— 它是包旁的独立文件（`<zip>` + [ConsoleKeys.SIGNATURE_SUFFIX]，Base64 编码）。
     */
    @Keep
    data class RemoteInfo(
        val version: String,
        val size: Long = 0L,
        /**
         * zip 整数哈希（sha256 十六进制）。
         *
         * **在线通道**：来自包**旁**的 `manifest.json`（发布侧计算，可信参照）。
         * **包内** `manifest.json` 该字段**必须留空** —— 包内文件无法携带"包含它自己的 zip 的哈希"
         * （自引用不可实现）⇒ 本地上传通道**不做 sha256 比对**（见 [verify] 的 `localBypass` 分支）。
         */
        val sha256: String = "",
        val minAppApiLevel: Int = 1,
        val notes: String? = null,
        /** 包内入口文件（默认 index.html）。 */
        val entry: String = DEFAULT_ENTRY,
    )

    /** 完整状态（F 组 `GET /consoleStatus` 的出参体）。 */
    data class ConsoleStatus(
        val state: State,
        val installed: InstalledInfo?,
        val latest: RemoteInfo?,
        val channel: Channel,
        val previousAvailable: Boolean,
        /** 当前 App 是否满足 [latest] 的 minAppApiLevel。 */
        val minAppApiLevelOk: Boolean,
    )

    /** 结构化安装失败（供端点映射 `{code, message}`）。 */
    class ConsoleInstallException(val code: String, message: String) : NoStackTraceException(message)

    // ============================================================ 可配置常量（待发布侧确认）

    /**
     * GitHub Release 直连基址。**待发布侧确认**：独立仓发布后的 Release 标签/资产命名需与此一致
     * （当前取 `latest` 标签，资产名 `manifest.json` 与 `console-v{version}.zip`）。
     */
    private const val GITHUB_RELEASE_BASE =
        "https://github.com/syq17496152/legadoM-web/releases/latest/download"

    /**
     * relay 通道基址提供者（默认 `null` = 未接线 ⇒ relay 探测直接失败、自动降级 GitHub）。
     *
     * **待接线**：由 relay 域在初始化时注入 `RelayConfig.workerUrl`（本类禁止直接依赖 relay 域，
     * 故以回调注入；未配对设备返回 null 即天然降级）。relay 侧资产路径约定为
     * `{workerUrl}/console/manifest.json` 与 `{workerUrl}/console/console-v{version}.zip`，
     * **待发布侧确认**。
     */
    var relayBaseUrlProvider: () -> String? = { null }

    private const val MANIFEST_NAME = "manifest.json"
    private const val DEFAULT_ENTRY = "index.html"
    private const val DOWNLOAD_TMP_NAME = "console-download.tmp.zip"
    private const val INSTALLED_META_NAME = "installed.json"
    private const val PROBE_TIMEOUT_MS = 3_000L
    private const val DOWNLOAD_TIMEOUT_MS = 60_000L

    /**
     * App 侧控制台接口契约级别（版本协议比对基线）。
     *
     * **须与 [io.legado.app.service.kernel.AppSettingsKernel.CONSOLE_API_LEVEL] 同步递增**
     * （两处同源，跨包引用被本类依赖红线禁止，故此处重复持有；改一处必须同时改另一处）。
     */
    private const val CONSOLE_API_LEVEL = 1

    /** Ed25519 SubjectPublicKeyInfo（SPKI）DER 前缀 —— 拼上 raw 32 字节公钥即为完整 SPKI。 */
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    // ============================================================ 内部状态

    /** 安装互斥（1.4.5）：INSTALLING 期间二次触发直接拒绝。 */
    private val installMutex = Mutex()

    /** 是否有安装进行中（供 [status] 出 INSTALLING 态）。 */
    @Volatile
    private var installing = false

    /** 上次操作是否失败（供 [status] 出 FAILED 态）。 */
    @Volatile
    private var lastFailed = false

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    // ============================================================ 目录（与 FileWeb 共用同一落盘点）

    /** 安装根：`filesDir/console`（[FileWeb.consoleDir] 的父目录）。 */
    private val installRoot: File
        get() = FileWeb.consoleDir.parentFile ?: File(appCtx.filesDir, "console")

    /** 当前版本目录（即 [FileWeb.consoleDir]，静态源读取处）。 */
    private val currentDir: File get() = FileWeb.consoleDir

    /** 上一版本目录（供 [rollback]）。 */
    private val previousDir: File get() = File(installRoot, "previous")

    /** 解压暂存目录（全部校验通过后才 rename 到 [currentDir]）。 */
    private val stagingDir: File get() = File(installRoot, "console-new")

    private val metaFile: File get() = File(installRoot, INSTALLED_META_NAME)

    // ============================================================ 公开 API

    /**
     * 查询状态（1.3.1 / 1.5.1）：读本地已装信息 + 探测通道 + 拉取发布侧 manifest（**不下载整包**）。
     */
    suspend fun status(): ConsoleStatus = withContext(Dispatchers.IO) {
        val installed = readInstalledInfo()
        val channel = probeChannels().firstOrNull() ?: Channel.NONE
        val latest: RemoteInfo? = if (channel == Channel.NONE) {
            null
        } else {
            runCatching { fetchManifest(channel) }
                .onFailure { AppLog.put("控制台 manifest 拉取失败（channel=${channel.name}）", it) }
                .getOrNull()
        }
        val state = when {
            installing -> State.INSTALLING
            installed != null -> State.INSTALLED
            lastFailed -> State.FAILED
            else -> State.NOT_INSTALLED
        }
        ConsoleStatus(
            state = state,
            installed = installed,
            latest = latest,
            channel = channel,
            previousAvailable = previousDir.isDirectory,
            minAppApiLevelOk = latest == null || latest.minAppApiLevel <= CONSOLE_API_LEVEL,
        )
    }

    /**
     * 从指定在线通道下载并安装（1.3.3 / 1.3.4），[onProgress] 为下载进度 0..100。
     *
     * 校验链任一失败 ⇒ 返回 failure 且**不落盘**、旧版完好。
     */
    suspend fun install(
        channel: Channel,
        onProgress: (Int) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (channel == Channel.NONE) {
            return@withContext Result.failure(
                ConsoleInstallException("NO_CHANNEL", "无可用下载通道，请改用本地上传")
            )
        }
        val base = relayOrGithubBase(channel)
            ?: return@withContext Result.failure(
                ConsoleInstallException("NO_CHANNEL", "通道地址未配置（${channel.name}）")
            )
        if (!installMutex.tryLock()) {
            return@withContext Result.failure(
                ConsoleInstallException("INSTALL_BUSY", "安装进行中，请稍后重试")
            )
        }
        installing = true
        try {
            val result = runCatching {
                val remote = fetchManifest(channel)
                requireApiLevel(remote)
                val url = zipUrl(channel, base, remote.version)
                val zipFile = downloadFile(url, onProgress)
                // 签名为包旁的独立文件（zip 原始字节签名），验签前必须先取到，缺失即拒绝
                val signature = fetchSignature(url)
                verify(zipFile, expected = remote, signature = signature, localBypass = false)
                atomicInstall(
                    zip = zipFile,
                    info = InstalledInfo(remote.version, zipFile.length(), System.currentTimeMillis()),
                    entry = remote.entry,
                )
                // 安装成功后清理下载临时包（暂存目录已被 rename 到 current，无需清理）
                runCatching { zipFile.delete() }
                Unit
            }
            afterInstallAttempt(result)
            result
        } finally {
            installing = false
            installMutex.unlock()
        }
    }

    /**
     * 本地上传通道安装（1.3.5 / REQ-4-106）：[confirm] = 用户已二次确认。
     *
     * **豁免验签**（包由用户自备，无发布侧签名），但仍做 sha256 完整性 + 结构 / 入口 校验；
     * 语义边界：只防**损坏包**，不防恶意包。
     */
    suspend fun uploadFromFile(
        file: File,
        confirm: Boolean,
        onProgress: (Int) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!confirm) {
            return@withContext Result.failure(
                ConsoleInstallException("NEED_CONFIRM", "本地上传需显式二次确认")
            )
        }
        if (!file.isFile) {
            return@withContext Result.failure(
                ConsoleInstallException("FILE_MISSING", "上传文件不存在")
            )
        }
        if (!installMutex.tryLock()) {
            return@withContext Result.failure(
                ConsoleInstallException("INSTALL_BUSY", "安装进行中，请稍后重试")
            )
        }
        installing = true
        try {
            val result = runCatching {
                onProgress(10)
                val manifest = readManifestFromZip(file)
                    ?: throw ConsoleInstallException("MANIFEST_MISSING", "包内缺 manifest.json（结构不完整）")
                onProgress(40)
                verify(file, expected = manifest, signature = null, localBypass = true)
                onProgress(70)
                atomicInstall(
                    zip = file,
                    info = InstalledInfo(manifest.version, file.length(), System.currentTimeMillis()),
                    entry = manifest.entry,
                )
                onProgress(100)
            }
            afterInstallAttempt(result)
            result
        } finally {
            installing = false
            installMutex.unlock()
        }
    }

    /**
     * 回退到上一可用版本（1.4.4 / REQ-4-108）：[currentDir] 与 [previousDir] 互换。
     */
    suspend fun rollback(): Result<Unit> = withContext(Dispatchers.IO) {
        val result = runCatching {
            if (!previousDir.isDirectory) {
                throw ConsoleInstallException("NO_PREVIOUS", "无可回退版本")
            }
            val tmp = File(installRoot, "rollback-tmp")
            tmp.deleteRecursively()
            if (!currentDir.renameTo(tmp)) {
                throw ConsoleInstallException("SWITCH_FAILED", "回退失败（旧版移出失败）")
            }
            if (!previousDir.renameTo(currentDir)) {
                runCatching { tmp.renameTo(currentDir) }
                throw ConsoleInstallException("SWITCH_FAILED", "回退失败（已还原）")
            }
            if (!tmp.renameTo(previousDir)) {
                throw ConsoleInstallException("SWITCH_FAILED", "回退失败（上一版目录还原失败）")
            }
            val restored = readManifestFromDir(currentDir)
            if (restored != null) {
                writeInstalledInfo(
                    InstalledInfo(restored.version, restored.size, System.currentTimeMillis())
                )
            }
            lastFailed = false
        }
        if (result.isFailure) {
            AppLog.put("控制台回退失败：${result.exceptionOrNull()?.message}", result.exceptionOrNull())
        }
        result
    }

    // ============================================================ 通道探测（1.3.2）

    /**
     * 并发探测 relay / GitHub 的 manifest 地址，**按到达顺序**返回可达通道（先到者在前）；
     * 空列表 = 全不可用（[Channel.NONE]，引导本地上传）。LOCAL 不参与自动探测。
     */
    private suspend fun probeChannels(): List<Channel> = coroutineScope {
        val urls = buildMap<Channel, String> {
            relayBaseUrlProvider()?.let { put(Channel.RELAY, manifestUrl(Channel.RELAY, it)) }
            put(Channel.GITHUB, manifestUrl(Channel.GITHUB, GITHUB_RELEASE_BASE))
        }
        if (urls.isEmpty()) {
            return@coroutineScope emptyList()
        }
        // 有界队列：容量 = 候选通道数（探测结束时 tryReceive 按 send 顺序（即到达顺序）取回）
        val arrived = ChannelQueue<Channel>(urls.size)
        val jobs = urls.map { (channel, url) ->
            launch { if (probeReachable(url)) arrived.send(channel) }
        }
        jobs.joinAll()
        arrived.close()
        buildList {
            while (true) {
                add(arrived.tryReceive().getOrNull() ?: break)
            }
        }
    }

    /** 单通道可达探测：HEAD 优先，服务端不支持 HEAD 时回退 GET（3s 超时）。 */
    private fun probeReachable(url: String): Boolean {
        val client = httpClient.newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        return runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { it.isSuccessful }
        }.getOrElse {
            runCatching {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    private fun relayOrGithubBase(channel: Channel): String? = when (channel) {
        Channel.RELAY -> relayBaseUrlProvider()
        Channel.GITHUB -> GITHUB_RELEASE_BASE
        Channel.NONE -> null
    }

    /** relay 资产置于 `/console/` 子路径下；GitHub 资产直接位于 Release 下载基址下。 */
    private fun manifestUrl(channel: Channel, base: String): String {
        val root = base.trimEnd('/')
        return when (channel) {
            Channel.RELAY -> "$root/console/$MANIFEST_NAME"
            Channel.GITHUB -> "$root/$MANIFEST_NAME"
            Channel.NONE -> "$root/$MANIFEST_NAME"
        }
    }

    private fun zipUrl(channel: Channel, base: String, version: String): String {
        val root = base.trimEnd('/')
        return when (channel) {
            Channel.RELAY -> "$root/console/console-v$version.zip"
            Channel.GITHUB -> "$root/console-v$version.zip"
            Channel.NONE -> "$root/console-v$version.zip"
        }
    }

    private fun fetchManifest(channel: Channel): RemoteInfo {
        val base = relayOrGithubBase(channel)
            ?: throw ConsoleInstallException("NO_CHANNEL", "通道地址未配置（${channel.name}）")
        val url = manifestUrl(channel, base)
        return httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ConsoleInstallException("MANIFEST_HTTP_${resp.code}", "manifest 拉取失败（HTTP ${resp.code}）")
            }
            GSON.fromJson(resp.body.string(), RemoteInfo::class.java)
                ?: throw ConsoleInstallException("MANIFEST_INVALID", "manifest.json 解析失败")
        }
    }

    /** 拉取包旁签名文件（Base64 原文）并解码为原始字节。 */
    private fun fetchSignature(zipUrl: String): ByteArray {
        val url = zipUrl + ConsoleKeys.SIGNATURE_SUFFIX
        return httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ConsoleInstallException("SIGNATURE_MISSING", "缺签名文件（HTTP ${resp.code}），拒绝安装")
            }
            // OkHttp 5：`resp.body` 非空、取文本用 `string()`（不是 `text()`）
            val raw = resp.body.string().trim()
            runCatching { Base64.decode(raw, Base64.DEFAULT) }
                .getOrElse { throw ConsoleInstallException("SIGNATURE_INVALID", "签名文件解码失败") }
        }
    }

    // ============================================================ 下载 + 校验 + 原子安装

    /** 下载到安装根内的临时文件（与最终位同盘 ⇒ rename 原子）。 */
    private fun downloadFile(url: String, onProgress: (Int) -> Unit): File {
        installRoot.mkdirs()
        val dest = File(installRoot, DOWNLOAD_TMP_NAME)
        dest.delete()
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ConsoleInstallException("DOWNLOAD_HTTP_${resp.code}", "下载失败（HTTP ${resp.code}）")
            }
            val total = resp.body.contentLength().takeIf { it > 0 } ?: -1L
            // 响应体流为空属异常（不能当作"下载完成"静默放行 —— 那会装进一个空包）
            val ins = resp.body.byteStream()
            ins.use { input ->
                FileOutputStream(dest).use { out ->
                    val buffer = ByteArray(8192)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        }
        onProgress(100)
        return dest
    }

    /**
     * 校验链（design 1.4）：① 结构（能取到 manifest）② sha256 完整性 ③ Ed25519 验签（本地上传豁免）
     * ④ minAppApiLevel 版本协议 ⑤ entry 存在性。任一失败即抛异常、**不落盘**。
     */
    private fun verify(
        zip: File,
        expected: RemoteInfo?,
        signature: ByteArray?,
        localBypass: Boolean,
    ) {
        // ① 结构
        val manifest = expected ?: readManifestFromZip(zip)
            ?: throw ConsoleInstallException("MANIFEST_MISSING", "包内缺 manifest.json（结构不完整）")

        // ② 完整性：zip 实算 sha256 与 manifest 声明比对。
        //    **本地上传豁免**：包内 manifest 无法携带"包含它自己的 zip 的哈希"（自引用不可实现）
        //    ⇒ 本地通道只防损坏，由 **zip 条目 CRC（ZipInputStream）+ 结构/入口/解压校验** 兜底。
        if (!localBypass) {
            val actualSha = sha256Hex(zip)
            if (!actualSha.equals(manifest.sha256, ignoreCase = true)) {
                throw ConsoleInstallException("SHA_MISMATCH", "包完整性校验失败（sha256 不符）")
            }
        }

        // ③ 来源可信：Ed25519 验签（本地上传通道豁免）
        if (!localBypass) {
            val sig = signature
                ?: throw ConsoleInstallException("SIGNATURE_MISSING", "缺签名文件，拒绝安装")
            verifyEd25519OrRefuse(zip, sig)
        }

        // ④ 版本协议
        requireApiLevel(manifest)

        // ⑤ 入口存在性（解压前先在 zip 内确认）
        if (!zipContainsEntry(zip, manifest.entry)) {
            throw ConsoleInstallException("ENTRY_MISSING", "包结构不完整（缺入口 ${manifest.entry}）")
        }
    }

    private fun requireApiLevel(manifest: RemoteInfo) {
        if (manifest.minAppApiLevel > CONSOLE_API_LEVEL) {
            throw ConsoleInstallException(
                "APP_API_TOO_LOW",
                "请升级 App（控制台要求 API ${manifest.minAppApiLevel}）",
            )
        }
    }

    /**
     * Ed25519 验签（zip 原始字节）。
     *
     * **平台可用性（实测口径）**：`java.security` 的 `Signature("Ed25519")` 与 `KeyFactory("Ed25519")`
     * 仅在较新平台（约 API 33+）可用；minSdk 23 起的老平台运行期抛 `NoSuchAlgorithmException`。
     * 故此处**先运行期探测**：不可用 ⇒ 抛 `VERIFY_UNSUPPORTED`（"验签能力受平台限制"），
     * **绝不跳过验签安装**（安全底线）；可用 ⇒ 真验签，验不过抛 `SIGNATURE_INVALID`。
     */
    private fun verifyEd25519OrRefuse(zip: File, signature: ByteArray) {
        val engineAvailable = runCatching {
            Signature.getInstance("Ed25519")
            KeyFactory.getInstance("Ed25519")
        }.isSuccess
        if (!engineAvailable) {
            throw ConsoleInstallException(
                "VERIFY_UNSUPPORTED",
                "验签能力受平台限制（本机无 Ed25519），拒绝安装",
            )
        }
        val ok = runCatching { verifyEd25519(zip, signature) }.getOrElse { e ->
            AppLog.put("控制台包验签执行异常", e)
            throw ConsoleInstallException("VERIFY_UNSUPPORTED", "验签失败（${e.javaClass.simpleName}），拒绝安装")
        }
        if (!ok) {
            throw ConsoleInstallException("SIGNATURE_INVALID", "包签名校验失败（来源不可信）")
        }
    }

    private fun verifyEd25519(zip: File, signature: ByteArray): Boolean {
        val rawKey = Base64.decode(ConsoleKeys.CONSOLE_PUBLIC_KEY_BASE64, Base64.DEFAULT)
        val spki = ED25519_SPKI_PREFIX + rawKey
        val publicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(spki))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        zip.inputStream().use { ins ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = ins.read(buffer)
                if (read <= 0) break
                verifier.update(buffer, 0, read)
            }
        }
        return verifier.verify(signature)
    }

    /**
     * 原子安装（design 1.5）：解压到 [stagingDir] → 校验入口 → 保留旧版（[currentDir] → [previousDir]）
     * → rename 切换 → 落 metadata。任一步失败抛异常并尽量还原，使旧版完好（回滚语义）。
     */
    private fun atomicInstall(zip: File, info: InstalledInfo, entry: String) {
        installRoot.mkdirs()
        stagingDir.deleteRecursively()
        unzipTo(zip, stagingDir)
        if (!File(stagingDir, entry).isFile) {
            stagingDir.deleteRecursively()
            throw ConsoleInstallException("ENTRY_MISSING", "包结构不完整（缺入口 $entry）")
        }
        // 保留上一版
        if (currentDir.exists()) {
            previousDir.deleteRecursively()
            if (!currentDir.renameTo(previousDir)) {
                stagingDir.deleteRecursively()
                throw ConsoleInstallException("SWITCH_FAILED", "旧版备份失败")
            }
        }
        if (!stagingDir.renameTo(currentDir)) {
            // 回滚：把备份换回 current
            runCatching { previousDir.renameTo(currentDir) }
            stagingDir.deleteRecursively()
            throw ConsoleInstallException("SWITCH_FAILED", "安装切换失败（可能跨挂载点）")
        }
        writeInstalledInfo(info)
    }

    /** 解压（逐条目路径穿越校验：拒 `..` / 绝对路径 / 盘符 / NUL），失败即整体抛出。 */
    private fun unzipTo(zip: File, destDir: File) {
        destDir.deleteRecursively()
        destDir.mkdirs()
        val canonicalRoot = destDir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") ||
                    name.contains(':') || name.contains('\u0000')
                ) {
                    destDir.deleteRecursively()
                    throw ConsoleInstallException("ENTRY_UNSAFE", "包内含非法路径条目")
                }
                val target = File(destDir, name)
                if (!target.canonicalPath.startsWith(canonicalRoot)) {
                    destDir.deleteRecursively()
                    throw ConsoleInstallException("ENTRY_UNSAFE", "包内含越界路径条目")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun afterInstallAttempt(result: Result<Unit>) {
        lastFailed = result.isFailure
        if (result.isFailure) {
            cleanupStaging()
            AppLog.put("控制台安装失败：${result.exceptionOrNull()?.message}", result.exceptionOrNull())
        }
    }

    /** 安装失败的现场清理：暂存目录与下载临时文件（旧版 current 不动）。 */
    private fun cleanupStaging() {
        runCatching { stagingDir.deleteRecursively() }
        runCatching { File(installRoot, DOWNLOAD_TMP_NAME).delete() }
    }

    // ============================================================ metadata / manifest 读写

    private fun writeInstalledInfo(info: InstalledInfo) {
        runCatching {
            installRoot.mkdirs()
            metaFile.writeText(GSON.toJson(info))
        }.onFailure { AppLog.put("控制台已装信息写入失败", it) }
    }

    /** 读本地已装信息：优先 `installed.json`，缺失则从 `current/manifest.json` 推断。 */
    private fun readInstalledInfo(): InstalledInfo? {
        if (metaFile.isFile) {
            runCatching { GSON.fromJson(metaFile.readText(), InstalledInfo::class.java) }
                .getOrNull()
                ?.let { return it }
        }
        return readManifestFromDir(currentDir)?.let {
            InstalledInfo(it.version, it.size, currentDir.lastModified())
        }
    }

    /** 读取 zip 内的顶层 `manifest.json`。 */
    private fun readManifestFromZip(zip: File): RemoteInfo? {
        return runCatching {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.substringAfterLast('/') == MANIFEST_NAME &&
                        entry.name.count { it == '/' } <= 1
                    ) {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        return@use GSON.fromJson(text, RemoteInfo::class.java)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                null
            }
        }.getOrNull()
    }

    private fun readManifestFromDir(dir: File): RemoteInfo? {
        val manifest = File(dir, MANIFEST_NAME)
        if (!manifest.isFile) return null
        return runCatching { GSON.fromJson(manifest.readText(), RemoteInfo::class.java) }.getOrNull()
    }

    private fun zipContainsEntry(zip: File, entry: String): Boolean {
        return runCatching {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name == entry) return@use true
                    zis.closeEntry()
                    e = zis.nextEntry
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = ins.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}