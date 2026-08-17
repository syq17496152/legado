package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getClipText
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.share
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.FileOutputStream

/**
 * 主题设置（L-E2，主题架构 v2 重设计）：内容区全 Compose（[ThemeConfigScreen]），
 * 文件选择/下载/分享等系统交互保留 Fragment。
 *
 * 旧版 PreferenceFragment 实现废弃：色行改 ColorPickerSheet 活预览（MoRealm 思路）、
 * 主题列表改瓦片网格（MD3-DIY 手机模型预览），改色经 ThemeSync 即时全局换肤
 * （本页不重建，ConfigActivity 豁免 RECREATE 重建）。
 */
class ThemeConfigFragment : Fragment() {

    private val requestCodeBgLight = 121
    private val requestCodeBgDark = 122

    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestCodeBgLight -> setBgFromUri(uri, PreferKey.bgImage) { upTheme(false) }
                requestCodeBgDark -> setBgFromUri(uri, PreferKey.bgImageN) { upTheme(true) }
            }
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                LegadoTheme {
                    ThemeConfigScreen(
                        onApplyConfig = { config ->
                            ThemeConfig.applyConfig(requireContext(), config)
                        },
                        onDeleteConfig = { config ->
                            val index = ThemeConfig.configList.indexOf(config)
                            if (index >= 0) {
                                ThemeConfig.delConfig(index)
                            }
                        },
                        onShareConfig = { config ->
                            requireContext().share(GSON.toJson(config), getString(R.string.share))
                        },
                        onImportClick = {
                            val clip = requireContext().getClipText()
                            when {
                                clip.isNullOrEmpty() -> toastOnUi(R.string.cannot_empty)
                                !ThemeConfig.addConfig(clip) -> toastOnUi("格式不对,添加失败")
                            }
                        },
                        onColorChange = { isNightGroup, key, color ->
                            // 背景色明暗守卫（原 ColorPreference.onSaveColor 逻辑）
                            if (key == PreferKey.cBackground && !isNightGroup &&
                                !ColorUtils.isColorLight(color)
                            ) {
                                toastOnUi(R.string.day_background_too_dark)
                            } else if (key == PreferKey.cNBackground && isNightGroup &&
                                ColorUtils.isColorLight(color)
                            ) {
                                toastOnUi(R.string.night_background_too_light)
                            } else {
                                requireContext().putPrefInt(key, color)
                                upTheme(isNightGroup)
                            }
                        },
                        onTransparentNavBarChange = { isNightGroup, checked ->
                            val key = if (isNightGroup) PreferKey.tNavBarN else PreferKey.tNavBar
                            requireContext().putPrefBoolean(key, checked)
                            upTheme(isNightGroup)
                        },
                        onBgImageClick = { isNightGroup ->
                            selectImage.launch {
                                requestCode = if (isNightGroup) {
                                    requestCodeBgDark
                                } else {
                                    requestCodeBgLight
                                }
                                mode = HandleFileContract.IMAGE
                            }
                        },
                        onBgImageDelete = { isNightGroup ->
                            val key = if (isNightGroup) PreferKey.bgImageN else PreferKey.bgImage
                            requireContext().removePref(key)
                            upTheme(isNightGroup)
                        },
                        onBlurringChange = { isNightGroup, value ->
                            val key = if (isNightGroup) {
                                PreferKey.bgImageNBlurring
                            } else {
                                PreferKey.bgImageBlurring
                            }
                            requireContext().putPrefInt(key, value)
                            upTheme(isNightGroup)
                        },
                        onSaveTheme = { isNightGroup, name ->
                            if (isNightGroup) {
                                ThemeConfig.saveNightTheme(requireContext(), name)
                            } else {
                                ThemeConfig.saveDayTheme(requireContext(), name)
                            }
                            toastOnUi(R.string.set_success)
                        },
                        onTransparentStatusBarChange = { checked ->
                            requireContext()
                                .putPrefBoolean(PreferKey.transparentStatusBar, checked)
                            recreateActivities()
                        },
                        onImmNavigationBarChange = { checked ->
                            requireContext().putPrefBoolean(PreferKey.immNavigationBar, checked)
                            recreateActivities()
                        },
                        onElevationChange = { value ->
                            AppConfig.elevation = value
                            recreateActivities()
                        },
                        onFontScaleChange = { value ->
                            requireContext().putPrefInt(PreferKey.fontScale, value)
                            recreateActivities()
                        },
                        onCoverConfigClick = {
                            startActivity<ConfigActivity> {
                                putExtra("configTag", ConfigTag.COVER_CONFIG)
                            }
                        },
                        onWelcomeConfigClick = {
                            startActivity<ConfigActivity> {
                                putExtra("configTag", ConfigTag.WELCOME_CONFIG)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.theme_setting)
        // L-E2 S2：菜单迁移至 ConfigActivity Compose 顶栏（日夜切换）
        (activity as? ConfigActivity)?.setTopBarMenu(
            listOf(
                MenuAction(
                    icon = Icons.Filled.DarkMode,
                    title = getString(R.string.theme_mode),
                    onClick = {
                        AppConfig.isNightTheme = !AppConfig.isNightTheme
                        ThemeConfig.applyDayNight(requireContext())
                    }
                )
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 防止 ConfigActivity 顶栏残留本页菜单
        (activity as? ConfigActivity)?.setTopBarMenu(emptyList())
    }

    /**
     * 应用主题：当前模式组实时生效（applyTheme→ThemeSync.bump 即时全局换肤）；
     * 非当前模式组已保存偏好，切换模式后生效（toast 明确反馈，消除「设置无效」观感）。
     */
    private fun upTheme(isNightGroup: Boolean) {
        if (AppConfig.isNightTheme == isNightGroup) {
            ThemeConfig.applyTheme(requireContext())
            recreateActivities()
        } else {
            val modeName = getString(if (isNightGroup) R.string.night else R.string.day)
            toastOnUi(getString(R.string.theme_saved_pending_mode, modeName))
        }
    }

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }

    private fun setBgFromUri(uri: Uri, preferenceKey: String, success: () -> Unit) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi("下载背景图片中...")
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
                    file = FileUtils.createFileIfNotExist(file, preferenceKey, fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    requireContext().putPrefString(preferenceKey, file.absolutePath)
                    if (isAdded && context != null) {
                        success()
                    }
                }.onSuccess {
                    appCtx.toastOnUi("设定成功")
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
                file = FileUtils.createFileIfNotExist(file, preferenceKey, fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                requireContext().putPrefString(preferenceKey, file.absolutePath)
                success()
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}
