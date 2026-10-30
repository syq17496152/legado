package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
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
}
