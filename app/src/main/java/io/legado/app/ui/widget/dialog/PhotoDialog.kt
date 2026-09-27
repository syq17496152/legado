package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogPhotoViewBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.image.ImagePyramidLoader
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import com.davemorrissey.labs.subscaleview.ImageSource
import java.io.File

/**
 * 显示图片（W6 7.3：呈现轨由 `PhotoView` 收敛为 **SSIV 单一实现** —— AD-10/AD-21）
 *
 * 三条取图分支与改造前一一对应，仅替换承载视图与绑定入口：
 * ① `ImageProvider` 内存缓存命中 → [ImagePyramidLoader.bindNormalBitmap]；
 * ② 书籍本地图（`BookHelp.getImage`）→ [ImagePyramidLoader.bindNormalImage]；
 * ③ 远程地址 → Glide `downloadOnly` 落地缓存文件后绑定（与画布/预览同源取图方式，带 `sourceOrigin` 注入）。
 *
 * 说明：SSIV 的 `CENTER_INSIDE` 与原 `PhotoView` 的 `scaleType=centerInside` 语义一致
 * （整图可见、不变形不裁剪），故视觉效果不变；手势由 SSIV 提供（双指缩放/双击/平移）。
 */
class PhotoDialog() : BaseDialogFragment(R.layout.dialog_photo_view) {

    constructor(src: String, sourceOrigin: String? = null, isBook: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("src", src)
            putString("sourceOrigin", sourceOrigin)
            putBoolean("isBook", isBook)
        }
    }

    private val binding by viewBinding(DialogPhotoViewBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    @SuppressLint("CheckResult")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val arguments = arguments ?: return
        val src = arguments.getString("src") ?: return
        ImageProvider.get(src)?.let {
            // ① 内存缓存命中：直接绑 Bitmap（无落盘文件）
            ImagePyramidLoader.bindNormalBitmap(binding.ssivView, it)
            return
        }
        val isBook = arguments.getBoolean("isBook")
        val file = if (isBook) ReadBook.book?.let { book ->
            BookHelp.getImage(book, src)
        } else null
        if (file?.exists() == true) {
            // ② 书籍本地图
            ImagePyramidLoader.bindNormalImage(binding.ssivView, file)
        } else {
            // ③ 远程地址：downloadOnly 落地缓存文件
            loadRemote(src, arguments.getString("sourceOrigin"), isBook)
        }
    }

    private fun loadRemote(src: String, sourceOrigin: String?, isBook: Boolean) {
        // 兜底图：书籍图用 BookCover 默认图，其余用加载失败图（与原实现同口径）；
        // SSIV 的资源入口只收 resource id ⇒ 统一转 Bitmap 绑定（Drawable 分支也走同一路）
        val errorDrawable = if (isBook) {
            BookCover.defaultDrawable
        } else {
            ContextCompat.getDrawable(requireContext(), R.drawable.image_loading_error)
        }
        val errorSource = errorDrawable?.let { ImageSource.bitmap(it.toBitmap()) }
        Coroutine.async<File?> {
            ImageLoader.loadFile(requireContext(), src).apply {
                sourceOrigin?.let { origin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin))
                }
            }.submit().get()
        }.onSuccess { file ->
            // 异步回调：对话框可能已关闭（防 detached 访问 binding 崩溃）
            kotlin.runCatching {
                if (!isAdded || file == null) return@onSuccess
                ImagePyramidLoader.bindNormalImage(binding.ssivView, file)
            }
        }.onError { e ->
            AppLog.put("图片预览加载失败", e)
            kotlin.runCatching {
                if (!isAdded) return@onError
                binding.ssivView.recycle()
                errorSource?.let { binding.ssivView.setImage(it) }
            }
        }
    }

}