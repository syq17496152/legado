package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogCheckRssSourceConfigBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.CheckRssSource
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick

/**
 * 订阅源校验设置（参考 [CheckSourceConfig]）
 */
class CheckRssSourceConfig : BaseDialogFragment(R.layout.dialog_check_rss_source_config) {

    private val binding by viewBinding(DialogCheckRssSourceConfigBinding::bind)

    // 允许的最小超时时间，秒
    private val minTimeout = 0L

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.checkDomain.onClick {
            // RadioGroup 可见性跟随 checkDomain
            binding.domainCheckModeGroup.visibility =
                if (binding.checkDomain.isChecked) View.VISIBLE else View.GONE
        }
        CheckRssSource.run {
            binding.checkSourceTimeout.setText((timeout / 1000).toString())
            binding.enableDedup.isChecked = enableDedup
            binding.checkDomain.isChecked = checkDomain
            // 初始化域名校验方式
            when (domainCheckMode) {
                0 -> binding.rbSocket.isChecked = true
                else -> binding.rbAnalyzeUrl.isChecked = true
            }
            binding.domainCheckModeGroup.visibility =
                if (checkDomain) View.VISIBLE else View.GONE
            binding.checkArticles.isChecked = checkArticles
            binding.checkSearch.isChecked = checkSearch
            binding.checkSort.isChecked = checkSort
            binding.checkContent.isChecked = checkContent
            binding.tvCancel.onClick {
                dismiss()
            }
            binding.tvOk.onClick {
                val text = binding.checkSourceTimeout.text.toString()
                when {
                    text.isBlank() -> {
                        toastOnUi("${getString(R.string.timeout)}${getString(R.string.cannot_empty)}")
                        return@onClick
                    }
                    text.toLong() <= minTimeout -> {
                        toastOnUi(
                            "${getString(R.string.timeout)}${getString(R.string.less_than)}${minTimeout}${
                                getString(R.string.seconds)
                            }"
                        )
                        return@onClick
                    }
                    else -> timeout = text.toLong() * 1000
                }
                enableDedup = binding.enableDedup.isChecked
                checkDomain = binding.checkDomain.isChecked
                domainCheckMode = if (binding.rbSocket.isChecked) 0 else 1
                checkArticles = binding.checkArticles.isChecked
                checkSearch = binding.checkSearch.isChecked
                checkSort = binding.checkSort.isChecked
                checkContent = binding.checkContent.isChecked
                putConfig()
                dismiss()
            }
        }
    }
}
