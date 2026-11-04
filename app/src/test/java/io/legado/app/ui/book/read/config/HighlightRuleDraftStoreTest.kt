package io.legado.app.ui.book.read.config

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 6.3 高亮规则草稿：AI 生成草稿 → 应用 → 回滚。
 * 守四件事：① 同 id 覆盖不膨胀；② 应用后真的写进规则库；③ 回滚精确还原且不误删未触碰规则；
 * ④ 空草稿/无快照不产生副作用（不能一按就清空用户规则）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HighlightRuleDraftStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HighlightRuleDraftStore.clearAll()
        // 从一个可预期的库状态开始（否则 load 首次会按内置模板重置）
        HighlightRuleStore.reset(context)
    }

    @Test
    fun `同 id 草稿覆盖不膨胀且空 id 可兜底`() {
        val first = HighlightRuleDraftStore.upsert(
            HighlightRule(id = "ai_1", name = "AI 草稿", pattern = "说")
        )
        assertEquals(true, first["new"])
        assertEquals(1, HighlightRuleDraftStore.draftCount())

        val second = HighlightRuleDraftStore.upsert(
            HighlightRule(id = "ai_1", name = "AI 草稿改", pattern = "笑道")
        )
        assertEquals(false, second["new"])
        assertEquals(1, HighlightRuleDraftStore.draftCount())
        assertEquals("AI 草稿改", HighlightRuleDraftStore.list().first().name)

        // id 为空时兜底生成非空键（AI 常不带稳定 id，不能因此产生散落草稿）
        val auto = HighlightRuleDraftStore.upsert(
            HighlightRule(id = "", name = "无 id", pattern = "断言")
        )
        assertTrue((auto["draftId"] as String).isNotBlank())
        assertEquals(2, HighlightRuleDraftStore.draftCount())
        assertEquals(2, HighlightRuleDraftStore.discard())
        assertEquals(0, HighlightRuleDraftStore.draftCount())
    }

    @Test
    fun `应用写入规则库且回滚精确还原`() {
        val original = HighlightRule(id = "u1", name = "用户规则", pattern = "旧模式")
        val untouched = HighlightRule(id = "u2", name = "未触碰", pattern = "另一条")
        HighlightRuleStore.save(context, listOf(original, untouched))

        // 修改已有规则 + 新增 AI 规则
        HighlightRuleDraftStore.upsert(original.copy(name = "用户规则AI改", pattern = "新模式"))
        HighlightRuleDraftStore.upsert(HighlightRule(id = "ai_new", name = "AI 新增", pattern = "哈哈"))

        val applied = HighlightRuleDraftStore.apply(context)
        assertEquals(2, applied.applied)
        assertEquals(2, applied.snapshotCount)
        assertEquals(0, HighlightRuleDraftStore.draftCount())

        val afterApply = HighlightRuleStore.load(context).associateBy { it.id }
        assertEquals("用户规则AI改", afterApply["u1"]?.name)
        assertEquals("新模式", afterApply["u1"]?.pattern)
        assertTrue("AI 新增应写入库", afterApply.containsKey("ai_new"))
        assertEquals("另一条", afterApply["u2"]?.pattern)

        val rollback = HighlightRuleDraftStore.rollback(context)
        assertEquals(1, rollback.restored)
        assertEquals(1, rollback.deleted)
        assertEquals(0, HighlightRuleDraftStore.snapshotCount())

        val afterRollback = HighlightRuleStore.load(context).associateBy { it.id }
        assertEquals("用户规则", afterRollback["u1"]?.name)
        assertEquals("旧模式", afterRollback["u1"]?.pattern)
        assertNull("apply 新增的规则回滚后必须消失", afterRollback["ai_new"])
        assertEquals("未触碰的规则不能被整表覆盖", "另一条", afterRollback["u2"]?.pattern)
    }

    @Test
    fun `空草稿与无快照不产生副作用`() {
        val before = HighlightRuleStore.load(context).map { it.id to it.pattern }.toSet()

        val applied = HighlightRuleDraftStore.apply(context)
        assertEquals(0, applied.applied)
        assertEquals(0, applied.snapshotCount)
        assertEquals(before.size, HighlightRuleStore.load(context).size)

        val rollback = HighlightRuleDraftStore.rollback(context)
        assertEquals(0, rollback.restored)
        assertEquals(0, rollback.deleted)
        assertEquals(before, HighlightRuleStore.load(context).map { it.id to it.pattern }.toSet())
    }

    @Test
    fun `丢弃草稿不影响已生效规则`() {
        val original = HighlightRule(id = "u1", name = "用户规则", pattern = "旧模式")
        HighlightRuleStore.save(context, listOf(original))
        HighlightRuleDraftStore.upsert(original.copy(pattern = "新模式"))
        assertEquals(1, HighlightRuleDraftStore.draftCount())

        HighlightRuleDraftStore.discard()

        assertEquals("旧模式", HighlightRuleStore.load(context).first { it.id == "u1" }.pattern)
    }
}
