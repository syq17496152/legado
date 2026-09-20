package io.legado.app.ui.qrcode

import android.Manifest
import com.google.zxing.Result
import com.king.camera.scan.AnalyzeResult
import com.king.camera.scan.CameraScan
import com.king.camera.scan.util.PermissionUtils
import com.king.zxing.BarcodeCameraScanFragment
import com.king.zxing.DecodeConfig
import com.king.zxing.DecodeFormatManager
import com.king.zxing.analyze.MultiFormatAnalyzer

class QrCodeFragment : BarcodeCameraScanFragment() {

    override fun initCameraScan(cameraScan: CameraScan<Result>) {
        super.initCameraScan(cameraScan)
        //初始化解码配置
        val decodeConfig = DecodeConfig()
        //如果只有识别二维码的需求，这样设置效率会更高，不设置默认为DecodeFormatManager.DEFAULT_HINTS
        decodeConfig.hints = DecodeFormatManager.QR_CODE_HINTS
        //设置是否全区域识别，默认false
        decodeConfig.isFullAreaScan = true
        //设置识别区域比例，默认0.8，设置的比例最终会在预览区域裁剪基于此比例的一个矩形进行扫码识别
        decodeConfig.areaRectRatio = 0.8f

        //在启动预览之前，设置分析器，只识别二维码
        cameraScan.setAnalyzer(MultiFormatAnalyzer(decodeConfig))
    }

    override fun onScanResultCallback(result: AnalyzeResult<Result>) {
        cameraScan.setAnalyzeImage(false)
        (activity as? QrCodeActivity)?.onScanResultCallback(result.result)
    }

    /**
     * 相机权限结果分流。
     *
     * 库默认实现：授权失败直接 `requireActivity().finish()` —— 用户被静默抛回调用方，
     * 既不知道原因也不知道还有相册识别这条替代路径（F189 死态）。
     * 本页改为把「受限态」上抛给 Activity 显示兜底引导卡；授权成功仍走库原生 [startCamera]。
     */
    override fun requestCameraPermissionResult(
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (PermissionUtils.requestPermissionsResult(
                Manifest.permission.CAMERA, permissions, grantResults
            )
        ) {
            startCamera()
        } else {
            (activity as? QrCodeActivity)?.onCameraPermissionDenied()
        }
    }

    /** F189 授权闭环：用户跳系统设置授权后返回、权限已授予时由 Activity 调用恢复预览 */
    fun resumeCameraAfterPermissionGranted() {
        startCamera()
    }

}