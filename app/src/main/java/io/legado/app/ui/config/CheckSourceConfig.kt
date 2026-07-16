package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogCheckSourceConfigBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.CheckSource
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick

class CheckSourceConfig : BaseDialogFragment(R.layout.dialog_check_source_config) {

    private val binding by viewBinding(DialogCheckSourceConfigBinding::bind)

    //允许的最小超时时间，秒
    private val minTimeout = 0L

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.run {
            fun disableInfoSection() {
                checkInfo.isChecked = false
                checkInfo.isEnabled = false
                checkCategory.isChecked = false
                checkContent.isChecked = false
                checkCategory.isEnabled = false
                checkContent.isEnabled = false
            }
            checkDomain.onClick {
                if (!checkSearch.isChecked && !checkDiscovery.isChecked && !checkDomain.isChecked) {
                    checkSearch.isChecked = true
                }
                // 域名校验方式RadioGroup可见性跟随checkDomain
                domainCheckModeGroup.visibility =
                    if (checkDomain.isChecked) View.VISIBLE else View.GONE
            }
            checkSearch.onClick {
                if (!checkSearch.isChecked && !checkDiscovery.isChecked) {
                    disableInfoSection()
                    if (!checkDomain.isChecked) {
                        checkDiscovery.isChecked = true
                    }
                } else {
                    checkInfo.isEnabled = true
                }
            }
            checkDiscovery.onClick {
                if (!checkSearch.isChecked && !checkDiscovery.isChecked) {
                    disableInfoSection()
                    if (!checkDomain.isChecked) {
                        checkSearch.isChecked = true
                    }
                } else {
                    checkInfo.isEnabled = true
                }
            }
            checkInfo.onClick {
                if (!checkInfo.isChecked) {
                    checkCategory.isChecked = false
                    checkContent.isChecked = false
                    checkCategory.isEnabled = false
                    checkContent.isEnabled = false
                } else {
                    checkCategory.isEnabled = true
                }
            }
            checkCategory.onClick {
                if (!checkCategory.isChecked) {
                    checkContent.isChecked = false
                    checkContent.isEnabled = false
                } else {
                    checkContent.isEnabled = true
                }
            }
        }
        CheckSource.run {
            binding.checkSourceTimeout.setText((timeout / 1000).toString())
            binding.wSourceComment.isChecked  = wSourceComment
            binding.checkDomain.isChecked = checkDomain
            // 初始化域名校验方式RadioGroup
            when (domainCheckMode) {
                0 -> binding.rbSocket.isChecked = true
                else -> binding.rbAnalyzeUrl.isChecked = true
            }
            binding.domainCheckModeGroup.visibility =
                if (checkDomain) View.VISIBLE else View.GONE
            binding.checkSearch.isChecked = checkSearch
            binding.checkDiscovery.isChecked = checkDiscovery
            binding.checkInfo.isChecked = checkInfo
            binding.checkCategory.isChecked = checkCategory
            binding.checkContent.isChecked = checkContent
            binding.checkCategory.isEnabled = checkInfo
            binding.checkContent.isEnabled = checkCategory
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
                                getString(
                                    R.string.seconds
                                )
                            }"
                        )
                        return@onClick
                    }
                    else -> timeout = text.toLong() * 1000
                }
                wSourceComment = binding.wSourceComment.isChecked
                checkDomain = binding.checkDomain.isChecked
                // 保存域名校验方式：Socket=0, AnalyzeUrl=1
                domainCheckMode = if (binding.rbSocket.isChecked) 0 else 1
                checkSearch = binding.checkSearch.isChecked
                checkDiscovery = binding.checkDiscovery.isChecked
                checkInfo = binding.checkInfo.isChecked
                checkCategory = binding.checkCategory.isChecked
                checkContent = binding.checkContent.isChecked
                putConfig()
                putPrefString(PreferKey.checkSource, summary)
                dismiss()
            }
        }
    }
}