package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线角色发现（T4.2 规则兜底）：cue 邻接过滤 + 排序 + 代词排除。
 * 纯函数测试，不触库不联网（落库路径由 [OfflineCharacterDiscovery.ensureCharacters] 在设备上验）。
 */
class OfflineCharacterDiscoveryTest {

    @Test
    fun `只收 cue 邻接的人名并按出现次数降序`() {
        val paragraphs = listOf(
            "张三说：“你终于来了，我等了很久。”",
            "李四说：“路上耽搁了，别急，先喝口茶。”",
            "张三笑了笑：“这么多年，你还是老样子。”",
            "夜色渐深，客栈里只剩两盏灯。"
        )
        val candidates = OfflineCharacterDiscovery.candidates(paragraphs)
        assertEquals(listOf("张三", "李四"), candidates.map { it.name })
        assertEquals(2, candidates[0].cueCount)
        assertEquals(1, candidates[1].cueCount)
    }

    @Test
    fun `无 cue 的人名不落候选`() {
        val paragraphs = listOf("张三站在门口，风吹动他的衣角。")
        assertTrue(OfflineCharacterDiscovery.candidates(paragraphs).isEmpty())
    }

    @Test
    fun `代词与冒号剧本写法`() {
        val paragraphs = listOf(
            "他说：“别急。”",
            "李四：路上耽搁了。"
        )
        val candidates = OfflineCharacterDiscovery.candidates(paragraphs)
        assertEquals(listOf("李四"), candidates.map { it.name })
    }

    @Test
    fun `候选数量受上限约束`() {
        val names = listOf("张三", "李四", "王五", "赵六", "陈七", "杨八", "周九", "吴十")
        val paragraphs = names.map { name -> "${name}说：“好。”" }
        val candidates = OfflineCharacterDiscovery.candidates(paragraphs, limit = 3)
        assertEquals(3, candidates.size)
        assertEquals(names.take(3), candidates.map { it.name })
    }

    /** 2026-11-02 真机回归：单姓最长只取 2 字，避免把后续动词吞成"郑吒嗤"这类碎片名 */
    @Test
    fun `单姓人名不吞后续动词`() {
        val paragraphs = listOf(
            "郑吒嗤笑道：“你想真正的活着吗？”",
            "郑吒忽然道：“先别急，听听他们怎么说。”"
        )
        val candidates = OfflineCharacterDiscovery.candidates(paragraphs)
        assertEquals(listOf("郑吒"), candidates.map { it.name })
        assertEquals(2, candidates[0].cueCount)
    }

    @Test
    fun `复姓保留三字名`() {
        val paragraphs = listOf("欧阳锋说：“这一招你看清了么？”")
        val candidates = OfflineCharacterDiscovery.candidates(paragraphs)
        assertEquals(listOf("欧阳锋"), candidates.map { it.name })
    }

    /** 2026-11-02 用户反馈：萧宏律/赵樱空是真名，不能被 2 字上限截断成萧宏/赵樱 */
    @Test
    fun `三字单姓名在多次 cue 命中时完整保留`() {
        val paragraphs = listOf(
            "萧宏律说：“这里的规则不是你想的那样。”",
            "萧宏律道：“先活下去再说。”",
            "赵樱空道：“别废话，走。”",
            "赵樱空说：“我断后。”"
        )
        val names = OfflineCharacterDiscovery.candidates(paragraphs).map { it.name }
        assertEquals(listOf("萧宏律", "赵樱空"), names)
        // 2 字前缀属于截断碎片，必须被剪掉
        assertFalse("萧宏" in names)
        assertFalse("赵樱" in names)
    }

    /** 一次出现的"人名+动作字"（郑吒嗤）必须被频次剔除，保住真名郑吒 */
    @Test
    fun `一次性尾巴碎片被频次剔除`() {
        val paragraphs = listOf(
            "郑吒嗤笑道：“你想真正的活着吗？”",
            "郑吒说：“先别急，听听他们怎么说。”",
            "郑吒道：“走吧。”"
        )
        val names = OfflineCharacterDiscovery.candidates(paragraphs).map { it.name }
        assertEquals(listOf("郑吒"), names)
        assertFalse("郑吒嗤" in names)
    }

    /** 变更数口径：清理旧碎片与新增一样触发分镜重算，不能只看 added */
    @Test
    fun `新增与清理都计入变更数`() {
        assertEquals(0, OfflineCharacterDiscovery.DiscoveryResult(added = 0, pruned = 0).changed)
        assertEquals(2, OfflineCharacterDiscovery.DiscoveryResult(added = 1, pruned = 1).changed)
    }

    /** 姓字起头的高频动词（解释/回答）不是人名，cue 邻接也不得入选 */
    @Test
    fun `姓字起头的非人名词不落候选`() {
        val paragraphs = listOf(
            "他解释道：“这只是个意外。”",
            "她回答：“我明白了。”"
        )
        assertTrue(OfflineCharacterDiscovery.candidates(paragraphs).isEmpty())
    }
}
