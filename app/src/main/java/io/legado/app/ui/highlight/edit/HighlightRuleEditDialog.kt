package io.legado.app.ui.highlight.edit

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.data.entities.BookHighlight
import io.legado.app.help.HighlightColors
import io.legado.app.help.HighlightStyle
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.highlight.HighlightRuleActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.HighlightStyleSheet
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.AppRuleTextField
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.GSON
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodySecondary

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 编辑高亮规则（Compose 化，全屏弹框）。
 *
 * 原 View 版继承 BaseDialogFragment + dialog_highlight_rule_edit 布局 + 内嵌 HighlightStyleDialog 子弹框；
 * 迁移后继承 [ComposeDialogFragment]，内容 = [AppDialogFrame]：
 * - 基础字段区：name / pattern / replacement（[AppRuleTextField]）+ useRegex / dotAll（[AppDialogSwitchRow]）
 * - 样式通道区：内联复用 [HighlightStyleSheet]（原 [HighlightStyleDialog] 的 Compose 内容组件）平铺，
 *   取色/选字经宿主回调弹 ColorPickerDialog / FontSelectDialog；原「样式」按钮弹框层级消除
 * - 预览（AD-04）：固定文案 `AnnotatedString` + SpanStyle 渲染 fill/textColor/bold/italic/underline/strike；
 *   保留原局限（underline 不区分线型，box/emphasis/fontPath 不预览）
 * - 保存链路不变：isValidRule → HighlightRuleStore.save → ReadBook.upHighlightRules() →
 *   「批量」来源划线删除 → HighlightRuleActivity.refreshList() → dismiss
 *
 * 已知上限（阻塞点1，短期保留）：取色仍用第三方 ColorPickerDialog 并强制 R.style.AppTheme_Light 亮色；
 * 升级路径：替换为 Compose 自绘色板（复用 HighlightColors 预设通道 + rememberAppDialogStyle 动态色）。
 */
class HighlightRuleEditDialog : ComposeDialogFragment(),
    FontSelectDialog.CallBack,
    ColorPickerDialogListener {

    companion object {
        private const val COLOR_PICKER_TAG = "highlight-rule-color-picker"

        /**
         * 新建规则(预填 pattern/isRegex/style)。
         * [sourceHighlightTime] > 0 表示由「批量」从某手动划线发起,保存成功后删除该划线(转化为规则);
         * 0 表示规则管理页直接新增,不影响任何划线。
         */
        fun create(
            pattern: String,
            isRegex: Boolean = false,
            style: String? = null,
            sourceHighlightTime: Long = 0L
        ): HighlightRuleEditDialog = HighlightRuleEditDialog().apply {
            arguments = Bundle().apply {
                putString("pattern", pattern)
                putBoolean("isRegex", isRegex)
                putString("style", style)
                putLong("sourceHighlightTime", sourceHighlightTime)
            }
        }

        /** 编辑已有规则（适配：id 为 String） */
        fun edit(id: String): HighlightRuleEditDialog = HighlightRuleEditDialog().apply {
            arguments = Bundle().apply { putString("id", id) }
        }

        /** 「批量」保存成功后应删除的来源划线; sourceTime<=0(规则管理页新增)时返回 null */
        fun highlightToRemove(highlights: List<BookHighlight>, sourceTime: Long): BookHighlight? =
            if (sourceTime > 0) highlights.firstOrNull { it.time == sourceTime } else null

        data class ColorPickerConfig(
            val dialogId: Int,
            val color: Int,
            val withAlpha: Boolean,
            val presets: IntArray
        )

        fun colorPickerConfig(dialogId: Int, initial: Int, withAlpha: Boolean): ColorPickerConfig {
            val seed = if (initial != 0) initial else HighlightColors.bg.first()
            return ColorPickerConfig(
                dialogId = dialogId,
                color = seed,
                withAlpha = withAlpha,
                presets = if (withAlpha) HighlightColors.bg else HighlightColors.text
            )
        }

        fun bindColorPickerListener(
            dialog: ColorPickerDialog,
            listener: ColorPickerDialogListener
        ): ColorPickerDialog = dialog.also { it.setColorPickerDialogListener(listener) }

        // 已知上限/compat：ColorPickerDialog 强制亮色主题是为避免暗色下预设色块全白（Issue-2 根因），保留现状
        fun createColorPickerDialog(
            dialogId: Int,
            initial: Int,
            withAlpha: Boolean,
            listener: ColorPickerDialogListener
        ): ColorPickerDialog {
            val config = colorPickerConfig(dialogId, initial, withAlpha)
            val dialog = ColorPickerDialog.newBuilder()
                .setColor(config.color)
                .setShowAlphaSlider(config.withAlpha)
                .setDialogType(ColorPickerDialog.TYPE_PRESETS)
                .setPresets(config.presets)
                .setDialogId(config.dialogId)
                .create()
            dialog.setStyle(androidx.fragment.app.DialogFragment.STYLE_NO_FRAME, R.style.AppTheme_Light)
            return bindColorPickerListener(dialog, listener)
        }
    }

    override val dialogHeight: Int = ViewGroup.LayoutParams.MATCH_PARENT

    private var rule: HighlightRule? = null

    // --- Compose 状态（load 后填充，save 时读取） ---
    private var nameValue by mutableStateOf(TextFieldValue(""))
    private var patternValue by mutableStateOf(TextFieldValue(""))
    private var replacementValue by mutableStateOf(TextFieldValue(""))
    private var useRegex by mutableStateOf(false)
    private var dotAll by mutableStateOf(false)
    private var editingStyle by mutableStateOf(HighlightStyle())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        childFragmentManager.findFragmentByTag(COLOR_PICKER_TAG)
            ?.let { it as? ColorPickerDialog }
            ?.setColorPickerDialogListener(this)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    EditDialogContent()
                }
            }
        }
    }

    @Composable
    private fun EditDialogContent() {
        val style = rememberAppDialogStyle()
        LaunchedEffect(Unit) { loadInitial() }
        val palette = style.toMiuixPalette()
        AppDialogFrame(
            title = stringResource(R.string.highlight_rule_edit_title),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppRuleTextField(
                        value = nameValue,
                        onValueChange = { nameValue = it },
                        label = stringResource(R.string.replace_rule_summary),
                        singleLine = true,
                        style = style
                    )
                    AppRuleTextField(
                        value = patternValue,
                        onValueChange = { patternValue = it },
                        label = stringResource(R.string.highlight_rule_pattern),
                        singleLine = true,
                        style = style
                    )
                    AppRuleTextField(
                        value = replacementValue,
                        onValueChange = { replacementValue = it },
                        label = stringResource(R.string.highlight_rule_replacement),
                        minLines = 2,
                        maxLines = 3,
                        style = style
                    )
                    AppDialogSwitchRow(
                        text = stringResource(R.string.use_regex),
                        checked = useRegex,
                        onCheckedChange = { useRegex = it }
                    )
                    AppDialogSwitchRow(
                        text = stringResource(R.string.highlight_rule_dot_all),
                        checked = dotAll,
                        onCheckedChange = { dotAll = it }
                    )
                    StylePreviewBlock(
                        target = editingStyle,
                        dialogStyle = style
                    )
                    HighlightStyleSheet(
                        style = editingStyle,
                        onStyleChange = { editingStyle = it },
                        onPickColor = { dialogId, initial, withAlpha ->
                            pickColor(dialogId, initial, withAlpha)
                        },
                        onPickFont = { pickFont(it) },
                        fontDisplayName = fontDisplayName(editingStyle.fontPath),
                        // AppDialogFrame 外层已 verticalScroll，此处禁用内部滚动（嵌套会收到无限高度约束崩溃）
                        scrollable = false
                    )
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() }
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    primary = true,
                    onClick = { save() }
                )
            }
        )
    }

    /** 预览（AD-04）：SpanStyle 等价渲染 fill/textColor/bold/italic/underline/strike，保留原局限。 */
    @Composable
    private fun StylePreviewBlock(target: HighlightStyle, dialogStyle: AppDialogStyle) {
        val sampleText = "预览文字 Preview"
        val annotated = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    // Unspecified 视觉等价 null（无填充/保持外层字色），非空满足现有 SpanStyle 重载
                    color = if (target.textColor != 0) {
                        Color(target.textColor)
                    } else {
                        Color.Unspecified
                    },
                    background = if (target.fill != 0) {
                        Color(target.fill)
                    } else {
                        Color.Unspecified
                    },
                    fontWeight = if (target.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (target.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = when {
                        target.underline != null && target.strike != null ->
                            TextDecoration.Underline + TextDecoration.LineThrough
                        target.underline != null -> TextDecoration.Underline
                        target.strike != null -> TextDecoration.LineThrough
                        else -> TextDecoration.None
                    }
                )
            ) { append(sampleText) }
        }
        LegadoMiuixCard(
            modifier = Modifier.fillMaxWidth(),
            color = dialogStyle.fieldSurface,
            contentColor = dialogStyle.primaryText,
            cornerRadius = dialogStyle.panelRadius,
            insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(text = annotated, fontSize = MaterialTheme.typography.bodySecondary.fontSize, color = dialogStyle.primaryText)
        }
    }

    /** 加载规则（edit(id)）或入参（create）到 Compose 状态。 */
    private suspend fun loadInitial() {
        val id = arguments?.getString("id")
        val target = if (!id.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                HighlightRuleStore.load(requireContext()).firstOrNull { it.id == id }
            }
        } else {
            val a = arguments ?: return
            HighlightRule(
                name = a.getString("pattern") ?: "",
                pattern = a.getString("pattern") ?: "",
                isRegex = a.getBoolean("isRegex", false),
                styleJson = a.getString("style") ?: ""
            )
        }
        if (target == null) {
            requireActivity().toastOnUi(getString(R.string.highlight_rule_not_found))
            dismissAllowingStateLoss()
            return
        }
        rule = target
        fillFrom(target)
    }

    private fun fillFrom(r: HighlightRule) {
        nameValue = TextFieldValue(r.name)
        patternValue = TextFieldValue(r.pattern)
        replacementValue = TextFieldValue(r.replacement)
        useRegex = r.isRegex
        dotAll = r.isDotAll
        editingStyle = r.toHighlightStyle()
    }

    private fun getRule(): HighlightRule {
        val r = rule ?: HighlightRule()
        r.name = nameValue.text
        r.pattern = patternValue.text
        r.isRegex = useRegex
        r.replacement = replacementValue.text
        r.isDotAll = dotAll
        r.styleJson = GSON.toJson(editingStyle)
        return r
    }

    /** 内联验证（pattern 非空, isRegex 时正则可编译） */
    private fun isValidRule(r: HighlightRule): Boolean {
        if (r.pattern.isBlank()) return false
        if (r.isRegex) {
            return runCatching { Regex(r.pattern) }.isSuccess
        }
        return true
    }

    /** load 整个列表 → 替换或追加 → save 整个列表，链路保持与旧版一致 */
    private fun save() {
        val r = getRule()
        if (!isValidRule(r)) {
            requireActivity().toastOnUi(getString(R.string.highlight_rule_invalid, r.pattern))
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val rules = HighlightRuleStore.load(requireContext())
                val idx = rules.indexOfFirst { it.id == r.id }
                if (idx >= 0) {
                    rules[idx] = r
                } else {
                    rules.add(r)
                }
                HighlightRuleStore.save(requireContext(), rules)
            }
            ReadBook.upHighlightRules()
            // 「批量」转化: 规则已接管该处文字, 删除发起的那条手动划线, 避免同段文字双份高亮
            val srcTime = arguments?.getLong("sourceHighlightTime", 0L) ?: 0L
            highlightToRemove(ReadBook.highlights, srcTime)?.let { ReadBook.removeHighlight(it) }
            // 通知 Activity 刷新列表（保存后列表不更新问题）
            (activity as? HighlightRuleActivity)?.refreshList()
            dismissAllowingStateLoss()
        }
    }

    private fun pickColor(dialogId: Int, initial: Int, withAlpha: Boolean) {
        createColorPickerDialog(
            dialogId = dialogId,
            initial = initial,
            withAlpha = withAlpha,
            listener = this@HighlightRuleEditDialog
        ).show(childFragmentManager, COLOR_PICKER_TAG)
    }

    private fun pickFont(current: String) {
        showDialogFragment(FontSelectDialog())
    }

    // --- FontSelectDialog.CallBack ---
    override val curFontPath: String get() = editingStyle.fontPath

    override fun selectFont(path: String) {
        editingStyle = editingStyle.copy(fontPath = path)
    }

    // --- ColorPickerDialogListener ---
    override fun onColorSelected(dialogId: Int, color: Int) {
        editingStyle = HighlightStyleDialog.applyChannelColor(editingStyle, dialogId, color)
    }

    override fun onDialogDismissed(dialogId: Int) {}

    /** 字体路径转可读名（content uri 解码后取末段文件名）；空=默认 */
    private fun fontDisplayName(path: String): String {
        if (path.isEmpty()) return getString(R.string.highlight_font_default)
        return Uri.decode(path)?.substringAfterLast('/')?.ifBlank { path } ?: path
    }
}
