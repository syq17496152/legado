package io.legado.app.ui.association

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.DirectLinkUpload
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.ShibbolethCodec
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi

fun AppCompatActivity.showShibbolethDialog(url: String, type: String) {
    val code = createShibboleth(url, type) ?: return
    showComposeTextInputDialog(
        title = getString(R.string.shibboleth),
        hint = getString(R.string.shibboleth),
        initialValue = code,
        readOnly = true,
        positiveText = getString(R.string.copy_text),
        onPositive = { sendToClip(code) }
    )
}

fun Fragment.showShibbolethDialog(url: String, type: String) {
    val code = createShibboleth(url, type) ?: return
    showComposeTextInputDialog(
        title = getString(R.string.shibboleth),
        hint = getString(R.string.shibboleth),
        initialValue = code,
        readOnly = true,
        positiveText = getString(R.string.copy_text),
        onPositive = { requireContext().sendToClip(code) }
    )
}

private fun AppCompatActivity.createShibboleth(url: String, type: String): String? {
    return ShibbolethCodec.encode(
        url = url,
        type = type,
        expiryDays = DirectLinkUpload.getExpiryDate()
    ).getOrElse {
        toastOnUi(R.string.shibboleth_https_only)
        null
    }
}

private fun Fragment.createShibboleth(url: String, type: String): String? {
    return ShibbolethCodec.encode(
        url = url,
        type = type,
        expiryDays = DirectLinkUpload.getExpiryDate()
    ).getOrElse {
        toastOnUi(R.string.shibboleth_https_only)
        null
    }
}
