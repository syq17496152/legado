package io.legado.app.ui.widget.compose

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.app.Dialog
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx
import io.legado.app.utils.LogUtils
import io.legado.app.utils.setBackgroundKeepPadding
import io.legado.app.utils.setLayout
import io.legado.app.utils.windowSize
import splitties.systemservices.windowManager

abstract class ComposeDialogFragment : DialogFragment() {

    protected open val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Center
    protected open val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    protected open val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    protected open val dialogSize: AppDialogSize? = null
    protected open val widthFraction: Float? get() = dialogSize?.widthFraction
    protected open val maxWidthDp: Int? get() = dialogSize?.maxWidthDp
    protected open val dialogGravity: Int = Gravity.CENTER
    protected open val dialogWindowAnimations: Int = R.style.AnimDialogCenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, if (AppConfig.isEInkMode) 0 else dialogTheme)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            (this as? ComponentDialog)?.onBackPressedDispatcher?.addCallback(
                this@ComposeDialogFragment,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        handleDialogBack()
                    }
                }
            )
            setOnKeyListener { _, keyCode, event ->
                keyCode == KeyEvent.KEYCODE_BACK &&
                        event.action == KeyEvent.ACTION_UP &&
                        handleDialogBack()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val attr = window.attributes
            attr.gravity = dialogGravity
            if (AppConfig.isEInkMode) {
                attr.dimAmount = 0f
                attr.windowAnimations = 0
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            } else {
                attr.windowAnimations = dialogWindowAnimations
            }
            window.attributes = attr
            window.setBackgroundDrawableResource(R.color.transparent)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            if (AppConfig.isEInkMode) {
                window.decorView.setBackgroundKeepPadding(R.color.transparent)
            }
        }
        if (AppConfig.isEInkMode) {
            applyEInkDialogBorder()
        }
        val fraction = widthFraction
        val maxDp = maxWidthDp
        if (fraction != null || maxDp != null) {
            val dm = requireContext().windowManager.windowSize
            val target = (dm.widthPixels * (fraction ?: 1f)).toInt()
            val maxWidth = maxDp?.dpToPx() ?: target
            dialog?.window?.setLayout(minOf(target, maxWidth), dialogHeight)
        } else {
            setLayout(dialogWidth, dialogHeight)
        }
    }

    private fun handleDialogBack(): Boolean {
        if (isCancelable) {
            dismissAllowingStateLoss()
        }
        return true
    }

    private fun applyEInkDialogBorder() {
        val contentView = view ?: return
        when (dialogGravity) {
            Gravity.TOP -> contentView.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            Gravity.BOTTOM -> contentView.setBackgroundResource(R.drawable.bg_eink_border_top)
            else -> {
                val padding = 2.dpToPx()
                contentView.setPadding(padding, padding, padding, padding)
                contentView.setBackgroundResource(R.drawable.bg_eink_border_dialog)
            }
        }
    }

    override fun show(manager: androidx.fragment.app.FragmentManager, tag: String?) {
        kotlin.runCatching {
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            LogUtils.e("ComposeDialogFragment", "show failed: ${it.stackTraceToString()}")
        }
    }
}
