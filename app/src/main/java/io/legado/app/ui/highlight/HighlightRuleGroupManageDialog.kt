package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleGroupStore
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.ComposeGroupManageDialogContent
import io.legado.app.utils.GSON
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi

/**
 * 高亮规则分组管理弹框（Compose 化，薄壳受控模式）。
 *
 * 原 View 版继承 BaseDialogFragment + dialog_highlight_rule_group_manage 布局；
 * 迁移后继承 [ComposeDialogFragment]，内容复用 [ComposeGroupManageDialogContent]：
 * - 新增/重命名走内联编辑卡片（空名校验在组件 submit 兜底，重名校验在薄壳内 toast 拒绝）
 * - 行尾「更多」菜单复现原 R.menu.highlight_rule_group_item（rename / export / delete），默认分组禁删
 * - 删除确认子弹框 + 默认分组保护，删除后规则批量改回默认分组
 * - 导出走 sendToClip(GSON.toJson(目标规则))
 * - 行点击选中分组 / 「查看全部」保留原回调契约（onSelectGroup 由调用方决定行为）
 */
class HighlightRuleGroupManageDialog @JvmOverloads constructor(
    private val onChanged: (oldGroup: String?, newGroup: String?) -> Unit = { _, _ -> },
    private val onSelectGroup: (String?) -> Unit = {},
) : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management
    override val dialogGravity: Int = Gravity.BOTTOM

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                GroupManageDialogContent(
                    onChanged = onChanged,
                    onSelectGroup = onSelectGroup,
                    onDismiss = { dismissAllowingStateLoss() }
                )
            }
        }
    }

    @Composable
    private fun GroupManageDialogContent(
        onChanged: (oldGroup: String?, newGroup: String?) -> Unit,
        onSelectGroup: (String?) -> Unit,
        onDismiss: () -> Unit
    ) {
        val context = LocalContext.current
        var groups by remember { mutableStateOf<List<String>>(HighlightRuleGroupStore.load(context)) }
        var rules by remember { mutableStateOf<List<HighlightRule>>(HighlightRuleStore.load(context)) }
        var editTarget by remember { mutableStateOf<String?>(null) }
        var editSeq by remember { mutableStateOf(0L) }

        fun saveAll() {
            HighlightRuleGroupStore.save(context, groups)
            HighlightRuleStore.save(context, rules)
        }

        fun exportGroup(group: String) {
            val targetRules = rules.filter { it.group == group }
            if (targetRules.isEmpty()) {
                context.toastOnUi(context.getString(R.string.highlight_rule_group_no_rule_export))
                return
            }
            context.sendToClip(GSON.toJson(targetRules))
            context.toastOnUi(
                context.getString(R.string.highlight_rule_group_export_count, targetRules.size)
            )
        }

        fun confirmDeleteGroup(group: String) {
            if (group == HighlightRuleGroupStore.DEFAULT_GROUP) {
                context.toastOnUi(context.getString(R.string.highlight_rule_group_default_protected))
                return
            }
            alert(context.getString(R.string.delete_group)) {
                setMessage(context.getString(R.string.highlight_rule_group_delete_confirm))
                okButton {
                    groups = groups.filterNot { it == group }
                    rules = rules.map {
                        if (it.group == group) it.copy(group = HighlightRuleGroupStore.DEFAULT_GROUP) else it
                    }
                    saveAll()
                    onChanged(group, null)
                }
                cancelButton()
            }
        }

        fun moreActionsFor(group: String): List<MenuAction> {
            val actions = mutableListOf(
                MenuAction(
                    icon = Icons.Filled.Edit,
                    title = context.getString(R.string.edit),
                    onClick = {
                        editTarget = group
                        editSeq++
                    }
                ),
                MenuAction(
                    icon = Icons.Filled.Share,
                    title = context.getString(R.string.export),
                    onClick = { exportGroup(group) }
                )
            )
            if (group != HighlightRuleGroupStore.DEFAULT_GROUP) {
                actions += MenuAction(
                    icon = Icons.Filled.Delete,
                    title = context.getString(R.string.delete),
                    tint = androidx.compose.ui.graphics.Color(0xFFE53935),
                    onClick = { confirmDeleteGroup(group) }
                )
            }
            return actions
        }

        ComposeGroupManageDialogContent(
            groups = groups,
            message = context.getString(R.string.highlight_rule_group_rules_count, rules.size),
            groupCountText = { group ->
                context.getString(R.string.highlight_rule_group_rules_count, rules.count { it.group == group })
            },
            onAddGroup = { name ->
                if (groups.contains(name)) {
                    context.toastOnUi(context.getString(R.string.highlight_rule_group_name_exists))
                } else {
                    groups = groups + name
                    saveAll()
                    onChanged(null, null)
                }
            },
            onRenameGroup = { old, new ->
                if (groups.contains(new) && new != old) {
                    context.toastOnUi(context.getString(R.string.highlight_rule_group_name_exists))
                } else {
                    groups = groups.map { if (it == old) new else it }
                    rules = rules.map { if (it.group == old) it.copy(group = new) else it }
                    saveAll()
                    onChanged(old, new)
                }
            },
            onDeleteGroup = { group -> confirmDeleteGroup(group) },
            onSelectGroup = { group ->
                onSelectGroup(group)
                onDismiss()
            },
            onViewAll = {
                onSelectGroup(null)
                onDismiss()
            },
            viewAllLabel = context.getString(R.string.highlight_rule_group_view_all),
            moreActions = { group -> moreActionsFor(group) },
            externalEdit = editTarget to editSeq,
            onDismiss = onDismiss
        )
    }
}