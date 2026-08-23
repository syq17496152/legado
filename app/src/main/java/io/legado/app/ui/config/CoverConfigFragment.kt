package io.legado.app.ui.config

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.model.BookCover
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.FileOutputStream
import java.util.UUID

/**
 * 封面配置（仅Wifi加载封面 / 封面规则 / 始终显示默认封面 / 封面集合管理 / 日·夜默认封面+集合 + 书名·作者显隐）
 * L-E3 S2 → E3 7.11ak 升级：内容区 Compose 化（ComposeSettingFragment，对齐 Archive），
 * 保留主项目增强：日/夜默认封面选图 + 删除（defaultCover / defaultCoverDark）。
 */
class CoverConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.cover_config

    private val requestCodeCover = 111
    private val requestCodeCoverDark = 112

    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestCodeCover -> setCoverFromUri(PreferKey.defaultCover, uri)
                requestCodeCoverDark -> setCoverFromUri(PreferKey.defaultCoverDark, uri)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            CoverCollectionManager.load(false)
            CoverCollectionManager.load(true)
            refreshSettings()
        }
    }

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingSwitchSpec(
                            key = "loadCoverOnlyWifi",
                            title = getString(R.string.only_wifi),
                            summary = getString(R.string.only_wifi_summary),
                            checked = booleanSetting("loadCoverOnlyWifi", false),
                            onCheckedChange = {
                                updateBooleanSetting("loadCoverOnlyWifi", it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = "loadCoverHighQuality",
                            title = getString(R.string.load_cover_high_quality),
                            summary = getString(R.string.load_cover_high_quality_summary),
                            checked = booleanSetting("loadCoverHighQuality", false),
                            onCheckedChange = {
                                updateBooleanSetting("loadCoverHighQuality", it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.bookCoverShadow,
                            title = getString(R.string.book_cover_shadow),
                            summary = getString(R.string.book_cover_shadow_summary),
                            checked = booleanSetting(PreferKey.bookCoverShadow, true),
                            searchKeys = listOf("coverShadow", "cover_shadow", "shadow"),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.bookCoverShadow, it)
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_COVER_RULE,
                            title = getString(R.string.cover_rule),
                            summary = getString(R.string.cover_rule_summary),
                            onClick = {
                                showDialogFragment(CoverRuleConfigDialog())
                            }
                        ),
                        SettingSwitchSpec(
                            key = "useDefaultCover",
                            title = getString(R.string.use_default_cover),
                            summary = getString(R.string.use_default_cover_s),
                            checked = booleanSetting("useDefaultCover", false),
                            onCheckedChange = {
                                updateBooleanSetting("useDefaultCover", it)
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_COVER_COLLECTION_MANAGE,
                            title = getString(R.string.cover_collection_manage),
                            onClick = {
                                startActivity<CoverCollectionManageActivity>()
                            }
                        )
                    )
                ),
                coverCollectionSection(isNight = false),
                coverCollectionSection(isNight = true)
            )
        )
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.coverCollectionDay,
            PreferKey.coverCollectionNight,
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark -> refreshSettings()

            PreferKey.coverShowName,
            PreferKey.coverShowNameN -> {
                BookCover.upDefaultCover()
                refreshSettings()
            }

            PreferKey.coverShowAuthor,
            PreferKey.coverShowAuthorN,
            PreferKey.coverCollectionModeDay,
            PreferKey.coverCollectionModeNight,
            PreferKey.bookCoverShadow -> refreshCoverCollection()
        }
    }

    private fun coverCollectionSection(isNight: Boolean): SettingSectionSpec {
        val collectionKey = if (isNight) {
            PreferKey.coverCollectionNight
        } else {
            PreferKey.coverCollectionDay
        }
        val modeKey = if (isNight) {
            PreferKey.coverCollectionModeNight
        } else {
            PreferKey.coverCollectionModeDay
        }
        val defaultCoverKey = if (isNight) PreferKey.defaultCoverDark else PreferKey.defaultCover
        val showNameKey = if (isNight) PreferKey.coverShowNameN else PreferKey.coverShowName
        val showAuthorKey = if (isNight) PreferKey.coverShowAuthorN else PreferKey.coverShowAuthor
        val showName = booleanSetting(showNameKey, true)
        val mode = stringSetting(modeKey, CoverCollectionManager.MODE_RANDOM)
        return SettingSectionSpec(
            title = getString(if (isNight) R.string.night else R.string.day),
            items = listOf(
                SettingActionSpec(
                    key = defaultCoverKey,
                    title = getString(R.string.default_cover),
                    summary = imageSummary(stringSetting(defaultCoverKey, "")),
                    onClick = { showDefaultCoverActions(isNight) }
                ),
                SettingActionSpec(
                    key = collectionKey,
                    title = getString(R.string.cover_collection_select),
                    summary = coverCollectionSummary(isNight, stringSetting(collectionKey, "")),
                    onClick = { selectCoverCollection(isNight) }
                ),
                SettingChoiceSpec(
                    key = modeKey,
                    title = getString(R.string.cover_collection_mode),
                    options = coverModeOptions(),
                    selectedValue = mode,
                    summary = coverModeLabel(mode),
                    onSelected = {
                        updateStringSetting(modeKey, it)
                    }
                ),
                SettingSwitchSpec(
                    key = showNameKey,
                    title = getString(R.string.cover_show_name),
                    summary = getString(R.string.cover_show_name_summary),
                    checked = showName,
                    onCheckedChange = {
                        updateBooleanSetting(showNameKey, it)
                    }
                ),
                SettingSwitchSpec(
                    key = showAuthorKey,
                    title = getString(R.string.cover_show_author),
                    summary = getString(R.string.cover_show_author_summary),
                    checked = booleanSetting(showAuthorKey, true),
                    enabled = showName,
                    onCheckedChange = {
                        updateBooleanSetting(showAuthorKey, it)
                    }
                )
            )
        )
    }

    private fun coverModeOptions(): List<SettingChoiceOption> {
        val entries = resources.getStringArray(R.array.cover_collection_mode_entries)
        val values = resources.getStringArray(R.array.cover_collection_mode_values)
        return values.mapIndexed { index, value ->
            SettingChoiceOption(
                value = value,
                label = entries.getOrElse(index) { value }
            )
        }
    }

    private fun coverModeLabel(value: String): String {
        return coverModeOptions()
            .firstOrNull { it.value == value }
            ?.label
            ?.toString()
            .orEmpty()
    }

    private fun coverCollectionSummary(
        isNight: Boolean,
        selectedId: String?
    ): String {
        return CoverCollectionManager.cachedName(isNight, selectedId)
            ?: getString(R.string.cover_collection_none)
    }

    private fun imageSummary(value: String?): String {
        return if (value.isNullOrBlank()) {
            getString(R.string.select_image)
        } else {
            value
        }
    }

    private fun selectCoverCollection(isNight: Boolean) {
        lifecycleScope.launch {
            val collections = CoverCollectionManager.load(isNight)
            val labels = listOf(getString(R.string.cover_collection_none)) +
                collections.map { "${it.name} (${it.images.size})" }
            val selectedId = stringSetting(
                if (isNight) PreferKey.coverCollectionNight else PreferKey.coverCollectionDay,
                ""
            )
            showComposeChoiceListDialog(
                title = getString(R.string.cover_collection_select),
                labels = labels,
                selectedIndex = if (selectedId.isBlank()) {
                    0
                } else {
                    collections.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
                        ?.plus(1)
                        ?: -1
                }
            ) { index ->
                val selected = if (index <= 0) null else collections.getOrNull(index - 1)
                CoverCollectionManager.setSelected(isNight, selected?.id)
                lifecycleScope.launch {
                    AppearanceKitManager.syncCurrentCoverCollectionRef(isNight, selected)
                }
                refreshSettings()
                refreshCoverCollection()
            }
        }
    }

    private fun showDefaultCoverActions(isNight: Boolean) {
        val key = if (isNight) PreferKey.defaultCoverDark else PreferKey.defaultCover
        val requestCode = if (isNight) requestCodeCoverDark else requestCodeCover
        if (stringSetting(key, "").isBlank()) {
            launchImagePicker(requestCode)
            return
        }
        showComposeActionListDialog(
            title = getString(R.string.default_cover),
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
                    refreshSettings()
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
                BookCover.upDefaultCover()
                refreshSettings()
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun refreshCoverCollection() {
        BookCover.upDefaultCover()
        postEvent(EventBus.BOOKSHELF_REFRESH, UUID.randomUUID().toString())
        postEvent(EventBus.REFRESH_BOOK_INFO, false)
    }

    companion object {
        private const val KEY_COVER_RULE = "coverRule"
        private const val KEY_COVER_COLLECTION_MANAGE = "coverCollectionManage"
    }
}
