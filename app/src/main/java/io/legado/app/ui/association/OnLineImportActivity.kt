package io.legado.app.ui.association

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.config.ParagraphRuleManageActivity
import io.legado.app.ui.config.BubbleManageActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 网络一键导入
 * 格式: legado://import/{path}?src={url}
 */
class OnLineImportActivity :
    VMBaseActivity<ActivityTranslucenceBinding, OnLineImportViewModel>(),
    OnlineImportConfirmDialog.Callback,
    OnlineImportErrorDialog.Callback {

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)
    override val viewModel by viewModels<OnLineImportViewModel>()
    private val onlineImportDownloader by lazy { OnlineImportDownloader(applicationContext) }
    private val handler by lazy { buildMainHandler() }
    private var pendingDownload: OnlineImportDownload? = null
    private var pendingRoute: OnlinePackageImportRoute? = null
    private var pendingParagraphInspection: ParagraphRuleImportInspection? = null
    private var downloadJob: Job? = null

    /** 失败弹窗「重试」动作（F356）：随失败来源重建，null = 该失败不可重试 */
    private var retryAction: (() -> Unit)? = null

    /** F354 进度卡状态（Compose 槽位数据源；null = 槽位隐藏） */
    private var progressState by mutableStateOf<OnlineImportProgressState?>(null)
    private var lastProgressTickAt = 0L
    private var lastProgressBytes = 0L

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initProgressCard()
        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> showDialogFragment(
                    ImportBookSourceDialog(it.second, true)
                )
                "rssSource" -> showDialogFragment(
                    ImportRssSourceDialog(it.second, true)
                )
                "replaceRule" -> showDialogFragment(
                    ImportReplaceRuleDialog(it.second, true)
                )
                "httpTts" -> showDialogFragment(
                    ImportHttpTtsDialog(it.second, true)
                )
                "theme" -> showDialogFragment(
                    ImportThemeDialog(it.second, true)
                )
                "txtRule" -> showDialogFragment(
                    ImportTxtTocRuleDialog(it.second, true)
                )
                "dictRule" -> showDialogFragment(
                    ImportDictRuleDialog(it.second, true)
                )
            }
        }
        viewModel.errorLive.observe(this) {
            showImportErrorMessage(it)
        }
        intent.data?.let {
            val url = it.getQueryParameter("src")
            when (val route = OnlinePackageImportRoute.parse(it.scheme, it.host, it.path, url)) {
                is OnlinePackageImportRoute.ParagraphRule -> {
                    downloadOnlinePackage(route, OnlineImportPayloadType.PARAGRAPH_RULES)
                    return
                }

                is OnlinePackageImportRoute.Bubble -> {
                    downloadOnlinePackage(route, OnlineImportPayloadType.BUBBLE_PACKAGE)
                    return
                }

                is OnlinePackageImportRoute.Invalid -> {
                    showInvalidLink(route.kind)
                    return
                }

                OnlinePackageImportRoute.Other -> Unit
            }
            if (url.isNullOrEmpty()) {
                finish()
                return
            }
            when (it.path) {
                "/bookSource" -> showDialogFragment(
                    ImportBookSourceDialog(url, true)
                )

                "/rssSource" -> showDialogFragment(
                    ImportRssSourceDialog(url, true)
                )

                "/replaceRule" -> showDialogFragment(
                    ImportReplaceRuleDialog(url, true)
                )

                "/textTocRule" -> showDialogFragment(
                    ImportTxtTocRuleDialog(url, true)
                )
                "/httpTTS" -> showDialogFragment(
                    ImportHttpTtsDialog(url, true)
                )
                "/dictRule" -> showDialogFragment(
                    ImportDictRuleDialog(url, true)
                )
                "/theme" -> showDialogFragment(
                    ImportThemeDialog(url, true)
                )
                "/readConfig" -> viewModel.getBytes(url) { bytes ->
                    viewModel.importReadConfig(bytes, ::showReadConfigSuccess, ::showImportErrorMessage)
                }
                "/addToBookshelf" -> showDialogFragment(
                    AddToBookshelfDialog(url, true)
                )
                "/importonline" -> when (it.host) {
                    "booksource" -> showDialogFragment(
                        ImportBookSourceDialog(url, true)
                    )
                    "rsssource" -> showDialogFragment(
                        ImportRssSourceDialog(url, true)
                    )
                    "replace" -> showDialogFragment(
                        ImportReplaceRuleDialog(url, true)
                    )
                    else -> {
                        viewModel.determineType(url, ::showReadConfigSuccess, ::showImportErrorMessage)
                    }
                }
                else -> viewModel.determineType(url, ::showReadConfigSuccess, ::showImportErrorMessage)
            }
        }
    }

    /**
     * F354：下载/校验期进度卡（透明壳此前全程零反馈，弱网下像「点了没反应」）。
     * 槽位接线仅本页（其余透明壳页恒 gone 零占位）。
     */
    private fun initProgressCard() {
        binding.cvImportProgress.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.cvImportProgress.setContent {
            LegadoTheme {
                progressState?.let { state ->
                    OnlineImportProgressCard(state = state, onCancel = ::cancelImport)
                }
            }
        }
    }

    private fun showProgress(
        host: String,
        stage: OnlineImportStage,
        downloaded: Long = 0L,
        total: Long = 0L,
        bytesPerSecond: Long = 0L
    ) {
        progressState = OnlineImportProgressState(stage, host, downloaded, total, bytesPerSecond)
        binding.cvImportProgress.visibility = View.VISIBLE
    }

    private fun clearProgress() {
        progressState = null
        binding.cvImportProgress.visibility = View.GONE
    }

    /** 取消下载：关掉在途连接（OkHttp call）后取消协程并退出，避免「点了取消还在后台下完」 */
    private fun cancelImport() {
        onlineImportDownloader.cancelActive()
        downloadJob?.cancel()
        downloadJob = null
        pendingRoute = null
        pendingParagraphInspection = null
        if (!isFinishing) finish()
    }

    /** 进度回调在 IO 线程触发 ⇒ 节流后投主线程（150ms 一帧，收尾必投） */
    private fun reportProgress(host: String, downloaded: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastProgressTickAt
        val finished = total > 0L && downloaded >= total
        if (!finished && elapsed < PROGRESS_MIN_INTERVAL_MS) return
        val speed = if (lastProgressTickAt > 0L && elapsed > 0L) {
            (downloaded - lastProgressBytes) * 1000L / elapsed
        } else {
            0L
        }
        lastProgressTickAt = now
        lastProgressBytes = downloaded
        handler.post {
            showProgress(host, OnlineImportStage.DOWNLOAD, downloaded, total, speed.coerceAtLeast(0L))
        }
    }

    private fun downloadOnlinePackage(
        route: OnlinePackageImportRoute,
        payloadType: OnlineImportPayloadType,
        allowPrivateNetwork: Boolean = false
    ) {
        val sourceUrl = when (route) {
            is OnlinePackageImportRoute.ParagraphRule -> route.sourceUrl
            is OnlinePackageImportRoute.Bubble -> route.sourceUrl
            else -> return
        }
        val host = sourceUrl.toHttpUrlOrNull()?.host ?: sourceUrl
        retryAction = { downloadOnlinePackage(route, payloadType, allowPrivateNetwork) }
        lastProgressTickAt = 0L
        lastProgressBytes = 0L
        showProgress(host, OnlineImportStage.DOWNLOAD)
        downloadJob = lifecycleScope.launch {
            runCatching {
                onlineImportDownloader.download(sourceUrl, payloadType, allowPrivateNetwork) { downloaded, total ->
                    reportProgress(host, downloaded, total)
                }
            }.onSuccess { download ->
                if (isFinishing || isDestroyed) {
                    download.close()
                    return@onSuccess
                }
                pendingDownload?.close()
                pendingParagraphInspection = null
                pendingDownload = download
                pendingRoute = route
                when (route) {
                    is OnlinePackageImportRoute.ParagraphRule -> prepareParagraphRuleImport(download)
                    is OnlinePackageImportRoute.Bubble -> showBubbleImportPreview(download)
                    else -> discardPendingDownload(download)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (error is PrivateNetworkConfirmationRequiredException && !allowPrivateNetwork) {
                    clearProgress()
                    showPrivateNetworkConfirmation(route, payloadType)
                } else {
                    clearProgress()
                    showImportFailure(error, retryable = true)
                }
            }
        }
    }

    private fun showPrivateNetworkConfirmation(
        route: OnlinePackageImportRoute,
        payloadType: OnlineImportPayloadType
    ) {
        showComposeConfirmDialog(
            title = getString(R.string.online_import_private_network_title),
            message = getString(R.string.online_import_private_network_message),
            positiveText = getString(R.string.continue_),
            messageInContent = true,
            onPositive = {
                downloadOnlinePackage(route, payloadType, allowPrivateNetwork = true)
            },
            onDismissAction = ::finish
        )
    }

    private fun showBubbleImportPreview(download: OnlineImportDownload) {
        showProgress(hostOf(download.sourceUrl), OnlineImportStage.CONFIRM)
        showDialogFragment(
            OnlineImportConfirmDialog.create(
                typeName = getString(R.string.bubble_package),
                sourceUrl = download.sourceUrl,
                finalUrl = download.finalUrl,
                sizeText = ConvertUtils.formatFileSize(download.size),
                privateNetwork = download.privateNetwork
            )
        )
    }

    private fun prepareParagraphRuleImport(download: OnlineImportDownload) {
        showProgress(hostOf(download.sourceUrl), OnlineImportStage.INSPECT)
        lifecycleScope.launch {
            try {
                val inspection = withContext(IO) {
                    ParagraphRulePackageImporter(appDb).inspect(download.file)
                }
                if (isFinishing || isDestroyed) {
                    discardPendingDownload(download, finishActivity = false)
                    return@launch
                }
                pendingParagraphInspection = inspection
                showParagraphRulePreview(download, inspection)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                discardPendingDownload(download, finishActivity = false)
                clearProgress()
                showImportFailure(error, retryable = true)
            }
        }
    }

    private fun showParagraphRulePreview(
        download: OnlineImportDownload,
        inspection: ParagraphRuleImportInspection
    ) {
        showProgress(hostOf(download.sourceUrl), OnlineImportStage.CONFIRM)
        val totalCount = inspection.packageData.entries.size
        showDialogFragment(
            OnlineImportConfirmDialog.create(
                typeName = getString(R.string.paragraph_rule),
                sourceUrl = download.sourceUrl,
                finalUrl = download.finalUrl,
                sizeText = ConvertUtils.formatFileSize(download.size),
                privateNetwork = download.privateNetwork,
                summary = getString(
                    R.string.paragraph_import_summary,
                    totalCount,
                    totalCount - inspection.conflictCount,
                    inspection.conflictCount
                ),
                warning = getString(R.string.online_import_paragraph_script_warning),
                conflictCount = inspection.conflictCount
            )
        )
    }

    override fun onOnlineImportConfirmed(strategy: ParagraphRuleConflictStrategy) {
        val download = pendingDownload
        val route = pendingRoute
        val inspection = pendingParagraphInspection
        pendingDownload = null
        pendingRoute = null
        pendingParagraphInspection = null
        clearProgress()
        if (download == null || route == null) {
            download?.close()
            if (!isFinishing) finish()
            return
        }
        importOnlinePackage(route, download, inspection, strategy)
    }

    override fun onOnlineImportCancelled() {
        clearProgress()
        pendingRoute = null
        discardPendingDownload(pendingDownload)
    }

    override fun onOnlineImportRetry() {
        val action = retryAction
        if (action == null) {
            if (!isFinishing) finish()
            return
        }
        action()
    }

    private fun discardPendingDownload(
        download: OnlineImportDownload?,
        finishActivity: Boolean = true
    ) {
        if (download != null && pendingDownload === download) {
            pendingDownload = null
            pendingRoute = null
            pendingParagraphInspection = null
        }
        download?.close()
        if (finishActivity && !isFinishing) finish()
    }

    private fun importOnlinePackage(
        route: OnlinePackageImportRoute,
        download: OnlineImportDownload,
        paragraphInspection: ParagraphRuleImportInspection? = null,
        paragraphStrategy: ParagraphRuleConflictStrategy = ParagraphRuleConflictStrategy.RENAME
    ) {
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val resultMessage = when (route) {
                    is OnlinePackageImportRoute.Bubble -> withContext(IO) {
                        BubblePackageManager.importZip(download.file)
                        getString(R.string.success)
                    }

                    is OnlinePackageImportRoute.ParagraphRule -> {
                        val inspection = paragraphInspection
                            ?: throw IllegalStateException("Paragraph rule package was not prepared")
                        val result = withContext(IO) {
                            ParagraphRulePackageImporter(appDb).import(inspection, paragraphStrategy)
                        }
                        runCatching {
                            ReadBook.invalidateParagraphRuleLayout()
                            ReadBook.callBack?.get()?.upContent(resetPageOffset = false)
                            ReadBook.loadContent(resetPageOffset = false)
                        }
                        getString(
                            R.string.paragraph_import_result,
                            result.inserted,
                            result.overwritten,
                            result.skipped,
                            result.renamed
                        )
                    }

                    else -> return@launch
                }
                showSuccessDialog(route, resultMessage)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showImportFailure(error)
            } finally {
                download.close()
            }
        }
    }

    /**
     * 优化 4（F357）：导入成功弹窗即入口 —— 「去查看」按来源直达对应管理页。
     *
     * 此前成功弹窗只有单按钮「确认」点击即 finish（`OnLineImportActivity.kt` 旧 finallyDialog），
     * 一键导入多来自分享链接（新手导源第一步），「导入了但不知道去哪看」是「导入失败」误报的主要来源。
     * 口径：**只有确有管理页**的来源才给「去查看」（排版配置无独立管理页 ⇒ 保持单按钮，登记待办）。
     */
    private fun showSuccessDialog(route: OnlinePackageImportRoute, message: String) {
        val target = manageTargetFor(route)
        if (target == null) {
            finallyDialog(getString(R.string.success), message)
            return
        }
        showComposeConfirmDialog(
            title = getString(R.string.success),
            message = message,
            positiveText = getString(R.string.online_import_go_view),
            negativeText = getString(android.R.string.ok),
            messageInContent = true,
            onPositive = {
                startActivity(Intent(this, target))
                finish()
            },
            onNegative = ::finish,
            onDismissAction = ::finish
        )
    }

    private fun manageTargetFor(route: OnlinePackageImportRoute): Class<*>? = when (route) {
        is OnlinePackageImportRoute.ParagraphRule -> ParagraphRuleManageActivity::class.java
        is OnlinePackageImportRoute.Bubble -> BubbleManageActivity::class.java
        else -> null
    }

    /** 排版配置导入成功（无独立管理页 ⇒ 走单按钮；文案含配置名，导入的就是哪个一目了然） */
    private fun showReadConfigSuccess(configName: String) {
        finallyDialog(
            getString(R.string.success),
            getString(R.string.import_read_config_success, configName)
        )
    }

    /**
     * 失败态统一出口（修复 3 + 优化 5）：
     * - 可重试来源（下载/校验失败）⇒ 「重试」+「复制错误详情」+「关闭」，用户能留在原地排查；
     * - 其余失败（如排版解析）⇒ 「复制错误详情」+「确认」。
     * 文案按 [OnlineImportFailureKind] 取本地化资源（F353：不再透出英文内部消息）。
     */
    private fun showImportFailure(error: Throwable, retryable: Boolean = false) {
        val reason = localizedImportMessage(error)
        showDialogFragment(
            OnlineImportErrorDialog.create(
                reason = reason,
                copyDetail = buildFailureDetail(error, reason),
                allowRetry = retryable
            )
        )
    }

    /** ViewModel 直接回报的失败（文案已是本地化文本，无异常对象） */
    private fun showImportErrorMessage(message: String) {
        showDialogFragment(
            OnlineImportErrorDialog.create(reason = message, copyDetail = message)
        )
    }

    /** 优化 5（F356）：非法链接弹窗附**原始链接**（单行省略 + 复制链接），让排障信息可带离 */
    private fun showInvalidLink(kind: OnlineImportFailureKind) {
        clearProgress()
        showDialogFragment(
            OnlineImportErrorDialog.create(
                reason = localizedImportFailureKind(kind),
                rawLink = intent.data?.toString()
            )
        )
    }

    private fun buildFailureDetail(error: Throwable, reason: String): String = buildString {
        append(reason)
        val raw = error.message
        if (!raw.isNullOrBlank() && raw != reason) {
            append('\n').append(raw)
        }
    }

    private fun localizedImportMessage(error: Throwable): String {
        if (error is OnlineImportFailureException) {
            return localizedImportFailureKind(error.kind, error.detail)
        }
        return error.localizedMessage ?: getString(R.string.unknown_error)
    }

    private fun localizedImportFailureKind(kind: OnlineImportFailureKind, detail: String? = null): String =
        when (kind) {
            OnlineImportFailureKind.SOURCE_URL_INVALID -> getString(R.string.online_import_error_source_url)
            OnlineImportFailureKind.CREDENTIALS_NOT_ALLOWED -> getString(R.string.online_import_error_credentials)
            OnlineImportFailureKind.LOCALHOST_NOT_ALLOWED -> getString(R.string.online_import_error_localhost)
            OnlineImportFailureKind.UNSAFE_ADDRESS -> getString(
                R.string.online_import_error_unsafe_address,
                detail.orEmpty()
            )
            OnlineImportFailureKind.HOST_UNRESOLVED -> getString(
                R.string.online_import_error_host_unresolved,
                detail.orEmpty()
            )
            OnlineImportFailureKind.HTTP_STATUS -> getString(
                R.string.online_import_error_http_status,
                detail?.toIntOrNull() ?: 0
            )
            OnlineImportFailureKind.TOO_LARGE -> getString(
                R.string.online_import_error_too_large,
                ConvertUtils.formatFileSize(detail?.toLongOrNull() ?: 0L)
            )
            OnlineImportFailureKind.EMPTY_DOWNLOAD -> getString(R.string.online_import_error_empty_download)
            OnlineImportFailureKind.REDIRECT_INVALID -> getString(R.string.online_import_error_redirect)
            OnlineImportFailureKind.REDIRECT_DOWNGRADE -> getString(R.string.online_import_error_redirect_downgrade)
            OnlineImportFailureKind.TOO_MANY_REDIRECTS -> getString(R.string.online_import_error_too_many_redirects)
            OnlineImportFailureKind.PROXY_NOT_ALLOWED -> getString(R.string.online_import_error_proxy)
            OnlineImportFailureKind.ROUTE_UNAVAILABLE -> getString(R.string.online_import_error_route)
            OnlineImportFailureKind.HOST_NOT_IMPORT -> getString(R.string.online_import_error_host_not_import)
            OnlineImportFailureKind.SRC_MISSING -> getString(R.string.online_import_error_src_missing)
            OnlineImportFailureKind.PACKAGE_MALFORMED -> getString(R.string.online_import_error_package_malformed)
            OnlineImportFailureKind.PACKAGE_UNSUPPORTED -> getString(R.string.online_import_error_package_unsupported)
            OnlineImportFailureKind.PACKAGE_RULE_INVALID -> getString(R.string.online_import_error_package_rule_invalid)
            OnlineImportFailureKind.PACKAGE_LIMIT_EXCEEDED -> getString(R.string.online_import_error_package_limit)
        }

    private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url

    override fun onDestroy() {
        downloadJob?.cancel()
        downloadJob = null
        onlineImportDownloader.cancelActive()
        pendingDownload?.close()
        pendingDownload = null
        pendingRoute = null
        pendingParagraphInspection = null
        super.onDestroy()
    }

    private fun finallyDialog(title: String, msg: String) {
        showComposeConfirmDialog(
            title = title,
            message = msg,
            showNegative = false,
            messageInContent = true,
            onPositive = ::finish,
            onDismissAction = ::finish
        )
    }

    private companion object {
        /** 进度上报节流：150ms 一帧（下载回调本身按 32KB 步长触发） */
        const val PROGRESS_MIN_INTERVAL_MS = 150L
    }

}