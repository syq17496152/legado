package io.legado.app.ui.about

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityAboutBinding
import io.legado.app.help.CrashHandler
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.update.AppUpdate
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendMail
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.delay
import splitties.init.appCtx
import java.io.File

/**
 * 关于页（L-C20，S6 展示 + S2 列表管理页）
 * Compose 化：AboutScreen 壳层，AboutFragment 的偏好项业务（openUrl/showMdFile/checkUpdate/
 * sendMail/copyGzh/CrashLogsDialog/saveLog/createHeapDump）并入本 Activity；Dialog 族保留 View 体系
 */
class AboutActivity : BaseActivity<ActivityAboutBinding>() {

    override val binding by viewBinding(ActivityAboutBinding::inflate)

    private val waitDialog by lazy {
        WaitDialog(this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                AboutScreen(
                    versionName = appInfo.versionName,
                    onBack = { finish() },
                    onShare = {
                        share(
                            getString(R.string.app_share_description_sigma),
                            getString(R.string.app_name)
                        )
                    },
                    onScoring = { openUrl("market://details?id=$packageName") },
                    onContributors = { openUrl(getString(R.string.contributors_url)) },
                    onUpdateLog = { showMdFile(getString(R.string.update_log), "updateLog.md") },
                    onCheckUpdate = { checkUpdate() },
                    onMail = { sendMail(getString(R.string.email)) },
                    onLicense = { showMdFile(getString(R.string.license), "LICENSE.md") },
                    onDisclaimer = { showMdFile(getString(R.string.disclaimer), "disclaimer.md") },
                    onPrivacyPolicy = { showMdFile(getString(R.string.privacy_policy), "privacyPolicy.md") },
                    onCopyGzh = { sendToClip(getString(R.string.legado_gzh)) },
                    onCrashLog = { showDialogFragment<CrashLogsDialog>() },
                    onSaveLog = { saveLog() },
                    onCreateHeapDump = { createHeapDump() }
                )
            }
        }
    }

    /**
     * 显示md文件
     */
    private fun showMdFile(title: String, fileName: String) {
        val mdText = String(assets.open(fileName).readBytes())
        showDialogFragment(TextDialog(title, mdText, TextDialog.Mode.MD))
    }

    /**
     * 检测更新
     */
    private fun checkUpdate() {
        waitDialog.show()
        AppUpdate.giteeUpdate.run {
            check(lifecycleScope)
                .onSuccess {
                    showDialogFragment(
                        UpdateDialog(it)
                    )
                }.onError {
                    appCtx.toastOnUi("${getString(R.string.check_update)}\n${it.localizedMessage}")
                }.onFinally {
                    waitDialog.dismiss()
                }
        }
    }

    private fun saveLog() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordLog) {
                appCtx.toastOnUi("未开启日志记录，请去其他设置里打开记录日志")
                delay(3000)
            }
            val doc = FileDoc.fromUri(android.net.Uri.parse(backupPath), true)
            copyLogs(doc)
            copyHeapDump(doc)
            appCtx.toastOnUi("已保存至备份目录")
        }.onError {
            AppLog.put("保存日志出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun createHeapDump() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                appCtx.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            appCtx.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(android.net.Uri.parse(backupPath), true)
            if (!copyHeapDump(doc)) {
                appCtx.toastOnUi("未找到堆转储文件")
            } else {
                appCtx.toastOnUi("已保存至备份目录")
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }

    private fun copyLogs(doc: FileDoc) {
        val cacheDir = appCtx.externalCache
        val logFiles = File(cacheDir, "logs")
        val crashFiles = File(cacheDir, "crash")
        val logcatFile = File(cacheDir, "logcat.txt")

        dumpLogcat(logcatFile)

        val zipFile = File(cacheDir, "logs.zip")
        ZipUtils.zipFiles(arrayListOf(logFiles, crashFiles, logcatFile), zipFile)

        doc.find("logs.zip")?.delete()

        zipFile.inputStream().use { input ->
            doc.createFileIfNotExist("logs.zip").openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        zipFile.delete()
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(appCtx.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            heapDumpDoc.createFileIfNotExist(heapFile.name).openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        return true
    }

    private fun dumpLogcat(file: File) {
        try {
            val process = Runtime.getRuntime().exec("logcat -d")
            file.outputStream().use {
                process.inputStream.copyTo(it)
            }
        } catch (e: Exception) {
            AppLog.put("保存Logcat失败\n$e", e)
        }
    }

}
