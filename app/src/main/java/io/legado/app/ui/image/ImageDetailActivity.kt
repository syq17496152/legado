package io.legado.app.ui.image

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewbinding.ViewBinding
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.adapter.ImageDetailAdapter
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.dpToPx
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeBytes
import java.util.Date

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
 *
 * CE 5.2（compose 包）：原 `activity_image_detail.xml` 已退役 ⇒ `composeShell` 合成壳 +
 * `attachComposeContent` 单源承载；**三个 View 内核一律 `AndroidView` 原样托管**
 * （`ViewPager2` 图片区 / 页码徽标 / 旋转工具条）。画布域固定黑白字面色已在
 * `theme_token_allowlist.json` 按同域同口径登记（原 XML 亦在画布域豁免内）。
 */
class ImageDetailActivity : BaseActivity<ViewBinding>(),
    ImageDetailAdapter.OnImageDetailCallback {

    override val binding: ViewBinding by lazy { composeShell(this) }

    private var imageDetailAdapter: ImageDetailAdapter? = null

    /** 沉浸式状态（true=隐藏状态栏/导航栏/工具栏） */
    private var isImmersive = false

    /** 当前图片索引（从 Intent 或 savedInstanceState 恢复） */
    private var currentIndex: Int = 0

    /** 当前长按的图片URL（用于选择保存目录后回调） */
    private var currentLongClickUrl: String? = null

    // ---- CE 5.2：原 XML 显隐机制改状态驱动（逐一与原 View 操作等价）----
    //  · topBarVisible        ← compose_top_bar 的 GONE/VISIBLE（沉浸式切换）
    //  · rotateToolbarVisible ← layout_rotate_toolbar 的 GONE/VISIBLE（沉浸式切换 + 初始 VISIBLE）
    //  · pageIndexVisible / pageIndexText ← tv_page_index 的 GONE/VISIBLE 与页码文案
    private var topBarVisible by mutableStateOf(true)
    private var rotateToolbarVisible by mutableStateOf(true)
    private var pageIndexVisible by mutableStateOf(false)
    private var pageIndexText by mutableStateOf("")

    // 三个 View 内核：随宿主即时创建（`initViewPager`/`initRotateToolbar` 早于组合挂载，
    // 因此必须先于 AndroidView factory 存在；factory 只做「把它挂上去」）
    private val viewPager by lazy { ViewPager2(this) }
    private val pageIndexView by lazy { createPageIndexView() }
    private val rotateToolbarView by lazy { createRotateToolbar() }

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
        initComposeContent()
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
        // 原 XML root 的 android:background="@android:color/black"：换装后由合成壳承担同色底
        binding.root.setBackgroundColor(Color.BLACK)
        // 默认显示工具栏（原 binding.layoutRotateToolbar.visibility = View.VISIBLE）
        rotateToolbarVisible = true
    }

    /**
     * CE 5.2：Compose 承载全页（图片区 + 顶栏 + 页码徽标 + 旋转工具条）。
     *
     * 与原 XML（`activity_image_detail.xml`）的**逐一对应关系**（三不影响口径）：
     *  · `view_pager`（全屏 ViewPager2 + PhotoView 手势，**无 Compose 等价物**）
     *    → `AndroidView { viewPager }`（同一实例，`initViewPager` 已在挂载前完成配置）
     *  · `compose_top_bar`（已 Compose）→ 内容逐行搬入页内；沉浸态隐藏改为条件组合
     *    （原 `visibility = GONE` ⇒ 不参与布局，语义等价）
     *  · `tv_page_index`（右上徽标：上边距 56dp / 右 16dp / `bg_image_page_index` / 白字 14sp）
     *    → `AndroidView` 程序化 `TextView`，显隐与文案由状态驱动
     *  · `layout_rotate_toolbar`（底部居中：下边距 32dp / `bg_overlay_button` / padding 12dp /
     *    三个 48dp 白色图标按钮）→ `AndroidView` 程序化 `LinearLayout`，显隐由状态驱动
     */
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            Box(modifier = Modifier.fillMaxSize()) {
                // ---- 图片区（原 view_pager）----
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewPager }
                )
                // ---- 顶栏（原 compose_top_bar；沉浸态整体收起）----
                Column(modifier = Modifier.fillMaxSize()) {
                    if (topBarVisible) {
                        LegadoTheme {
                            GlassTopAppBar(
                                title = getString(R.string.image_browse),
                                navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                                onNavClick = { finish() }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
                // ---- 页码徽标（原 tv_page_index：右上、上边距 56dp、右 16dp）----
                AndroidView(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 56.dp, end = 16.dp),
                    factory = { pageIndexView },
                    update = { tv ->
                        tv.visibility = if (pageIndexVisible) View.VISIBLE else View.GONE
                        if (pageIndexVisible) {
                            tv.text = pageIndexText
                        }
                    }
                )
                // ---- 旋转工具条（原 layout_rotate_toolbar：底部居中、下边距 32dp）----
                AndroidView(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    factory = { rotateToolbarView },
                    update = { bar ->
                        bar.visibility =
                            if (rotateToolbarVisible) View.VISIBLE else View.GONE
                    }
                )
            }
        }
    }

    /** 原 `tv_page_index` 的程序化等价物（drawable 底 / 白字 / 14sp / 12×6 内边距）。 */
    private fun createPageIndexView(): TextView = TextView(this).apply {
        id = R.id.tv_page_index
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setBackgroundResource(R.drawable.bg_image_page_index)
        setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
        setTextColor(Color.WHITE)
        textSize = 14f
        visibility = View.GONE
    }

    /** 原 `layout_rotate_toolbar` 的程序化等价物（底 drawable / 12dp 内边距 / 三个 48dp 按钮）。 */
    private fun createRotateToolbar(): LinearLayout {
        val toolbar = LinearLayout(this).apply {
            id = R.id.layout_rotate_toolbar
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.bg_overlay_button)
            val pad = 12.dpToPx()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        toolbar.addView(
            createRotateButton(
                R.id.btn_rotate_left, R.drawable.ic_rotate_left, "逆时针旋转90度",
                marginStart = 0, marginEnd = 16
            ) { imageDetailAdapter?.rotateCurrentCounterClockwise() }
        )
        toolbar.addView(
            createRotateButton(
                R.id.btn_reset, R.drawable.ic_reset, "重置视图",
                marginStart = 16, marginEnd = 16
            ) { imageDetailAdapter?.resetCurrentView() }
        )
        toolbar.addView(
            createRotateButton(
                R.id.btn_rotate_right, R.drawable.ic_rotate_right, "顺时针旋转90度",
                marginStart = 16, marginEnd = 0
            ) { imageDetailAdapter?.rotateCurrentClockwise() }
        )
        return toolbar
    }

    /** 原工具条按钮（48dp、无边界波纹底、白 tint、工具条内 16dp 间距）。 */
    private fun createRotateButton(
        viewId: Int,
        iconRes: Int,
        label: String,
        marginStart: Int,
        marginEnd: Int,
        onClick: () -> Unit
    ): AppCompatImageButton = AppCompatImageButton(this).apply {
        id = viewId
        layoutParams = LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx()).apply {
            this.marginStart = marginStart.dpToPx()
            this.marginEnd = marginEnd.dpToPx()
        }
        setBackgroundResource(borderlessItemBackgroundRes())
        contentDescription = label
        setImageResource(iconRes)
        setColorFilter(Color.WHITE)
        setOnClickListener { onClick() }
    }

    /** `?attr/selectableItemBackgroundBorderless` 的样式资源 id（原 XML 三处按钮的波纹底）。 */
    private fun borderlessItemBackgroundRes(): Int {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return outValue.resourceId
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
        viewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        viewPager.adapter = imageDetailAdapter

        // V2 B-4：从 savedInstanceState 恢复时使用 false（无动画）
        viewPager.setCurrentItem(currentIndex, false)

        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_DETAIL,
            "ImageDetailActivity: initViewPager startIndex=$currentIndex totalImages=${imageDetailAdapter?.getDataSize() ?: 0}",
            level = AppLog.Level.INFO
        )

        // 页面切换监听（更新 currentIndex + TitleBar 页码）
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
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
     *
     * CE 5.2：三个按钮的点击回调已在 [createRotateToolbar] 内绑定（工具条随宿主即时创建，
     * 与「先建视图再绑监听」的原顺序等价）；此处仅确保工具条实例已就绪。
     */
    private fun initRotateToolbar() {
        rotateToolbarView
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
            topBarVisible = false
            rotateToolbarVisible = false
        } else {
            // 显示系统栏
            controller.show(android.view.WindowInsets.Type.systemBars())
            topBarVisible = true
            rotateToolbarVisible = true
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
        showComposeChoiceListDialog(
            title = "图片操作",
            labels = listOf("保存图片", "分享图片", "复制URL")
        ) { which ->
            when (which) {
                0 -> saveImage(imageUrl)
                1 -> shareImage(imageUrl)
                2 -> copyImageUrl(imageUrl)
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
            toastOnUi(getString(R.string.image_save_failed, it.localizedMessage.orEmpty()))
        }.onSuccess {
            // F179（ui-subpage-optimization）保存回执升级：原为一句 toast「保存成功」，
            // 「存哪去了」全靠用户记忆。升级为回执条：落点说明 + 批次位置 + 「打开文件夹」直达。
            // 落点 URI 本就持久化在 ACache（imagePathKey），回显零新存储；
            // 「打开文件夹」走既有 FileManageActivity（不新建入口）。
            val total = imageDetailAdapter?.getDataSize() ?: 0
            val positionText = if (total > 1) {
                getString(R.string.image_save_receipt_with_position, currentIndex + 1, total)
            } else {
                ""
            }
            val folderName = Uri.parse(uri.toString()).lastPathSegment?.substringAfterLast(':').orEmpty()
            val message = getString(
                R.string.image_save_receipt,
                folderName.ifBlank { getString(R.string.image_save_receipt_folder_unknown) },
                positionText
            )
            // 用既有 View.snackbar 家族形态（Snackbar.make + setAction），与项目 6 处 longSnackbar 调用同源
            com.google.android.material.snackbar.Snackbar
                .make(
                    binding.root,
                    message,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                )
                .setAction(getString(R.string.download_open_folder)) {
                    startActivity(Intent(this@ImageDetailActivity, FileManageActivity::class.java))
                }
                .show()
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
            pageIndexVisible = true
            pageIndexText = "${position + 1} / $total"
        } else {
            // 单图时隐藏页码（R1.4）
            pageIndexVisible = false
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