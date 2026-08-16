package io.legado.app.ui.config

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.BookCover
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.FileOutputStream

/**
 * 欢迎页配置（欢迎图日/夜、显示时间、自定义欢迎）
 * L-E6 S2 改造：内容区 Compose 化（WelcomeConfigScreen），选图/删除/http 下载裁剪逻辑保留 Fragment
 */
class WelcomeConfigFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val requestWelcomeImage = 221
    private val requestWelcomeImageDark = 222

    // Compose 桥接状态（延迟初始化：构造期 requireContext 未 attach 会崩，真实值在 onCreateView 赋值）
    private var showTime by mutableStateOf(500)
    private var customWelcome by mutableStateOf(true)
    private var welcomeImageSummary by mutableStateOf("")
    private var welcomeImageDarkSummary by mutableStateOf("")

    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestWelcomeImage -> setCoverFromUri(PreferKey.welcomeImage, uri)
                requestWelcomeImageDark -> setCoverFromUri(PreferKey.welcomeImageDark, uri)
            }
        }
    }

    // 虽然启动页文字和图标都不显示不太好看，但仍然应该吧权力交给用户，故注释相关代码
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 延迟初始化：真实值（构造期 requireContext 未 attach）
        showTime = getPrefInt(PreferKey.welcomeShowTime, 500)
        customWelcome = getPrefBoolean(PreferKey.customWelcome, true)
        welcomeImageSummary = getPrefString(PreferKey.welcomeImage) ?: getString(R.string.select_image)
        welcomeImageDarkSummary = getPrefString(PreferKey.welcomeImageDark) ?: getString(R.string.select_image)
        return ComposeView(requireContext()).apply {
            setContent {
                LegadoTheme {
                    WelcomeConfigScreen(
                        showTime = showTime,
                        customWelcome = customWelcome,
                        welcomeImageSummary = welcomeImageSummary,
                        welcomeImageDarkSummary = welcomeImageDarkSummary,
                        onShowTimeChange = { value ->
                            showTime = value
                            putPrefInt(PreferKey.welcomeShowTime, value)
                        },
                        onCustomWelcomeChange = { value ->
                            customWelcome = value
                            putPrefBoolean(PreferKey.customWelcome, value)
                        },
                        onWelcomeImageClick = {
                            if (getPrefString(PreferKey.welcomeImage).isNullOrEmpty()) {
                                selectImage.launch {
                                    requestCode = requestWelcomeImage
                                    mode = HandleFileContract.IMAGE
                                }
                            } else {
                                context?.selector(
                                    items = arrayListOf(
                                        getString(R.string.delete),
                                        getString(R.string.select_image)
                                    )
                                ) { _, i ->
                                    if (i == 0) {
                                        removePref(PreferKey.welcomeImage)
                                        welcomeImageSummary = getString(R.string.select_image)
                                        BookCover.upDefaultCover()
                                    } else {
                                        selectImage.launch {
                                            requestCode = requestWelcomeImage
                                            mode = HandleFileContract.IMAGE
                                        }
                                    }
                                }
                            }
                        },
                        onWelcomeImageDarkClick = {
                            if (getPrefString(PreferKey.welcomeImageDark).isNullOrEmpty()) {
                                selectImage.launch {
                                    requestCode = requestWelcomeImageDark
                                    mode = HandleFileContract.IMAGE
                                }
                            } else {
                                context?.selector(
                                    items = arrayListOf(
                                        getString(R.string.delete),
                                        getString(R.string.select_image)
                                    )
                                ) { _, i ->
                                    if (i == 0) {
                                        removePref(PreferKey.welcomeImageDark)
                                        welcomeImageDarkSummary = getString(R.string.select_image)
                                        BookCover.upDefaultCover()
                                    } else {
                                        selectImage.launch {
                                            requestCode = requestWelcomeImageDark
                                            mode = HandleFileContract.IMAGE
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.welcome_style)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireContext().defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        context?.defaultSharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.welcomeShowTime -> {
                showTime = getPrefInt(key, 500)
            }

            PreferKey.customWelcome -> {
                customWelcome = getPrefBoolean(key, true)
            }

            PreferKey.welcomeImage -> {
                welcomeImageSummary = getPrefString(key) ?: getString(R.string.select_image)
            }

            PreferKey.welcomeImageDark -> {
                welcomeImageDarkSummary = getPrefString(key) ?: getString(R.string.select_image)
            }
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi("下载图片中...")
                    val analyzeUrl = AnalyzeUrl(uri.toString())
                    val url = analyzeUrl.urlNoQuery
                    var file = requireContext().externalFiles
                    val res = okHttpClient.newCallResponse(0) {
                        addHeaders(analyzeUrl.headerMap)
                        url(url)
                    }
                    val contentType = res.header("Content-Type") ?: "image/jpeg"
                    val imageType = when {
                        contentType.contains("png", ignoreCase = true) -> "png"
                        contentType.contains("gif", ignoreCase = true) -> "gif"
                        contentType.contains("webp", ignoreCase = true) -> "webp"
                        else -> "jpg"
                    }
                    val suffix = if (url.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        ".$imageType"
                    }
                    val fileName = MD5Utils.md5Encode(url) + suffix
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    // F-P7 按屏幕比例居中裁剪欢迎页图片
                    val dm = context?.resources?.displayMetrics
                    if (dm != null) {
                        BitmapUtils.cropBitmapToAspectRatio(
                            file.absolutePath, dm.widthPixels, dm.heightPixels
                        )
                    }
                    putPrefString(preferenceKey, file.absolutePath)
                    if (preferenceKey == PreferKey.welcomeImage) {
                        welcomeImageSummary = file.absolutePath
                    } else {
                        welcomeImageDarkSummary = file.absolutePath
                    }
                }.onSuccess {
                    appCtx.toastOnUi(R.string.set_success)
                }.onFailure {
                    appCtx.toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                // F-P7 按屏幕比例居中裁剪欢迎页图片
                val dm = context?.resources?.displayMetrics
                if (dm != null) {
                    BitmapUtils.cropBitmapToAspectRatio(
                        file.absolutePath, dm.widthPixels, dm.heightPixels
                    )
                }
                putPrefString(preferenceKey, file.absolutePath)
                if (preferenceKey == PreferKey.welcomeImage) {
                    welcomeImageSummary = file.absolutePath
                } else {
                    welcomeImageDarkSummary = file.absolutePath
                }
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}
