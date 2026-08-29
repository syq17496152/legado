package io.legado.app.ui.config

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ActivityCoverCollectionDetailBinding
import io.legado.app.databinding.ItemCoverCollectionImageBinding
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

class CoverCollectionDetailActivity : BaseActivity<ActivityCoverCollectionDetailBinding>() {

    override val binding by viewBinding(ActivityCoverCollectionDetailBinding::inflate)

    private var adapter: Adapter? = null
    private var isNight = false
    private var collectionId: String? = null
    private var collection: CoverCollectionManager.Collection? = null
    private val importImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val current = collection ?: return@registerForActivityResult
        lifecycleScope.launch {
            kotlin.runCatching {
                collection = CoverCollectionManager.addImages(this@CoverCollectionDetailActivity, current, uris)
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
            bindCollection()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
        isNight = intent.getBooleanExtra("isNight", false)
        collectionId = intent.getStringExtra("id")
        binding.root.applyUiBodyTypefaceDeep(uiTypeface())
        adapter = Adapter()
        adapter?.setOnItemLongClickListener { _, item ->
            confirmDeleteImage(item)
            true
        }
        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = adapter
        loadCollection()
    }

    /** subpage-topbar-unify: 子页头部统一为 MainTopBarView(Mode.SUB)，原「导入图片」菜单项改为 action 图标按钮。 */
    private fun initTopBar() = binding.titleBar.run {
        applyStatusBarPadding(withInitialPadding = true)
        setMode(MainTopBarView.Mode.SUB)
        setTitle(getString(R.string.cover_collection_manage))
        setSearchEntryVisible(false)
        // Mode.SUB 下 titleSelect 显示「标题+返回箭头」，点击回退
        titleSelect.setOnClickListener { finish() }
        addActionButton(R.drawable.ic_import, R.string.cover_collection_import_images) {
            importImages.launch("image/*")
        }
    }

    private fun loadCollection() {
        lifecycleScope.launch {
            collection = CoverCollectionManager.get(isNight, collectionId)
            bindCollection()
        }
    }

    private fun bindCollection() {
        val current = collection ?: return
        title = current.name
        binding.titleBar.setTitle(current.name)
        adapter?.setItems(current.images)
    }

    private fun confirmDeleteImage(imagePath: String) {
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = getString(R.string.cover_collection_delete_image_confirm),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                deleteImage(imagePath)
            }
        )
    }

    private fun deleteImage(imagePath: String) {
        val current = collection ?: return
        lifecycleScope.launch {
            kotlin.runCatching {
                collection = CoverCollectionManager.deleteImage(current, imagePath)
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
            bindCollection()
        }
    }

    private inner class Adapter :
        RecyclerAdapter<String, ItemCoverCollectionImageBinding>(this@CoverCollectionDetailActivity) {

        override fun getViewBinding(parent: ViewGroup): ItemCoverCollectionImageBinding {
            return ItemCoverCollectionImageBinding.inflate(inflater, parent, false).apply {
                root.background = UiCorner.panelRounded(
                    root.context,
                    root.context.themeCardColorOrDefault(),
                    UiCorner.panelRadius(root.context)
                )
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemCoverCollectionImageBinding,
            item: String,
            payloads: MutableList<Any>
        ) {
            Glide.with(binding.ivImage).load(item).centerCrop().into(binding.ivImage)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemCoverCollectionImageBinding) {
        }
    }
}