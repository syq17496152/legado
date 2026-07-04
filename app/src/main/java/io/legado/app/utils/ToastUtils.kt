@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.legado.app.BuildConfig
import io.legado.app.databinding.ViewToastBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import splitties.systemservices.layoutInflater

private var toast: Toast? = null

private var toastLegacy: Toast? = null

// Android 11 (API 30) 禁止自定义 Toast View，此标志控制是否使用自定义 Toast
private val useCustomToast: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

fun Context.toastOnUi(message: Int, duration: Int = Toast.LENGTH_SHORT) {
    toastOnUi(getString(message), duration)
}

@SuppressLint("InflateParams")
@Suppress("DEPRECATION")
fun Context.toastOnUi(message: CharSequence?, duration: Int = Toast.LENGTH_SHORT) {
    runOnUI {
        kotlin.runCatching {
            if (useCustomToast) {
                toast?.cancel()
                toast = Toast(this)
                val isLight = ColorUtils.isColorLight(bottomBackground)
                ViewToastBinding.inflate(layoutInflater).run {
                    toast?.view = root
                    cvToast.setCardBackgroundColor(bottomBackground)
                    tvText.setTextColor(getPrimaryTextColor(isLight))
                    tvText.text = message
                }
                toast?.duration = duration
                toast?.show()
            } else {
                // Android 11+ 降级为系统 Toast
                toastOnUiLegacy(message ?: "", duration)
            }
        }
    }
}

fun Context.toastOnUiLegacy(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    runOnUI {
        kotlin.runCatching {
            if (toastLegacy == null || BuildConfig.DEBUG || AppConfig.recordLog) {
                toastLegacy = Toast.makeText(this, message, duration)
            } else {
                toastLegacy?.setText(message)
                toastLegacy?.duration = duration
            }
            toastLegacy?.show()
        }
    }
}

fun Context.longToastOnUi(message: Int) {
    toastOnUi(message, Toast.LENGTH_LONG)
}

fun Context.longToastOnUi(message: CharSequence?) {
    toastOnUi(message, Toast.LENGTH_LONG)
}

fun Context.longToastOnUiLegacy(message: CharSequence) {
    toastOnUiLegacy(message, Toast.LENGTH_LONG)
}

fun Fragment.toastOnUi(message: Int) = requireActivity().toastOnUi(message)

fun Fragment.toastOnUi(message: CharSequence) = requireActivity().toastOnUi(message)

fun Fragment.longToast(message: Int) = requireContext().longToastOnUi(message)

fun Fragment.longToast(message: CharSequence) = requireContext().longToastOnUi(message)
