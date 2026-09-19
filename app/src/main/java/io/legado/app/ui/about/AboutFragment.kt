package io.legado.app.ui.about

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.help.update.AppUpdate
import io.legado.app.help.update.AppUpdateConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment

class AboutFragment : ComposeSettingFragment() {

    private val waitDialog by lazy {
        WaitDialog(requireContext())
    }

    override val titleRes: Int = R.string.about

    override val autoOpenTargetItem: Boolean = false

    /**
     * 优化 4（F160）：检查更新的「轻结果态」——命中"已是最新版本"时不再弹框打断，
     * 改为把结果写回本行摘要（✓ 暂无更新 · 刚刚检查）。0 表示尚未检查过。
     */
    private var lastNoUpdateAtMillis: Long = 0L

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        action(
                            key = KEY_CONTRIBUTORS,
                            title = getString(R.string.contributors),
                            summary = getString(R.string.contributors_summary_sigma)
                        ) {
                            openUrl(R.string.contributors_url)
                        },
                        action(
                            key = KEY_UPDATE_LOG,
                            title = getString(R.string.update_log),
                            summary = "${getString(R.string.version)} ${appInfo.versionName}"
                        ) {
                            showMdFile(getString(R.string.update_log), "updateLog.md")
                        },
                        action(
                            key = KEY_CHECK_UPDATE,
                            title = getString(R.string.check_update),
                            summary = checkUpdateSummary()
                        ) {
                            checkUpdate()
                        },
                        action(
                            key = KEY_UPDATE_ACCELERATION,
                            title = getString(R.string.update_acceleration_manage),
                            summary = AppUpdateConfig.summary(requireContext())
                        ) {
                            UpdateAcceleratorDialog.show(this)
                        }
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.other),
                    items = listOf(
                        action(
                            key = KEY_PRIVACY_POLICY,
                            title = getString(R.string.privacy_policy),
                            // 优化 5（F161）：条款行补摘要，用户点开前即可预期文档内容
                            summary = getString(R.string.about_privacy_policy_summary)
                        ) {
                            showMdFile(getString(R.string.privacy_policy), "privacyPolicy.md")
                        },
                        action(
                            key = KEY_LICENSE,
                            title = getString(R.string.license),
                            summary = getString(R.string.about_license_summary)
                        ) {
                            showMdFile(getString(R.string.license), "LICENSE.md")
                        },
                        action(
                            key = KEY_DISCLAIMER,
                            title = getString(R.string.disclaimer),
                            summary = getString(R.string.about_disclaimer_summary)
                        ) {
                            showMdFile(getString(R.string.disclaimer), "disclaimer.md")
                        }
                    )
                )
            ),
            // 优化 6：页脚构建信息（灰字），版本口径与"更新日志"行一致，另附 versionCode 与构建类型
            footer = { AboutBuildFooter() }
        )
    }

    /** 优化 4：检查更新行的轻结果态摘要（未检查过返回 null，保持原单行标题形态）。 */
    private fun checkUpdateSummary(): CharSequence? {
        val at = lastNoUpdateAtMillis
        if (at <= 0L) return null
        val elapsedMinutes = (System.currentTimeMillis() - at) / 60_000L
        val ago = when {
            elapsedMinutes < 1 -> getString(R.string.about_update_just_now)
            elapsedMinutes < 60 -> getString(R.string.about_update_minutes_ago, elapsedMinutes)
            else -> getString(R.string.about_update_hours_ago, elapsedMinutes / 60)
        }
        return getString(R.string.about_update_no_new_inline, ago)
    }

    @Composable
    private fun AboutBuildFooter() {
        val palette = rememberAppSettingPalette()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.about_build_info,
                    appInfo.versionName,
                    BuildConfig.VERSION_CODE.toString(),
                    BuildConfig.BUILD_TYPE
                ),
                color = palette.disabledText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    private fun action(
        key: String,
        title: CharSequence,
        summary: CharSequence? = null,
        onClick: () -> Unit
    ): SettingActionSpec {
        return SettingActionSpec(
            key = key,
            title = title,
            summary = summary,
            onClick = onClick
        )
    }

    @Suppress("SameParameterValue")
    private fun openUrl(@StringRes addressID: Int) {
        requireContext().openUrl(getString(addressID))
    }

    /**
     * 显示md文件
     */
    private fun showMdFile(title: String, fileName: String) {
        val mdText = String(requireContext().assets.open(fileName).readBytes())
        showDialogFragment(TextDialog(title, mdText, TextDialog.Mode.MD))
    }

    /**
     * 检测更新
     */
    private fun checkUpdate() {
        waitDialog.show()
        AppUpdate.preferredUpdate.run {
            check(lifecycleScope)
                .onSuccess {
                    lastNoUpdateAtMillis = 0L
                    refreshSettings()
                    showDialogFragment(
                        UpdateDialog(it)
                    )
                }.onError {
                    if (AppUpdate.isLatestVersionError(it)) {
                        // 优化 4：无新版不弹框，改为行内轻结果态（写回摘要 + 刷新本行）
                        lastNoUpdateAtMillis = System.currentTimeMillis()
                        refreshSettings()
                    } else {
                        showDialogFragment(
                            TextDialog(
                                getString(R.string.check_update),
                                it.localizedMessage ?: getString(R.string.check_update),
                                TextDialog.Mode.TEXT
                            )
                        )
                    }
                }.onFinally {
                    waitDialog.dismiss()
                }
        }
    }

    companion object {
        private const val KEY_CONTRIBUTORS = "contributors"
        private const val KEY_UPDATE_LOG = "update_log"
        private const val KEY_CHECK_UPDATE = "check_update"
        private const val KEY_UPDATE_ACCELERATION = "update_acceleration"
        private const val KEY_LICENSE = "license"
        private const val KEY_DISCLAIMER = "disclaimer"
        private const val KEY_PRIVACY_POLICY = "privacyPolicy"
    }

}