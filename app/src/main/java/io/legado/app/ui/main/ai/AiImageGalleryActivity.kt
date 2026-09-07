package io.legado.app.ui.main.ai

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.data.entities.AiImageGroup
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.ai.AiImageGalleryManager.GalleryFilter
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 图库（my-compose-full W6.2：composeHost + AiImageGalleryScreen 全量重写，
 * 原 MainTopBarView/EditText/动态 TextView chips/RecyclerAdapter 网格删除，
 * 顶栏+搜索+批量底栏由 AppManagementScaffold 承载，chips 为 Compose FlowRow）。
 */
class AiImageGalleryActivity : BaseActivity<ViewBinding>() {

    // W6.2：原 activity_ai_image_gallery.xml 已删除，改合成 ViewBinding 空壳
    // （对齐 AiImageProviderEditActivity 模式），Compose 全权接管
    override val binding: ViewBinding by lazy {
        object : ViewBinding {
            override fun getRoot(): android.view.View = root
        }
    }

    private val root: android.widget.FrameLayout by lazy { android.widget.FrameLayout(this) }

    private val selectedIds = mutableStateOf<Set<String>>(emptySet())
    private var currentFilter: GalleryFilter = GalleryFilter.ALL
    private var fixedBookKey: String = ""
    private var fixedTitle: String = ""

    // W6.2 Compose 桥接状态
    private val imagesState = mutableStateListOf<AiGeneratedImage>()
    private val groupsState = mutableStateListOf<AiImageGroup>()
    private var searchQueryState by mutableStateOf("")

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        fixedBookKey = intent.getStringExtra(EXTRA_BOOK_KEY).orEmpty()
        fixedTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (fixedBookKey.isNotBlank()) {
            currentFilter = GalleryFilter.BOOK(fixedBookKey)
        }
        initComposeContent()
        reload()
    }

    private fun initComposeContent() {
        val container = binding.root as? ViewGroup ?: return
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                LegadoTheme {
                    AiImageGalleryScreen(
                        chips = buildChips(),
                        images = imagesState,
                        selectedIds = selectedIds.value,
                        searchQuery = searchQueryState,
                        onSearchChange = { query ->
                            searchQueryState = query
                            reload()
                        },
                        pageTitle = fixedTitle.ifBlank { getString(R.string.ai_image_gallery) },
                        onChipClick = ::onChipClick,
                        onImageClick = ::onImageClick,
                        onImageLongClick = ::toggleSelection,
                        onSelectAll = ::selectAllVisibleImages,
                        onBatchGroup = ::showBatchGroupDialog,
                        onBatchDelete = ::confirmBatchDelete,
                        onBatchCancel = ::clearSelection,
                        onBack = { finish() }
                    )
                }
            }
        }
        container.addView(cv)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /**
     * 构建 chips 状态（固定项顺序对齐原 renderFilters，分组项追加在尾部）
     */
    private fun buildChips(): List<GalleryChipUi> {
        val chips = buildList {
            add(chip(R.string.ai_image_gallery_all, TAG_ALL))
            add(chip(R.string.ai_image_gallery_temporary, TAG_TEMPORARY))
            add(chip(R.string.favorites, TAG_FAVORITE))
            if (fixedBookKey.isNotBlank()) {
                add(GalleryChipUi("本书", TAG_BOOK, currentFilter == GalleryFilter.BOOK(fixedBookKey)))
            }
            add(
                GalleryChipUi(
                    "角色图",
                    TAG_CHARACTER,
                    currentFilter == GalleryFilter.SOURCE_TYPE(AiImageGalleryManager.SOURCE_TYPE_CHARACTER_AVATAR)
                )
            )
            groupsState.forEach { group ->
                add(
                    GalleryChipUi(
                        group.name,
                        TAG_GROUP_PREFIX + group.id,
                        currentFilter == GalleryFilter.GROUP(group.id)
                    )
                )
            }
        }
        return chips
    }

    private fun chip(labelRes: Int, tag: String): GalleryChipUi {
        val selected = when (tag) {
            TAG_ALL -> currentFilter == GalleryFilter.ALL
            TAG_TEMPORARY -> currentFilter == GalleryFilter.TEMPORARY
            TAG_FAVORITE -> currentFilter == GalleryFilter.FAVORITE
            else -> false
        }
        return GalleryChipUi(getString(labelRes), tag, selected)
    }

    private fun onChipClick(tag: String) {
        currentFilter = when {
            tag == TAG_ALL -> GalleryFilter.ALL
            tag == TAG_TEMPORARY -> GalleryFilter.TEMPORARY
            tag == TAG_FAVORITE -> GalleryFilter.FAVORITE
            tag == TAG_BOOK -> GalleryFilter.BOOK(fixedBookKey)
            tag == TAG_CHARACTER ->
                GalleryFilter.SOURCE_TYPE(AiImageGalleryManager.SOURCE_TYPE_CHARACTER_AVATAR)
            tag.startsWith(TAG_GROUP_PREFIX) -> GalleryFilter.GROUP(tag.removePrefix(TAG_GROUP_PREFIX))
            else -> return
        }
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val query = searchQueryState.trim()
            val data = withContext(Dispatchers.IO) {
                AiImageGalleryManager.cleanupExpiredTemporary()
                val groups = AiImageGalleryManager.listGroups()
                val images = when {
                    query.isNotBlank() -> AiImageGalleryManager.listImages(GalleryFilter.SEARCH(query))
                        .let { list -> if (fixedBookKey.isBlank()) list else list.filter { it.bookKey == fixedBookKey } }
                    else -> AiImageGalleryManager.listImages(currentFilter)
                }
                groups to images
            }
            groupsState.clear()
            groupsState.addAll(data.first)
            selectedIds.value = selectedIds.value.filter { id ->
                data.second.any { it.id == id }
            }.toSet()
            imagesState.clear()
            imagesState.addAll(data.second)
        }
    }

    private fun onImageClick(image: AiGeneratedImage) {
        if (selectedIds.value.isNotEmpty()) {
            toggleSelection(image)
        } else {
            val dialog = AiImagePreviewDialog(image.id).apply {
                setOnDismissListener { reload() }
            }
            showDialogFragment(dialog)
        }
    }

    private fun toggleSelection(image: AiGeneratedImage) {
        val current = selectedIds.value.toMutableSet()
        if (!current.add(image.id)) {
            current.remove(image.id)
        }
        selectedIds.value = current
    }

    private fun clearSelection() {
        selectedIds.value = emptySet()
    }

    private fun selectAllVisibleImages() {
        val ids = imagesState.map { it.id }.toSet()
        if (ids.isEmpty()) return
        selectedIds.value = ids
    }

    private fun showBatchGroupDialog() {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) { AiImageGalleryManager.listGroups() }
            val labels: List<CharSequence> = groups.map { it.name } + getString(R.string.ai_image_new_group)
            showComposeChoiceListDialog(
                title = getString(R.string.ai_image_favorite_to),
                labels = labels
            ) { index ->
                val group = groups.getOrNull(index)
                if (group != null) {
                    moveSelectedToGroup(ids, group.id)
                } else {
                    showCreateGroupDialog(ids)
                }
            }
        }
    }

    private fun showCreateGroupDialog(ids: List<String>) {
        showComposeTextInputDialog(
            title = getString(R.string.ai_image_new_group),
            hint = getString(R.string.ai_image_new_group),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(android.R.string.cancel),
            onPositive = { raw ->
                val name = raw.trim()
                if (name.isNotBlank()) {
                    val groupId = AiImageGalleryManager.createGroup(name).id
                    moveSelectedToGroup(ids, groupId)
                }
            }
        )
    }

    private fun moveSelectedToGroup(ids: List<String>, groupId: String?) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AiImageGalleryManager.moveImagesToGroup(ids, groupId)
            }
            toastOnUi("已移动分组")
            clearSelection()
            reload()
        }
    }

    private fun confirmBatchDelete() {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = "删除选中的 ${ids.size} 张图片？",
            positiveText = getString(android.R.string.ok),
            negativeText = getString(android.R.string.cancel),
            dangerPositive = true,
            onPositive = {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AiImageGalleryManager.deleteImages(ids)
                    }
                    clearSelection()
                    reload()
                }
            }
        )
    }

    companion object {
        private const val TAG_ALL = "all"
        private const val TAG_TEMPORARY = "temporary"
        private const val TAG_FAVORITE = "favorite"
        private const val TAG_BOOK = "book"
        private const val TAG_CHARACTER = "character"
        private const val TAG_GROUP_PREFIX = "group:"

        const val EXTRA_BOOK_KEY = "bookKey"
        const val EXTRA_TITLE = "title"
    }
}
