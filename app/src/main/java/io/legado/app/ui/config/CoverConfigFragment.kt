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
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.BookCover
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream

/**
 * 封面配置（仅Wifi加载封面 / 封面规则 / 始终显示默认封面 / 日·夜默认封面 + 书名·作者显隐）
 * L-E3 S2 改造：内容区 Compose 化（CoverConfigScreen），选图/删除/SharedPreferences 监听逻辑保留 Fragment
 */
class CoverConfigFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val requestCodeCover = 111
    private val requestCodeCoverDark = 112

    // Compose 桥接状态（延迟初始化：构造期 requireContext 未 attach 会崩，真实值在 onCreateView 赋值）
    private var loadCoverOnlyWifi by mutableStateOf(false)
    private var useDefaultCover by mutableStateOf(false)
    private var coverShowName by mutableStateOf(true)
    private var coverShowAuthor by mutableStateOf(true)
    private var coverShowNameN by mutableStateOf(true)
    private var coverShowAuthorN by mutableStateOf(true)
    private var defaultCoverSummary by mutableStateOf("")
    private var defaultCoverDarkSummary by mutableStateOf("")

    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestCodeCover -> setCoverFromUri(PreferKey.defaultCover, uri)
                requestCodeCoverDark -> setCoverFromUri(PreferKey.defaultCoverDark, uri)
            }
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 延迟初始化：真实值（构造期 requireContext 未 attach）
        loadCoverOnlyWifi = getPrefBoolean(PreferKey.loadCoverOnlyWifi, false)
        useDefaultCover = getPrefBoolean(PreferKey.useDefaultCover, false)
        coverShowName = getPrefBoolean(PreferKey.coverShowName, true)
        coverShowAuthor = getPrefBoolean(PreferKey.coverShowAuthor, true)
        coverShowNameN = getPrefBoolean(PreferKey.coverShowNameN, true)
        coverShowAuthorN = getPrefBoolean(PreferKey.coverShowAuthorN, true)
        defaultCoverSummary = getPrefString(PreferKey.defaultCover) ?: getString(R.string.select_image)
        defaultCoverDarkSummary = getPrefString(PreferKey.defaultCoverDark) ?: getString(R.string.select_image)
        return ComposeView(requireContext()).apply {
            setContent {
                LegadoTheme {
                    CoverConfigScreen(
                        loadCoverOnlyWifi = loadCoverOnlyWifi,
                        useDefaultCover = useDefaultCover,
                        coverShowName = coverShowName,
                        coverShowAuthor = coverShowAuthor,
                        coverShowNameN = coverShowNameN,
                        coverShowAuthorN = coverShowAuthorN,
                        defaultCoverSummary = defaultCoverSummary,
                        defaultCoverDarkSummary = defaultCoverDarkSummary,
                        onLoadCoverOnlyWifiChange = { value ->
                            loadCoverOnlyWifi = value
                            putPrefBoolean(PreferKey.loadCoverOnlyWifi, value)
                        },
                        onUseDefaultCoverChange = { value ->
                            useDefaultCover = value
                            putPrefBoolean(PreferKey.useDefaultCover, value)
                        },
                        onCoverRuleClick = { showDialogFragment(CoverRuleConfigDialog()) },
                        onCoverShowNameChange = { value ->
                            coverShowName = value
                            putPrefBoolean(PreferKey.coverShowName, value)
                            BookCover.upDefaultCover()
                        },
                        onCoverShowAuthorChange = { value ->
                            coverShowAuthor = value
                            putPrefBoolean(PreferKey.coverShowAuthor, value)
                            BookCover.upDefaultCover()
                        },
                        onCoverShowNameNChange = { value ->
                            coverShowNameN = value
                            putPrefBoolean(PreferKey.coverShowNameN, value)
                            BookCover.upDefaultCover()
                        },
                        onCoverShowAuthorNChange = { value ->
                            coverShowAuthorN = value
                            putPrefBoolean(PreferKey.coverShowAuthorN, value)
                            BookCover.upDefaultCover()
                        },
                        onDefaultCoverClick = {
                            if (getPrefString(PreferKey.defaultCover).isNullOrEmpty()) {
                                selectImage.launch {
                                    requestCode = requestCodeCover
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
                                        removePref(PreferKey.defaultCover)
                                        defaultCoverSummary = getString(R.string.select_image)
                                        BookCover.upDefaultCover()
                                    } else {
                                        selectImage.launch {
                                            requestCode = requestCodeCover
                                            mode = HandleFileContract.IMAGE
                                        }
                                    }
                                }
                            }
                        },
                        onDefaultCoverDarkClick = {
                            if (getPrefString(PreferKey.defaultCoverDark).isNullOrEmpty()) {
                                selectImage.launch {
                                    requestCode = requestCodeCoverDark
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
                                        removePref(PreferKey.defaultCoverDark)
                                        defaultCoverDarkSummary = getString(R.string.select_image)
                                        BookCover.upDefaultCover()
                                    } else {
                                        selectImage.launch {
                                            requestCode = requestCodeCoverDark
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
        activity?.setTitle(R.string.cover_config)
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
            PreferKey.defaultCover -> {
                defaultCoverSummary = getPrefString(key) ?: getString(R.string.select_image)
            }

            PreferKey.defaultCoverDark -> {
                defaultCoverDarkSummary = getPrefString(key) ?: getString(R.string.select_image)
            }

            PreferKey.coverShowName -> {
                coverShowName = getPrefBoolean(key, true)
                BookCover.upDefaultCover()
            }

            PreferKey.coverShowNameN -> {
                coverShowNameN = getPrefBoolean(key, true)
                BookCover.upDefaultCover()
            }

            PreferKey.coverShowAuthor -> {
                coverShowAuthor = getPrefBoolean(key, true)
                BookCover.upDefaultCover()
            }

            PreferKey.coverShowAuthorN -> {
                coverShowAuthorN = getPrefBoolean(key, true)
                BookCover.upDefaultCover()
            }
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
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
                putPrefString(preferenceKey, file.absolutePath)
                if (preferenceKey == PreferKey.defaultCover) {
                    defaultCoverSummary = file.absolutePath
                } else {
                    defaultCoverDarkSummary = file.absolutePath
                }
                BookCover.upDefaultCover()
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}
