package io.legado.app.ui.about

import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.utils.dpToPx
import io.legado.app.utils.openUrl
import io.legado.app.utils.share

/**
 * 关于页
 *
 * **CE-b：`activity_about.xml` 退役**（顶栏包 §4.1「页面直接渲染 Compose 顶栏」与本批合并完成）：
 *  · 原根 `LinearLayout` → `composeShell` 合成壳（`binding.root`）
 *  · 原 `installGlassTopBar(binding, …)`「运行时在 root 首插 ComposeView」→ **页内直接渲染 `GlassTopAppBar`**
 *    （必须如此：`attachComposeContent` 会 `removeAllViews()`，两者同用时顶栏会被清掉）
 *  · 原 `ll_about`（两块文案 + `tv_app_summary` 的 accent 上色）→ **整块 View 子树程序化复刻 + `AndroidView` 托管**
 *    （保住原 `Spannable` / 字号 20sp / 粗体 / 居中 / `margin=6dp` + `padding=10dp` 与主题取色口径，不做语义改写）
 *  · 原 `fl_fragment`（`AboutFragment` 事务容器）→ 程序化 `FrameLayout`（**显式赋 id**，id 随 XML 退役迁 `values/ids.xml`）
 *    + **`post{}` 延迟提交**（组合晚于 `onActivityCreated`，同 `activity_qrcode_capture` 范式）
 *  · 原 `fl_fragment` 高度是 `match_parent`（竖向 LinearLayout 内本就会溢出到屏外）⇒ 换装**保留同语义**
 *    （`Modifier.fillMaxHeight()`），**零观感变化**；**不改成 `weight(1f)`**（那属用户可见变更，须另行走 updateLog）
 *
 * 零用户可感变化 ⇒ 不入 updateLog。
 */
class AboutActivity : BaseActivity<ViewBinding>() {

    // 原 activity_about.xml 已退役（CE-b）：composeShell 合成壳 + attachComposeContent 单源
    override val binding: ViewBinding by lazy { composeShell(this) }

    /** 原 `fl_fragment`：`AboutFragment` 事务容器（id 随 XML 退役迁入 `values/ids.xml`） */
    private val flFragment: FrameLayout by lazy {
        FrameLayout(this).apply { id = R.id.fl_fragment }
    }

    /** 原 `tv_app_summary`（正文文案，运行时给其中的公众号名染强调色） */
    private val tvAppSummary: TextView by lazy {
        TextView(this).apply { text = getString(R.string.about_description_sigma) }
    }

    /** 原 `ll_about`：`layout_margin=6dp` 在 Compose 侧用 `padding(6.dp)` 承担，`padding=10dp` 在此复刻 */
    private val aboutHeader: LinearLayout by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 10.dpToPx())
            addView(
                TextView(this@AboutActivity).apply {
                    text = getString(R.string.app_name_sigma)
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.CENTER_HORIZONTAL }
                }
            )
            addView(
                tvAppSummary,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        applySummaryAccent()
        initComposeContent()
        // 组合晚于 onActivityCreated ⇒ 容器由组合托管时事务必须延到上树后（View.post 未 attach 时排入 run queue）
        flFragment.post {
            val fTag = "aboutFragment"
            var aboutFragment = supportFragmentManager.findFragmentByTag(fTag)
            if (aboutFragment == null) aboutFragment = AboutFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fl_fragment, aboutFragment, fTag)
                .commit()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶栏（原 installGlassTopBar 注入的 GlassTopAppBar：标题/返回/两个一级图标逐行不变）----
                LegadoTheme {
                    GlassTopAppBar(
                        title = getString(R.string.about),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = {
                            TopBarActionRow(
                                listOf(
                                    MenuAction(
                                        iconRes = R.drawable.ic_star_border,
                                        title = getString(R.string.scoring),
                                        alwaysShow = true
                                    ) { openUrl("market://details?id=$packageName") },
                                    MenuAction(
                                        iconRes = R.drawable.ic_share,
                                        title = getString(R.string.share),
                                        alwaysShow = true
                                    ) {
                                        share(
                                            getString(R.string.app_share_description_sigma),
                                            getString(R.string.app_name)
                                        )
                                    }
                                )
                            )
                        }
                    )
                }
                // ---- 原 ll_about（两块文案，整块 View 子树原样托管）----
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    factory = { aboutHeader }
                )
                // ---- 原 fl_fragment（高度语义与原 match_parent 一致）----
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    factory = { flFragment }
                )
            }
        }
    }

    /**
     * 原实现把正文里的公众号名染成主题强调色。
     *
     * 差异说明（无行为变化）：原代码用 `runCatching` 包住 `setSpan(start, start + len)`，`indexOf` 返回 -1
     * 时会抛 `IndexOutOfBoundsException` 被静默吞掉；这里显式判 `start >= 0` ⇒ 结果相同且不再构造无效异常。
     */
    private fun applySummaryAccent() {
        val summary = getString(R.string.about_description_sigma)
        val gzh = getString(R.string.legado_gzh)
        val spannableString = SpannableString(summary)
        val start = spannableString.indexOf(gzh)
        if (start >= 0) {
            spannableString.setSpan(
                ForegroundColorSpan(accentColor),
                start,
                start + gzh.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tvAppSummary.text = spannableString
    }
}