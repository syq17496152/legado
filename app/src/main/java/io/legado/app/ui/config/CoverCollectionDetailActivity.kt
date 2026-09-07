package io.legado.app.ui.config

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityCoverCollectionDetailBinding
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 封面图集详情页（my-compose-full W4.2：composeHost + CoverCollectionDetailScreen 全量重写，
 * 原 View Grid+RecyclerAdapter 删除；删除链路保留确认弹框，数据经 CoverCollectionManager 原路径）。
 */
class CoverCollectionDetailActivity : BaseActivity<ActivityCoverCollectionDetailBinding>() {

    override val binding by viewBinding(ActivityCoverCollectionDetailBinding::inflate)

    private var isNight = false
    private var collectionId: String? = null
    private var collection: CoverCollectionManager.Collection? = null

    // W4.2 Compose 桥接状态
    private var collectionName by mutableStateOf("")
    private var images by mutableStateOf(listOf<String>())

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
        isNight = intent.getBooleanExtra("isNight", false)
        collectionId = intent.getStringExtra("id")
        initComposeHost()
        loadCollection()
    }

    // W4.2：全页 Compose 渲染（顶栏 AppManagementScaffold+导入动作+3 列图片墙）
    private fun initComposeHost() {
        binding.composeHost.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeHost.setContent {
            LegadoTheme {
                CoverCollectionDetailScreen(
                    collectionName = collectionName,
                    images = images,
                    onBack = { finish() },
                    onImportImages = { importImages.launch("image/*") },
                    onDeleteImage = ::confirmDeleteImage
                )
            }
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
        collectionName = current.name
        images = current.images
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
}
