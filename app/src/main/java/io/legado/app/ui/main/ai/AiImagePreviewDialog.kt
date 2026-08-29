package io.legado.app.ui.main.ai

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.image.PhotoView
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiImagePreviewDialog() : ComposeDialogFragment() {

    constructor(imageId: String) : this() {
        arguments = Bundle().apply {
            putString(EXTRA_IMAGE_ID, imageId)
        }
    }

    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.MATCH_PARENT

    private var onDismissListener: DialogInterface.OnDismissListener? = null

    private var image by mutableStateOf<AiGeneratedImage?>(null)
    private val imageId: String
        get() = arguments?.getString(EXTRA_IMAGE_ID).orEmpty()

    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {
        onDismissListener = listener
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LaunchedEffect(Unit) { reload() }
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                val target = image
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(bottomBackground))
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (target != null) {
                            AndroidView(
                                factory = { ctx ->
                                    PhotoView(ctx).apply {
                                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                                    }
                                },
                                update = { photoView ->
                                    ImageLoader.load(requireContext(), target.localPath)
                                        .error(R.drawable.image_loading_error)
                                        .into(photoView)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Text(
                        text = target?.name.orEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        color = style.primaryText,
                        fontFamily = style.titleFontFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (target != null) {
                            val favorite = target.favorite
                            LegadoMiuixActionButton(
                                text = getString(
                                    if (favorite) R.string.ai_image_cancel_favorite else R.string.favorite
                                ),
                                palette = palette,
                                onClick = { toggleFavorite() },
                                modifier = Modifier.weight(1f)
                            )
                            if (favorite) {
                                LegadoMiuixActionButton(
                                    text = getString(R.string.ai_image_group),
                                    palette = palette,
                                    onClick = { selectGroup() },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            LegadoMiuixActionButton(
                                text = getString(R.string.ai_image_rename),
                                palette = palette,
                                onClick = { if (favorite) renameImage() },
                                modifier = Modifier
                                    .weight(1f)
                                    .alpha(if (favorite) 1f else 0.45f)
                            )
                            LegadoMiuixActionButton(
                                text = getString(R.string.delete),
                                palette = palette,
                                onClick = { confirmDelete() },
                                danger = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun reload() {
        image = AiImageGalleryManager.getImage(imageId)
        if (image == null) {
            dismissAllowingStateLoss()
        }
    }

    private fun toggleFavorite() {
        val target = image ?: return
        if (target.favorite) {
            AiImageGalleryManager.setFavorite(target.id, false, null)
            toastOnUi(R.string.out_favorites)
            reload()
        } else {
            showGroupSelector(getString(R.string.ai_image_favorite_to)) { groupId ->
                AiImageGalleryManager.setFavorite(target.id, true, groupId)
                toastOnUi(R.string.in_favorites)
                reload()
            }
        }
    }

    private fun selectGroup() {
        val target = image ?: return
        if (!target.favorite) return
        showGroupSelector(getString(R.string.ai_image_group)) { groupId ->
            AiImageGalleryManager.setFavorite(target.id, true, groupId)
            reload()
        }
    }

    private fun showGroupSelector(title: String, onSelected: (String) -> Unit) {
        val groups = AiImageGalleryManager.listGroups()
        val labels = groups.map { it.name } + getString(R.string.ai_image_new_group)
        showComposeChoiceListDialog(title = title, labels = labels) { index ->
            if (index == groups.size) {
                createGroup(onSelected)
            } else {
                onSelected(groups[index].id)
            }
        }
    }

    private fun createGroup(onCreated: (String) -> Unit) {
        showComposeTextInputDialog(
            title = getString(R.string.ai_image_new_group),
            hint = getString(R.string.ai_image_new_group),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(android.R.string.cancel),
            onPositive = { name ->
                if (name.trim().isNotBlank()) {
                    onCreated(AiImageGalleryManager.createGroup(name.trim()).id)
                }
            }
        )
    }

    private fun renameImage() {
        val target = image ?: return
        if (!target.favorite) return
        showComposeTextInputDialog(
            title = getString(R.string.ai_image_rename),
            initialValue = target.name,
            positiveText = getString(android.R.string.ok),
            negativeText = getString(android.R.string.cancel),
            onPositive = { name ->
                val trimmed = name.trim()
                if (trimmed.isNotBlank()) {
                    AiImageGalleryManager.renameImage(target.id, trimmed)
                    reload()
                }
            }
        )
    }

    private fun confirmDelete() {
        val target = image ?: return
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = getString(R.string.ai_image_delete_confirm),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(android.R.string.cancel),
            dangerPositive = true,
            onPositive = {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AiImageGalleryManager.deleteImage(target.id)
                    }
                    dismissAllowingStateLoss()
                }
            }
        )
    }

    companion object {
        private const val EXTRA_IMAGE_ID = "imageId"
    }
}
