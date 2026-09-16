package io.legado.app.ui.highlight.edit

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
import io.legado.app.R
import io.legado.app.data.entities.BookHighlight
import io.legado.app.help.HighlightColors
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.HighlightStyle
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.HighlightActionMenu
import io.legado.app.ui.book.read.HighlightStyleDialog
import io.legado.app.ui.widget.components.ColorPickerSheet
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
 * R1a：取色已改用 Compose 版 `ColorPickerSheet`（统一取色入口，支持半透明与项目色板预设），
 * 不再使用第三方 ColorPickerDialog —— 其"强制亮色主题"的已知上限随之解除。
 */
class HighlightRuleEditDialog : ComposeDialogFragment(),
    FontSelectDialog.CallBack {

    companion object {

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
            // D1 修复：墨水屏态下 bg/text 预设可为**空数组**——原 `HighlightColors.bg.first()`
            // 会抛 NoSuchElementException（取色即崩）；此处同时为非空预设做兜底
            val bgPresets = HighlightColors.bg
            val textPresets = HighlightColors.text
            val presets = if (withAlpha) {
                if (bgPresets.isEmpty()) textPresets else bgPresets
            } else {
                if (textPresets.isEmpty()) bgPresets else textPresets
            }
            val seed = if (initial != 0) initial else presets.firstOrNull() ?: 0
            return ColorPickerConfig(
                dialogId = dialogId,
                color = seed,
                withAlpha = withAlpha,
                presets = presets
            )
        }

        // R1a：第三方取色器创建/绑定入口已移除（改用 Compose `ColorPickerSheet`）
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

    /** R1a：Compose 版取色面板状态（null = 未打开）；替换第三方 `ColorPickerDialog` */
    private var pickerConfig by mutableStateOf<ColorPickerConfig?>(null)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    EditDialogContent()
                    // R1a：统一取色入口（Compose 版 ColorPickerSheet），与弹框并列于同一 Compose 树
                    HighlightColorPickerHost()
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

    /** 预览：SpanStyle 等价渲染 字色/粗斜体/下划线/删除线/阴影；R1a 起背景形状由 `drawBehind`
     * 自绘（复用 [HighlightGeometry.fillBand]，与阅读页同一几何），矩形仍用 `SpanStyle.background`。 */
    @Composable
    private fun StylePreviewBlock(target: HighlightStyle, dialogStyle: AppDialogStyle) {
        val sampleText = "预览文字 Preview"
        val shape = target.resolvedFillShape
        val fill = target.fill
        val drawShape = fill != 0 && shape != HighlightStyle.FillShape.RECTANGLE
        val shadowStyle = target.shadow?.normalized()
        val annotated = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    // Unspecified 视觉等价 null（无填充/保持外层字色），非空满足现有 SpanStyle 重载
                    color = if (target.textColor != 0) {
                        Color(target.textColor)
                    } else {
                        Color.Unspecified
                    },
                    background = if (fill != 0 && !drawShape) {
                        Color(fill)
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
                    },
                    // R1a：文字阴影（color == 0 时预览取黑，面板底色较浅）
                    shadow = shadowStyle?.let {
                        Shadow(
                            color = Color(if (it.color != 0) it.color else 0xFF000000.toInt()),
                            offset = Offset(it.dx, it.dy),
                            blurRadius = it.radius
                        )
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (drawShape) {
                            Modifier.drawBehind {
                                // 与阅读页同一几何（HighlightGeometry.fillBand），以文本块高度为"行"
                                val band = HighlightGeometry.fillBand(
                                    baseline = size.height * 0.8f,
                                    textSize = size.height * 0.62f,
                                    height = size.height,
                                    shape = shape
                                )
                                val h = (band.bottom - band.top).coerceAtLeast(0f)
                                if (h > 0f) {
                                    val topLeft = Offset(0f, band.top)
                                    val sz = Size(size.width, h)
                                    when (shape) {
                                        HighlightStyle.FillShape.ROUNDED -> drawRoundRect(
                                            color = Color(fill),
                                            topLeft = topLeft,
                                            size = sz,
                                            cornerRadius = CornerRadius(3.dp.toPx())
                                        )

                                        HighlightStyle.FillShape.PILL -> drawRoundRect(
                                            color = Color(fill),
                                            topLeft = topLeft,
                                            size = sz,
                                            cornerRadius = CornerRadius(h / 2f)
                                        )

                                        else -> drawRect(color = Color(fill), topLeft = topLeft, size = sz)
                                    }
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                Text(
                    text = annotated,
                    fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                    color = dialogStyle.primaryText
                )
            }
        }
    }

    /** R1a：取色面板宿主（Compose 版 `ColorPickerSheet`；未打开时不渲染任何内容） */
    @Composable
    private fun HighlightColorPickerHost() {
        pickerConfig?.let { cfg ->
            ColorPickerSheet(
                title = stringResource(channelTitleRes(cfg.dialogId)),
                initialColor = cfg.color,
                withAlpha = cfg.withAlpha,
                presets = cfg.presets,
                onConfirm = { color ->
                    editingStyle =
                        HighlightStyleDialog.applyChannelColor(editingStyle, cfg.dialogId, color)
                    pickerConfig = null
                },
                onDismiss = { pickerConfig = null }
            )
        }
    }

    /** R1a：取色面板标题（按通道 dialogId 取对应通道名） */
    private fun channelTitleRes(dialogId: Int): Int = when (dialogId) {
        HighlightActionMenu.HL_FILL -> R.string.highlight_bg_color
        HighlightActionMenu.HL_TEXT -> R.string.highlight_text_color
        HighlightActionMenu.HL_UNDERLINE -> R.string.highlight_underline
        HighlightActionMenu.HL_STRIKE -> R.string.highlight_strike
        HighlightActionMenu.HL_BOX -> R.string.highlight_box
        HighlightActionMenu.HL_SHADOW -> R.string.highlight_shadow
        else -> R.string.highlight_emphasis
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
        // F3/2.6：isRegex=false 且内容含正则元字符 → 高概率是"写了正则没开开关"，
        // 保存时一次性明示（匹配链路另有一次性日志留痕），杜绝静默字面量匹配
        if (!r.isRegex && r.pattern.any { it in "\\{}[]|()*+?^$" }) {
            requireActivity().toastOnUi(getString(R.string.highlight_rule_literal_match_hint))
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
        // R1a：打开 Compose 版取色面板（由 `HighlightColorPickerHost` 渲染）
        pickerConfig = colorPickerConfig(dialogId, initial, withAlpha)
    }

    private fun pickFont(current: String) {
        showDialogFragment(FontSelectDialog())
    }

    // --- FontSelectDialog.CallBack ---
    override val curFontPath: String get() = editingStyle.fontPath

    override fun selectFont(path: String) {
        editingStyle = editingStyle.copy(fontPath = path)
    }

    // R1a：取色回调已并入 `HighlightColorPickerHost`（Compose `ColorPickerSheet.onConfirm`）

    /** 字体路径转可读名（content uri 解码后取末段文件名）；空=默认 */
    private fun fontDisplayName(path: String): String {
        if (path.isEmpty()) return getString(R.string.highlight_font_default)
        return Uri.decode(path)?.substringAfterLast('/')?.ifBlank { path } ?: path
    }
}
