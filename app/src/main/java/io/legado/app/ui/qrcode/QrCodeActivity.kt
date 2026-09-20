package io.legado.app.ui.qrcode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.zxing.Result
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityQrcodeCaptureBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.readBytes
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.delay

/**
 * 扫码页：二维码结果回传容器（书源/订阅源导入、WebDAV 配置等场景调用）。
 *
 * 本轮优化（见 docs/UI/qrcode/qrcode/OPTIMIZATION.md）：
 * - F188 相册解码等待可见化：解码迁协程（IO）+ loading 遮罩 + 成功微确认（默认态/成功态/失败态闭环）
 * - F189 相机权限受限兜底：库默认「拒绝即 finish」改为停在本页，给出「去授权 / 从相册识别」双出口
 * - A3-3 禁止静默失败：解码结果为空不再 setResult(null)+finish，改为页内 danger 提示条 + 重试
 * - A3-2 扫描引导与全屏识别说明（覆盖层，见 [QrCodeOverlay]）
 */
class QrCodeActivity : BaseActivity<ActivityQrcodeCaptureBinding>(), ScanResultCallback {

    companion object {
        private const val FRAGMENT_TAG = "qrCodeFragment"

        /**
         * 相册解码成功后的微确认时长。蓝图 §1 记为 300ms、优化 3 口径为 300-500ms，
         * 取区间内 400ms：既能被用户感知为「已识别」终点，又不明显拖慢回传。
         */
        private const val DECODE_CONFIRM_DELAY = 400L
    }

    override val binding by viewBinding(ActivityQrcodeCaptureBinding::inflate)

    // —— 覆盖层状态（解码等待/失败、相机权限受限）——
    private var decoding by mutableStateOf(false)
    private var decodeSucceeded by mutableStateOf(false)
    private var decodeFailed by mutableStateOf(false)
    private var cameraBlocked by mutableStateOf(false)

    private val selectQrImage = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { decodeQrImage(it) }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeTopBar()
        initComposeOverlay()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fl_content, QrCodeFragment(), FRAGMENT_TAG)
            .commit()
    }

    override fun onResume() {
        super.onResume()
        // F189 授权闭环：从系统设置授权返回且权限已授予 → 收回兜底卡并恢复预览
        if (cameraBlocked && checkCameraPermission()) {
            cameraBlocked = false
            qrCodeFragment()?.resumeCameraAfterPermissionGranted()
        }
    }

    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = getString(R.string.scan_qr_code),
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() },
                    actions = {
                        // 解码期间置灰相册入口，避免重复触发（覆盖层的触摸拦截是第二道防护）
                        IconButton(
                            enabled = !decoding,
                            onClick = { launchImagePicker() }
                        ) {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                        }
                    }
                )
            }
        }
    }

    private fun initComposeOverlay() {
        binding.composeQrOverlay.setContent {
            LegadoTheme {
                QrCodeOverlay(
                    decoding = decoding,
                    decodeSucceeded = decodeSucceeded,
                    decodeFailed = decodeFailed,
                    cameraBlocked = cameraBlocked,
                    onGalleryClick = { launchImagePicker() },
                    onRetryClick = { launchImagePicker() },
                    onGrantClick = { openAppPermissionSettings() }
                )
            }
        }
    }

    private fun launchImagePicker() {
        selectQrImage.launch {
            mode = HandleFileContract.IMAGE
        }
    }

    private fun openAppPermissionSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    /**
     * 相册解码：读字节 + 位图解码 + 二维码解析全部下沉到 IO 协程（原先在主线程同步执行，
     * 大图期间整页无响应）。成功走微确认后回传；失败留在本页给出提示与重试。
     */
    private fun decodeQrImage(uri: Uri) {
        decoding = true
        decodeSucceeded = false
        decodeFailed = false
        Coroutine.async {
            val bytes = uri.readBytes(this@QrCodeActivity)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw NoStackTraceException("所选图片无法解码为位图")
            QRCodeUtils.parseCodeResult(bitmap)
        }.onSuccess { result ->
            if (result == null) {
                onDecodeFailed()
            } else {
                decodeSucceeded = true
                Coroutine.async { delay(DECODE_CONFIRM_DELAY) }
                    .onSuccess { onScanResultCallback(result) }
            }
        }.onError {
            onDecodeFailed()
        }
    }

    private fun onDecodeFailed() {
        decoding = false
        decodeSucceeded = false
        decodeFailed = true
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun qrCodeFragment(): QrCodeFragment? {
        return supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? QrCodeFragment
    }

    /**
     * 相机权限被拒回调（由 [QrCodeFragment] 转发）。
     *
     * 库默认行为是直接 `finish()`，用户被静默抛回调用方且不知道为什么扫不了；
     * 改为停在本页显示兜底引导卡（去授权 / 从相册识别）。
     */
    fun onCameraPermissionDenied() {
        cameraBlocked = true
    }

    override fun onScanResultCallback(result: Result?) {
        val intent = Intent()
        intent.putExtra("result", result?.text)
        setResult(RESULT_OK, intent)
        finish()
    }

}