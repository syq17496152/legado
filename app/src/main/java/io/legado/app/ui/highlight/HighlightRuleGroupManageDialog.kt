package io.legado.app.ui.highlight

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogHighlightRuleGroupManageBinding
import io.legado.app.databinding.ItemHighlightRuleGroupBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleGroupStore
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * F-P1-2 高亮规则分组管理 Dialog（借鉴蛋蛋Max,适配当前项目）
 *
 * 适配说明：
 * 1. shape_highlight_rule_* drawable → shape_card_view + MaterialButton + ?attr/selectableItemBackground
 * 2. 自定义 ViewBindingHolder → 标准 ItemHighlightRuleGroupBinding（RecyclerAdapter 要求 VB : ViewBinding）
 * 3. 修复蛋蛋Max exportGroup 的 GBK 乱码（"璇ュ垎缁勬殏鏃犺鍒欏彲瀵煎嚭" → "该分组暂无规则可导出"）
 * 4. 移除 attachBottomSheetDismiss（当前项目无此扩展,用默认 dismiss）
 * 5. 移除 observeEvent(EventBus.UP_CONFIG)（非必须主题切换监听）
 * 6. 移除 initTheme() 的 cardBgColor（用 shape_card_view 默认背景）
 * 7. setLayout(MATCH_PARENT, 0.85f) + Gravity.BOTTOM 实现底部弹出
 * 8. adaptationSoftKeyboard=true + vw_bg.setOnClickListener{} 阻止冒泡,实现点击外部 dismiss
 * 已知上限：无主题切换实时响应 | 升级路径：后续接入 ThemeStore 监听
 */
class HighlightRuleGroupManageDialog @JvmOverloads constructor(
    private val onChanged: (oldGroup: String?, newGroup: String?) -> Unit = { _, _ -> },
    private val onSelectGroup: (String?) -> Unit = {},
) : BaseDialogFragment(R.layout.dialog_highlight_rule_group_manage, true) {

    private val binding by viewBinding(DialogHighlightRuleGroupManageBinding::bind)
    private val adapter by lazy { GroupAdapter(requireContext()) }
    private val groups = ArrayList<String>()
    private val rules = ArrayList<HighlightRule>()

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
        binding.ivBack.setOnClickListener { dismiss() }
        binding.tvAddGroup.setOnClickListener { showGroupInputDialog(null) }
        binding.llViewAll.setOnClickListener {
            onSelectGroup(null)
            dismiss()
        }
        loadData()
    }

    private fun loadData() {
        groups.clear()
        groups.addAll(HighlightRuleGroupStore.load(requireContext()))
        rules.clear()
        rules.addAll(HighlightRuleStore.load(requireContext()))
        adapter.setItems(groups.toList())
        binding.tvEmptyMsg.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        binding.tvAllCount.text = "${rules.size} 条规则"
    }

    private fun showGroupInputDialog(source: String?) {
        val editText = EditText(requireContext()).apply {
            setText(source.orEmpty())
            setSelection(text.length)
            hint = "输入分组名称"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 8.dpToPx(), 20.dpToPx(), 0)
            addView(
                editText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        alert(if (source == null) "新增分组" else "重命名分组") {
            customView { container }
            okButton {
                val newName = editText.text?.toString()?.trim().orEmpty()
                if (newName.isBlank()) {
                    requireContext().toastOnUi("分组名称不能为空")
                    return@okButton
                }
                if (groups.contains(newName) && newName != source) {
                    requireContext().toastOnUi("分组名称已存在")
                    return@okButton
                }
                if (source == null) {
                    groups.add(newName)
                    HighlightRuleGroupStore.save(requireContext(), groups)
                    loadData()
                    onChanged(null, null)
                } else {
                    val index = groups.indexOf(source)
                    if (index >= 0) groups[index] = newName
                    rules.replaceAll { rule ->
                        if (rule.group == source) rule.copy(group = newName) else rule
                    }
                    HighlightRuleGroupStore.save(requireContext(), groups)
                    HighlightRuleStore.save(requireContext(), rules)
                    loadData()
                    onChanged(source, newName)
                }
            }
            cancelButton()
        }
    }

    private fun deleteGroup(group: String) {
        if (group == HighlightRuleGroupStore.DEFAULT_GROUP) {
            context?.toastOnUi("默认分组不能删除")
            return
        }
        alert("删除分组") {
            setMessage("删除后，该分组下的规则会移动到默认分组。")
            okButton {
                groups.remove(group)
                rules.replaceAll { rule ->
                    if (rule.group == group) {
                        rule.copy(group = HighlightRuleGroupStore.DEFAULT_GROUP)
                    } else {
                        rule
                    }
                }
                HighlightRuleGroupStore.save(requireContext(), groups)
                HighlightRuleStore.save(requireContext(), rules)
                loadData()
                onChanged(group, null)
            }
            cancelButton()
        }
    }

    private fun exportGroup(group: String) {
        val targetRules = rules.filter { it.group == group }
        if (targetRules.isEmpty()) {
            // 修复蛋蛋Max GBK 乱码："璇ュ垎缁勬殏鏃犺鍒欏彲瀵煎嚭" → "该分组暂无规则可导出"
            context?.toastOnUi("该分组暂无规则可导出")
            return
        }
        requireContext().sendToClip(GSON.toJson(targetRules))
        // 修复蛋蛋Max GBK 乱码："宸插鍒?${targetRules.size} 鏉¤鍒?" → "已复制 ${targetRules.size} 条规则"
        context?.toastOnUi("已复制 ${targetRules.size} 条规则")
    }

    private fun showItemMenu(group: String, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.highlight_rule_group_item, menu)
            if (group == HighlightRuleGroupStore.DEFAULT_GROUP) {
                menu.findItem(R.id.menu_delete)?.isVisible = false
            }
            setOnMenuItemClickListener { item: MenuItem ->
                when (item.itemId) {
                    R.id.menu_rename_group -> showGroupInputDialog(group)
                    R.id.menu_export_group -> exportGroup(group)
                    R.id.menu_delete -> deleteGroup(group)
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
        }.show()
    }

    private fun groupCount(group: String): Int {
        return rules.count { it.group == group }
    }

    private inner class GroupAdapter(context: android.content.Context) :
        RecyclerAdapter<String, ItemHighlightRuleGroupBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemHighlightRuleGroupBinding {
            return ItemHighlightRuleGroupBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemHighlightRuleGroupBinding,
            item: String,
            payloads: MutableList<Any>
        ) {
            binding.tvTitle.text = item
            binding.tvCount.text = "${groupCount(item)} 条规则"
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemHighlightRuleGroupBinding) {
            binding.root.setOnClickListener {
                getItem(holder.layoutPosition)?.let { group ->
                    onSelectGroup(group)
                    dismiss()
                }
            }
            binding.root.setOnLongClickListener {
                getItem(holder.layoutPosition)?.let { group ->
                    showItemMenu(group, binding.root)
                }
                true
            }
            binding.tvEdit.setOnClickListener {
                getItem(holder.layoutPosition)?.let(::showGroupInputDialog)
            }
            binding.tvMore.setOnClickListener {
                getItem(holder.layoutPosition)?.let { group ->
                    showItemMenu(group, binding.tvMore)
                }
            }
        }
    }
}
