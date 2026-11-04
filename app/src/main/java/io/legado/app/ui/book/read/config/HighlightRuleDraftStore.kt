package io.legado.app.ui.book.read.config

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 6.3 高亮规则草稿区（AI 生成 → 预览 → 应用 → 回滚）。
 *
 * 与替换规则草稿（`ReplaceRuleKernel`）同构，但高亮规则存于 SharedPreferences，
 * 因此「应用」= 写进既有 [HighlightRuleStore]，**不改匹配器**。
 *
 * 已知上限：草稿与快照仅本次进程内有效（与替换规则同口径：跨进程续用再落盘）。
 */
object HighlightRuleDraftStore {

    data class ApplyResult(val applied: Int, val snapshotCount: Int)

    data class RollbackResult(val restored: Int, val deleted: Int)

    private val drafts = ConcurrentHashMap<String, HighlightRule>()

    /** 快照值：`original == null` 表示应用前该规则不存在 ⇒ 回滚时删除（ConcurrentHashMap 不接受 null 值） */
    private data class Snapshot(val original: HighlightRule?)

    /** 应用前快照：`ruleId -> Snapshot` */
    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    /** 写入 / 覆盖一条草稿（不入库）。id 为空时按名称+规则生成稳定键，避免同名草稿散落。 */
    fun upsert(rule: HighlightRule): Map<String, Any?> {
        val id = rule.id.ifBlank { "ai_${rule.name}_${rule.pattern}".hashCode().toString() }
        val draft = rule.copy(id = id)
        val isNew = !drafts.containsKey(id)
        drafts[id] = draft
        return mapOf(
            "draftId" to id,
            "new" to isNew,
            "draftCount" to drafts.size,
            "ruleName" to draft.name
        )
    }

    /** 查看当前草稿（供 AI 确认改动内容）。 */
    fun list(): List<HighlightRule> = drafts.values.sortedBy { it.id }

    /** 丢弃草稿（不影响已生效规则）。 */
    fun discard(): Int {
        val size = drafts.size
        drafts.clear()
        return size
    }

    /** 未应用的草稿数（UI 提示"还有几条待确认"）。 */
    fun draftCount(): Int = drafts.size

    /** 可回滚的快照数（0 = 没有可回滚的应用）。 */
    fun snapshotCount(): Int = snapshots.size

    /**
     * 应用全部草稿：先快照「每条草稿对应的当前规则」，再写回既有规则库。
     * 重复 apply 会用**本次之前**的库内状态覆盖快照 ⇒ 回滚到最近一次 apply 之前。
     */
    fun apply(context: Context): ApplyResult {
        if (drafts.isEmpty()) return ApplyResult(0, snapshots.size)
        val merged = HighlightRuleStore.load(context).associateBy { it.id }.toMutableMap()
        drafts.values.forEach { draft ->
            snapshots[draft.id] = Snapshot(merged[draft.id])
            merged[draft.id] = draft
        }
        HighlightRuleStore.save(context, merged.values.toList())
        val applied = drafts.size
        drafts.clear()
        return ApplyResult(applied, snapshots.size)
    }

    /**
     * 回滚到最近一次 `apply` 之前：原规则还原，apply 时新增的规则被删除；
     * **未被草稿触碰过的规则保持原样**（不能整表覆盖回快照）。
     */
    fun rollback(context: Context): RollbackResult {
        if (snapshots.isEmpty()) return RollbackResult(0, 0)
        val merged = HighlightRuleStore.load(context).associateBy { it.id }.toMutableMap()
        var restored = 0
        var deleted = 0
        snapshots.forEach { (id, snapshot) ->
            val original = snapshot.original
            if (original == null) {
                if (merged.remove(id) != null) deleted++
            } else {
                merged[id] = original
                restored++
            }
        }
        HighlightRuleStore.save(context, merged.values.toList())
        snapshots.clear()
        return RollbackResult(restored, deleted)
    }

    /** 清空草稿与快照（切换书籍/退出 AI 会话时调用）。 */
    fun clearAll() {
        drafts.clear()
        snapshots.clear()
    }
}
