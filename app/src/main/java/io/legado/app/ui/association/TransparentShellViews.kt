package io.legado.app.ui.association

import android.app.Activity
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.base.attachComposeContent
import io.legado.app.ui.widget.anima.RotateLoading

/**
 * 透明壳页（`activity_translucence`，**5 个 Activity 共用**）的共享装配（CE-b）。
 *
 * 宿主：`FileAssociationActivity` / `OnLineImportActivity` / `OpenUrlConfirmActivity` /
 * `VerificationCodeActivity` / `HandleFileActivity`。原 XML 结构 = `ConstraintLayout`（透明底
 * `@color/transparent50`）+ 居中 `RotateLoading@rotate_loading`（36dp，初值 gone）+ 两个居中卡槽
 * `cv_result_card`(wrap_content) / `cv_import_progress`(match_parent 宽)。
 *
 * 与原 XML 的逐一对应关系：
 *  · 根 `ConstraintLayout` + `android:background="@color/transparent50"` → 合成壳 `root` 直接 `setBackgroundColor`
 *    （**保留资源名，不写字面色**，与 XML 同口径）
 *  · `rotate_loading` → 程序化 `RotateLoading`（`visibility=GONE` 初值同 XML；**显式 `setLoadingWidthDp(2)`
 *    复刻 `app:loading_width="2dp"`**，否则类默认 6dp 笔宽 ⇒ 视觉不等价）；居中 36dp（同 XML 几何；
 *    原 XML 的 `android:layout_margin="6dp"` 对「居中 + 定尺寸」的子视图只扩大占位外框、不改变视觉中心
 *    ⇒ 合成壳不再保留该 margin）
 *  · `cv_result_card` → 唯一工厂 [slot] 产生的 `ComposeView`（`wrap_content` + 居中语义 ⇒ `wrapContentSize(Center)`）
 *  · `cv_import_progress` → 同工厂（`match_parent` 宽 + 居中 ⇒ `fillMaxWidth().wrapContentHeight(CenterVertically)`）
 *
 * **为什么保留 `ComposeView`（本批唯一 interop 通道）**：两个卡槽的内容由**宿主**决定（宿主在运行时
 * `setContent { … }`），骨架无法代为组合；同 `ReplaceEditActivity` 的 `composeSlot()` 先例。
 * 收敛口径：`ComposeView` 构造点在本类内**只有 [slot] 一处**（合成策略也只在此设置一次），
 * 5 个宿主一律不得自行构造 `ComposeView`（由 `TransparentShellMigrationTest` 与
 * `ComposeShellSingleSourceTest.noConsumerHandWritesComposeView` 双向锁定）。
 */
class TransparentShellViews(private val activity: Activity) {

    /** 原 XML `rotate_loading`（36dp、`loading_width=2dp`、初值 gone、居中） */
    val rotateLoading: RotateLoading by lazy {
        RotateLoading(activity).apply {
            visibility = View.GONE
            setLoadingWidthDp(2)
        }
    }

    /** 原 XML `cv_result_card`（仅 `FileAssociationActivity` 填内容，其余页恒 gone 零占位） */
    val resultCard: ComposeView by lazy { slot() }

    /** 原 XML `cv_import_progress`（仅 `OnLineImportActivity` 填内容，其余页恒 gone 零占位） */
    val importProgress: ComposeView by lazy { slot() }

    /**
     * `ComposeView` 的**唯一构造工厂**（本类是本包唯一登记在 `ComposeShellSingleSourceTest` 的 interop 例外）。
     * 合成策略与旧 XML 口径一致（`DisposeOnViewTreeLifecycleDestroyed`），并**只在此处设置一次**。
     */
    private fun slot(): ComposeView = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        visibility = View.GONE
    }

    /**
     * 装配合成壳：透明底（同 XML `@color/transparent50`）+ 居中 loading + 两个恒在场卡槽。
     * 宿主须先用 `composeShell(this)` 建壳并传入 `binding.root`（`ViewBinding.root` 静态类型是
     * `View` ⇒ 形参取 `View`，容器能力由 `attachComposeContent` 内部判定，与其余合成壳页同口径）。
     */
    fun install(root: View) {
        root.setBackgroundColor(ContextCompat.getColor(activity, R.color.transparent50))
        root.attachComposeContent {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                    factory = { rotateLoading }
                )
                AndroidView(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .wrapContentSize(Alignment.Center),
                    factory = { resultCard }
                )
                AndroidView(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .wrapContentHeight(Alignment.CenterVertically),
                    factory = { importProgress }
                )
            }
        }
    }
}