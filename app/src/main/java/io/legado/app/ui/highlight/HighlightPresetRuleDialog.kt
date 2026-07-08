package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogHighlightPresetRuleBinding
import io.legado.app.databinding.ItemHighlightPresetAddBinding
import io.legado.app.help.HighlightRulePreview
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleGroupStore
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * F-P1-2 高亮预设规则 Dialog（借鉴蛋蛋Max,适配当前项目）
 *
 * 适配说明：
 * 1. shape_highlight_rule_sheet → shape_card_view
 * 2. GradientDrawable 动态背景 → shape_card_view 静态背景
 * 3. 移除 attachBottomSheetDismiss（当前项目无此扩展）
 * 4. 移除 observeEvent(EventBus.UP_CONFIG)（非必须主题切换监听）
 * 5. 移除 initTheme() 的复杂主题色计算（用 @color/primaryText/@color/secondaryText/@color/accent）
 * 6. HighlightRulePreview 用简化版（只 BackgroundColorSpan + ForegroundColorSpan）
 * 7. setLayout(MATCH_PARENT, 0.85f) + Gravity.BOTTOM 实现底部弹出
 * 8. adaptationSoftKeyboard=true + vw_bg.setOnClickListener{} 阻止冒泡
 * 已知上限：预览不显示下划线/着重号等高级样式 | 升级路径：移植 Span 类后可恢复
 */
class HighlightPresetRuleDialog @JvmOverloads constructor(
    private val defaultGroup: String? = null,
    private val onAddRule: (HighlightRule) -> Unit = {},
) : BaseDialogFragment(R.layout.dialog_highlight_preset_rule, true) {

    private val binding by viewBinding(DialogHighlightPresetRuleBinding::bind)
    private val adapter by lazy { PresetRuleAdapter(requireContext()) }
    private val presetRules by lazy { HighlightRuleStore.defaultPresetRules(requireContext()) }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.85f)
        dialog?.window?.setGravity(Gravity.BOTTOM)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        // 阻止内层卡片点击冒泡到根 view 触发 dismiss
        binding.vwBg.setOnClickListener { }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        adapter.setItems(presetRules)

        binding.ivBack.setOnClickListener { dismiss() }
    }

    private inner class PresetRuleAdapter(context: android.content.Context) :
        RecyclerAdapter<HighlightRule, ItemHighlightPresetAddBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemHighlightPresetAddBinding {
            return ItemHighlightPresetAddBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemHighlightPresetAddBinding,
            item: HighlightRule,
            payloads: MutableList<Any>
        ) {
            binding.tvTitle.text = item.name
            binding.tvDesc.text = item.displayPattern()
            binding.tvPreview.text = HighlightRulePreview.build(item)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemHighlightPresetAddBinding) {
            binding.ivAdd.setOnClickListener {
                getItem(holder.layoutPosition)?.let { item ->
                    val groupToUse = defaultGroup ?: HighlightRuleGroupStore.DEFAULT_GROUP
                    onAddRule(item.copy(
                        id = System.currentTimeMillis().toString(),
                        group = groupToUse
                    ))
                    dismiss()
                }
            }
        }
    }
}
