package io.legado.app.ui.image

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityImageDetailBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.adapter.ImageDetailAdapter
import io.legado.app.utils.ACache
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.writeBytes
import java.util.Date
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar

/**
 * 图片大图模式 Activity（V4 实施 Phase 1.3）
 *
 * 设计参考：design.md §1.1 架构图 + AD-02 大图模式容器选择
 *
 * 职责：
 * 1. ViewPager2 (horizontal) + ImageDetailAdapter 加载原图
 * 2. 接收共享元素动画（ActivityOptions.makeSceneTransitionAnimation）
 * 3. 沉浸式全屏（WindowInsetsControllerCompat，点击切换显隐）
 * 4. 旋转工具栏（顺时针/逆时针/重置）
 * 5. 状态保存（onSaveInstanceState 保存 currentIndex，V2 B-4）
 * 6. 从 ImagePlay.allImageUrls 读取图片 URL 列表（避免 Intent 1MB 限制，V2 B-4）
 * 7. 返回时通过 setResult 传递 currentIndex（V2 R2.6）
 *
 * 数据来源：
 * - Intent extra "startIndex"：初始图片索引（来自 ImageGalleryActivity 点击缩略图）
 * - ImagePlay.allImageUrls：图片 URL 列表（共享 ImagePlay 单例数据，跨 Activity 共享）
 *
 * 共享元素动画：
 * - 共享元素 transitionName = "shared_image_${listPosition}"（与 ImageCanvasAdapter.ImageViewHolder 一致）
 * - 需在 AndroidManifest.xml 中启用 windowActivityTransitions（Phase 1.3.5）
 */
class ImageDetailActivity : BaseActivity<ActivityImageDetailBinding>(),
    ImageDetailAdapter.OnImageDetailCallback {

    override val binding by viewBinding(ActivityImageDetailBinding::inflate)

    private var imageDetailAdapter: ImageDetailAdapter? = null

    /** 沉浸式状态（true=隐藏状态栏/导航栏/工具栏） */
    private var isImmersive = false

    /** 当前图片索引（从 Intent 或 savedInstanceState 恢复） */
    private var currentIndex: Int = 0

    /** 当前长按的图片URL（用于选择保存目录后回调） */
    private var currentLongClickUrl: String? = null

    /** startActivityForResult 请求码 */
    companion object {
        const val EXTRA_START_INDEX = "startIndex"
        const val EXTRA_CURRENT_INDEX = "currentIndex"
        const val KEY_CURRENT_INDEX = "key_current_index"
    }

    /**
     * 选择图片保存目录（SAF 模式，与 ImageGalleryActivity/ReadRssActivity 一致）
     *
     * 架构说明（V4 3.3.4 决策）：
     * - 项目使用 SAF（Storage Access Framework）让用户选择保存目录，URI 持久化到 ACache
     * - SAF 模式无需 READ_MEDIA_IMAGES / WRITE_EXTERNAL_STORAGE 运行时权限请求
     * - 与 ImageGalleryActivity.saveImage / ReadRssActivity.saveImage 架构一致
     * - tasks.md §3.3.4 "权限请求分支处理" 在项目架构中不适用（SAF 已规避权限请求）
     * - Android 13+ 兼容：SAF 在所有 API 版本（含 TIRAMISU+）均无需运行时权限请求
     */
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(AppConst.imagePathKey, uri.toString())
            currentLongClickUrl?.let { url ->
                saveImageInternal(url, uri)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initSharedElementTransition()
        initImmersion()
        initComposeTopBar()
        initViewPager(savedInstanceState)
        initRotateToolbar()
    }

    /**
     * 初始化共享元素动画接收配置（W2）
     *
     * - 设置 window.sharedElementEnterTransition = ChangeBounds
     * - 与 ImageGalleryActivity 的 makeSceneTransitionAnimation 配合
     * - 共享元素 transitionName = "shared_image_${listPosition}"
     *
     * 前置条件：主题已启用 android:windowActivityTransitions（见 styles.xml AppTheme.ImageDetail）
     */
    @android.annotation.SuppressLint("NewApi")
    private fun initSharedElementTransition() {
        kotlin.runCatching {
            window.sharedElementEnterTransition = android.transition.ChangeBounds().apply {
                duration = 250
            }
            window.sharedElementReturnTransition = android.transition.ChangeBounds().apply {
                duration = 200
            }
        }.onFailure { e ->
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_DETAIL,
                "initSharedElementTransition failed: ${e.message}",
                level = AppLog.Level.WARN
            )
        }
    }

    /**
     * 初始化沉浸式全屏（V2 O-4：使用 WindowInsetsControllerCompat + WindowCompat）
     *
     * 使用：
     * - WindowCompat.setDecorFitsSystemProperties(window, false) 让内容延伸到状态栏/导航栏下
     * - WindowInsetsControllerCompat 控制状态栏/导航栏显隐
     * - API 21+ 兼容（项目 minSdk=23 满足）
     */
    private fun initImmersion() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        // 默认显示工具栏
        binding.layoutRotateToolbar.visibility = View.VISIBLE
    }

    /**
     * Compose 顶栏（L-C15 S5 改造）：GlassTopAppBar + 返回按钮，无菜单
     *
     * - 返回按钮：直接 finish()
     * - 标题：使用 "图片浏览" 占位（页码通过 onPageChanged 更新）
     */
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = getString(R.string.image_browse),
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() }
                )
            }
        }
    }

    /**
     * 初始化 ViewPager2 + ImageDetailAdapter
     *
     * - orientation = horizontal（左右滑动切换图片）
     * - 从 ImagePlay.allImageUrls 读取图片 URL 列表（避免 Intent 1MB 限制）
     * - 初始定位：优先从 savedInstanceState 恢复 currentIndex，否则从 Intent 读取 startIndex
     */
    private fun initViewPager(savedInstanceState: Bundle?) {
        // V2 B-4：优先从 savedInstanceState 恢复 currentIndex（屏幕旋转/进程重建场景）
        val restored = savedInstanceState?.getInt(KEY_CURRENT_INDEX, -1)
        currentIndex = if (restored != null && restored >= 0) restored
            else intent.getIntExtra(EXTRA_START_INDEX, 0)

        val sourceOrigin = ImagePlay.rssSource?.sourceUrl
        val referer = ImagePlay.rssArticles?.getOrNull(ImagePlay.rssArticleIndex)?.link

        imageDetailAdapter = ImageDetailAdapter(this, sourceOrigin, referer)
        imageDetailAdapter?.setCallback(this)
        binding.viewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        binding.viewPager.adapter = imageDetailAdapter

        // V2 B-4：从 savedInstanceState 恢复时使用 false（无动画）
        binding.viewPager.setCurrentItem(currentIndex, false)

        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_DETAIL,
            "ImageDetailActivity: initViewPager startIndex=$currentIndex totalImages=${imageDetailAdapter?.getDataSize() ?: 0}",
            level = AppLog.Level.INFO
        )

        // 页面切换监听（更新 currentIndex + TitleBar 页码）
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentIndex = position
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_DETAIL,
                    "onPageSelected position=$position totalImages=${imageDetailAdapter?.getDataSize() ?: 0}",
                    level = AppLog.Level.INFO
                )
            }
        })
    }

    /**
     * 初始化旋转工具栏按钮（R1b.5-R1b.10）
     *
     * - 顺时针 90° / 逆时针 90° / 重置视图
     * - 调用 ImageDetailAdapter 当前 ViewHolder 的旋转方法
     */
    private fun initRotateToolbar() {
        binding.btnRotateRight.setOnClickListener {
            imageDetailAdapter?.rotateCurrentClockwise()
        }
        binding.btnRotateLeft.setOnClickListener {
            imageDetailAdapter?.rotateCurrentCounterClockwise()
        }
        binding.btnReset.setOnClickListener {
            imageDetailAdapter?.resetCurrentView()
        }
    }

    /**
     * 切换沉浸式全屏
     *
     * - true：隐藏状态栏/导航栏/工具栏，全屏看图
     * - false：显示状态栏/导航栏/工具栏，可操作旋转等
     */
    private fun toggleImmersive() {
        isImmersive = !isImmersive
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (isImmersive) {
            // 隐藏系统栏
            controller.hide(android.view.WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            binding.composeTopBar.visibility = View.GONE
            binding.layoutRotateToolbar.visibility = View.GONE
        } else {
            // 显示系统栏
            controller.show(android.view.WindowInsets.Type.systemBars())
            binding.composeTopBar.visibility = View.VISIBLE
            binding.layoutRotateToolbar.visibility = View.VISIBLE
        }
        AppLog.putDebugWithTag(AppLog.TAG_IMAGE_DETAIL, "toggleImmersive isImmersive=$isImmersive", level = AppLog.Level.INFO)
    }

    // ==================== ImageDetailAdapter.OnImageDetailCallback 实现 ====================

    /**
     * 单击图片回调：切换沉浸式
     */
    override fun onImageClick() {
        toggleImmersive()
    }

    /**
     * 长按图片回调：弹出保存/分享/复制URL菜单（V4 3.3.4 实施 + 7.2.1 改为 alert DSL）
     *
     * 架构说明：
     * - 项目使用 SAF 模式（HandleFileContract），无需运行时权限请求
     * - 与 ImageGalleryActivity.saveImage / ReadRssActivity.saveImage 一致
     * - 保存路径：用户首次选择目录后持久化到 ACache，后续直接复用
     * - V4 7.2.1：从 AlertDialog.Builder 改为 alert {} DSL（项目对话框统一规范）
     */
    override fun onImageLongClick(imageUrl: String, view: View) {
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_DETAIL,
            "onImageLongClick urlLen=${imageUrl.length}",
            level = AppLog.Level.INFO
        )
        currentLongClickUrl = imageUrl
        alert("图片操作") {
            items(listOf("保存图片", "分享图片", "复制URL")) { _, which ->
                when (which) {
                    0 -> saveImage(imageUrl)
                    1 -> shareImage(imageUrl)
                    2 -> copyImageUrl(imageUrl)
                }
            }
        }
    }

    /**
     * 保存图片到本地（SAF 模式，参考 ImageGalleryActivity.saveImage / ReadRssActivity.saveImage）
     *
     * 流程：
     * 1. 检查 ACache 是否有已保存的目录 URI
     * 2. 有：直接调用 saveImageInternal 保存
     * 3. 无：launch(null) 让用户选择目录，回调中调用 saveImageInternal
     */
    private fun saveImage(imageUrl: String) {
        val path = ACache.get().getAsString(AppConst.imagePathKey)
        if (path.isNullOrEmpty()) {
            selectImageDir.launch(null)
        } else {
            saveImageInternal(imageUrl, Uri.parse(path))
        }
    }

    /**
     * 实际执行图片保存（用 Coroutine.async 在 IO 线程加载+写文件）
     *
     * - 用 Glide asFile() 加载图片到缓存文件（支持 sourceOrigin 注入 Referer/Cookie）
     * - 写入用户选择的目录 URI
     * - 保存失败时清除 ACache 缓存路径（避免下次仍用错误路径）
     *
     * @param imageUrl 图片URL
     * @param uri 目标目录 URI（用户选择的保存目录）
     */
    private fun saveImageInternal(imageUrl: String, uri: Uri) {
        val sourceOrigin = ImagePlay.rssSource?.sourceUrl
        Coroutine.async<Unit> {
            val fileName = "${AppConst.fileNameFormat.format(Date(System.currentTimeMillis()))}.jpg"
            // 用 Glide asFile() 加载图片到缓存文件（支持 sourceOrigin 注入 Referer/Cookie）
            val file = ImageLoader.loadFile(this@ImageDetailActivity, imageUrl).apply {
                sourceOrigin?.let { origin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin))
                }
            }.submit().get()  // 同步加载（已在 IO 线程）
            val byteArray = file.readBytes()
            uri.writeBytes(this@ImageDetailActivity, fileName, byteArray)
        }.onError {
            ACache.get().remove(AppConst.imagePathKey)
            AppLog.put("保存图片失败", it, true)
            toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            toastOnUi("保存成功")
        }
    }

    /**
     * 分享图片（简化实现：复制 URL 到剪贴板）
     *
     * TODO 后续可扩展为 Intent.ACTION_SEND 真实分享图片文件
     */
    private fun shareImage(imageUrl: String) {
        sendToClip(imageUrl)
        toastOnUi("图片链接已复制到剪贴板")
    }

    /**
     * 复制图片 URL 到剪贴板
     */
    private fun copyImageUrl(imageUrl: String) {
        sendToClip(imageUrl)
        toastOnUi("图片链接已复制")
    }

    /**
     * 页码变化回调：更新 TitleBar 页码 "文章N/M 图片X/Y"
     */
    override fun onPageChanged(position: Int, total: Int) {
        if (total > 1) {
            binding.tvPageIndex.visibility = View.VISIBLE
            binding.tvPageIndex.text = "${position + 1} / $total"
        } else {
            // 单图时隐藏页码（R1.4）
            binding.tvPageIndex.visibility = View.GONE
        }
    }

    // ==================== 状态保存与返回数据传递 ====================

    /**
     * 屏幕旋转/进程重建时保存 currentIndex（V2 B-4）
     *
     * - 仅保存轻量 currentIndex（int）
     * - imageUrls 通过 ImagePlay.allImageUrls 单例持有（避免 Intent/SavedStateHandle 1MB 限制）
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_INDEX, currentIndex)
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_DETAIL,
            "onSaveInstanceState currentIndex=$currentIndex",
            level = AppLog.Level.INFO
        )
    }

    /**
     * 返回时通过 setResult 传递当前 currentIndex（V2 R2.6）
     *
     * ImageGalleryActivity.onActivityResult 接收后 scrollToPosition 到对应位置
     */
    override fun finish() {
        val data = Intent().apply {
            putExtra(EXTRA_CURRENT_INDEX, currentIndex)
        }
        setResult(RESULT_OK, data)
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_DETAIL,
            "finish currentIndex=$currentIndex",
            level = AppLog.Level.INFO
        )
        super.finish()
    }
}
