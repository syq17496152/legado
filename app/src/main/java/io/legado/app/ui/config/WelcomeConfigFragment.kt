package io.legado.app.ui.config

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.BookCover
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSliderSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.FileOutputStream

/**
 * 欢迎页配置（欢迎图日/夜、显示时间、自定义欢迎）
 * L-E6 S2 → E3 7.11ak 升级：内容区 Compose 化（ComposeSettingFragment，对齐 Archive），
 * 保留主项目增强：F-P7 欢迎图按屏幕比例居中裁剪。
 */
class WelcomeConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.welcome_style

    private val requestWelcomeImage = 221
    private val requestWelcomeImageDark = 222

    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestWelcomeImage -> setCoverFromUri(PreferKey.welcomeImage, uri)
                requestWelcomeImageDark -> setCoverFromUri(PreferKey.welcomeImageDark, uri)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val welcomeShowTime = intSetting(PreferKey.welcomeShowTime, 0)
        val safeWelcomeShowTime = welcomeShowTime.coerceIn(0, 800)
        if (welcomeShowTime != safeWelcomeShowTime) {
            updateIntSetting(PreferKey.welcomeShowTime, safeWelcomeShowTime)
        }
    }

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingSliderSpec(
                            key = PreferKey.welcomeShowTime,
                            title = getString(R.string.welcome_show_time),
                            summary = getString(R.string.welcome_show_time_summary),
                            value = intSetting(PreferKey.welcomeShowTime, 0).coerceIn(0, 800),
                            valueRange = 0..800,
                            onValueChange = {
                                updateIntSetting(PreferKey.welcomeShowTime, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.customWelcome,
                            title = getString(R.string.custom_welcome),
                            summary = getString(R.string.custom_welcome_summary),
                            checked = booleanSetting(PreferKey.customWelcome, false),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.customWelcome, it)
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.day),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.welcomeImage,
                            title = getString(R.string.background_image),
                            summary = imageSummary(AppConfig.welcomeImage),
                            onClick = {
                                showImageActions(
                                    key = PreferKey.welcomeImage,
                                    requestCode = requestWelcomeImage
                                )
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowText,
                            title = getString(R.string.show_welcome_text),
                            summary = getString(R.string.welcome_text),
                            checked = booleanSetting(PreferKey.welcomeShowText, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowText, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowIcon,
                            title = getString(R.string.show_icon),
                            summary = getString(R.string.show_default_book_icon),
                            checked = booleanSetting(PreferKey.welcomeShowIcon, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowIcon, it)
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.night),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.welcomeImageDark,
                            title = getString(R.string.background_image),
                            summary = imageSummary(AppConfig.welcomeImageDark),
                            onClick = {
                                showImageActions(
                                    key = PreferKey.welcomeImageDark,
                                    requestCode = requestWelcomeImageDark
                                )
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowTextDark,
                            title = getString(R.string.show_welcome_text),
                            summary = getString(R.string.welcome_text),
                            checked = booleanSetting(PreferKey.welcomeShowTextDark, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowTextDark, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.welcomeShowIconDark,
                            title = getString(R.string.show_icon),
                            summary = getString(R.string.show_default_book_icon),
                            checked = booleanSetting(PreferKey.welcomeShowIconDark, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.welcomeShowIconDark, it)
                            }
                        )
                    )
                )
            )
        )
    }

    private fun imageSummary(value: String?): String {
        return if (value.isNullOrBlank()) {
            getString(R.string.select_image)
        } else {
            value
        }
    }

    private fun showImageActions(
        key: String,
        requestCode: Int
    ) {
        if (stringSetting(key, "").isBlank()) {
            launchImagePicker(requestCode)
            return
        }
        showComposeActionListDialog(
            title = getString(R.string.select_image),
            labels = listOf(
                getString(R.string.delete),
                getString(R.string.select_image)
            ),
            dangerIndices = setOf(0),
            negativeText = getString(R.string.cancel),
            onSelected = { index ->
                if (index == 0) {
                    removePref(key)
                    BookCover.upDefaultCover()
                } else {
                    launchImagePicker(requestCode)
                }
            }
        )
    }

    private fun launchImagePicker(requestCode: Int) {
        selectImage.launch {
            this.requestCode = requestCode
            mode = HandleFileContract.IMAGE
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        putPrefBoolean(PreferKey.customWelcome, true)
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi(R.string.downloading_image)
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
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}
