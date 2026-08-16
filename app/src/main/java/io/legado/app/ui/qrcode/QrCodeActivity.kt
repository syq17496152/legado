package io.legado.app.ui.qrcode

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.google.zxing.Result
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityQrcodeCaptureBinding
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.readBytes
import io.legado.app.utils.viewbindingdelegate.viewBinding

class QrCodeActivity : BaseActivity<ActivityQrcodeCaptureBinding>(), ScanResultCallback {

    override val binding by viewBinding(ActivityQrcodeCaptureBinding::inflate)

    private val selectQrImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.readBytes(this)?.let { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            onScanResultCallback(QRCodeUtils.parseCodeResult(bitmap))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeTopBar()
        val fTag = "qrCodeFragment"
        val qrCodeFragment = QrCodeFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fl_content, qrCodeFragment, fTag)
            .commit()
    }

    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = getString(R.string.scan_qr_code),
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() },
                    actions = {
                        IconButton(
                            onClick = {
                                selectQrImage.launch {
                                    mode = HandleFileContract.IMAGE
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                        }
                    }
                )
            }
        }
    }

    override fun onScanResultCallback(result: Result?) {
        val intent = Intent()
        intent.putExtra("result", result?.text)
        setResult(RESULT_OK, intent)
        finish()
    }

}