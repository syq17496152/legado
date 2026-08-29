package io.legado.app.ui.about

import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.help.update.AppUpdate
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
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
                            title = getString(R.string.check_update)
                        ) {
                            checkUpdate()
                        }
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.other),
                    items = listOf(
                        action(
                            key = KEY_PRIVACY_POLICY,
                            title = getString(R.string.privacy_policy)
                        ) {
                            showMdFile(getString(R.string.privacy_policy), "privacyPolicy.md")
                        },
                        action(
                            key = KEY_LICENSE,
                            title = getString(R.string.license)
                        ) {
                            showMdFile(getString(R.string.license), "LICENSE.md")
                        },
                        action(
                            key = KEY_DISCLAIMER,
                            title = getString(R.string.disclaimer)
                        ) {
                            showMdFile(getString(R.string.disclaimer), "disclaimer.md")
                        }
                    )
                )
            )
        )
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
                    showDialogFragment(
                        UpdateDialog(it)
                    )
                }.onError {
                    showDialogFragment(
                        TextDialog(
                            getString(R.string.check_update),
                            if (AppUpdate.isLatestVersionError(it)) {
                                getString(R.string.update_no_new_version)
                            } else {
                                it.localizedMessage ?: getString(R.string.check_update)
                            },
                            TextDialog.Mode.TEXT
                        )
                    )
                }.onFinally {
                    waitDialog.dismiss()
                }
        }
    }

    companion object {
        private const val KEY_CONTRIBUTORS = "contributors"
        private const val KEY_UPDATE_LOG = "update_log"
        private const val KEY_CHECK_UPDATE = "check_update"
        private const val KEY_LICENSE = "license"
        private const val KEY_DISCLAIMER = "disclaimer"
        private const val KEY_PRIVACY_POLICY = "privacyPolicy"
    }

}