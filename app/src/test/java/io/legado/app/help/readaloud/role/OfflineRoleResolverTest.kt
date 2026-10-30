package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线归因（AD-13 / AD-15 / AD-17 / AD-21 / T5.3）：cue 命中、不猜、对话锁、区间映射。
 */
class OfflineRoleResolverTest {

    private val characters = listOf(
        OfflineRoleResolver.RoleCharacter(id = 1L, name = "张三", gender = "male", roleLevel = 2),
        OfflineRoleResolver.RoleCharacter(id = 2L, name = "李四", gender = "female", roleLevel = 1)
    )

    private fun unit(
        id: String,
        paragraphIndex: Int,
        start: Int,
        end: Int,
        cueBefore: String = "",
        roleType: String = "character",
        kind: String = "dialogue"
    ) = ReadAloudRoleUnit(
        id = id,
        kind = kind,
        roleType = roleType,
        characterName = "",
        ranges = listOf(ReadAloudRoleRange(paragraphIndex, start, end)),
        text = "",
        cueBefore = cueBefore,
        confidence = 0.46
    )

    @Test
    fun `cue 命中角色`() {
        val paragraphs = listOf("张三说：“我们走吧。”")
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 4, 10, cueBefore = "张三说：")),
            characters
        )
        assertEquals(1, result.segments.size)
        assertEquals("张三", result.segments[0].characterName)
        assertEquals(1L, result.segments[0].characterId)
        assertEquals("cue-hit", result.segments[0].reason)
    }

    @Test
    fun `张山不等于张三 未识别不猜`() {
        val paragraphs = listOf("张山说：“好。”")
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 4, 8, cueBefore = "张山说：")),
            characters
        )
        assertEquals(1, result.segments.size)
        assertTrue("不应张冠李戴", result.segments[0].characterName.isBlank())
        assertEquals("unknown", result.segments[0].reason)
        assertEquals(0.0, result.segments[0].confidence, 0.001)
    }

    @Test
    fun `同段连续对白对话锁继承`() {
        val paragraphs = listOf("张三说：“一。”他顿了顿，又说：“二。”")
        val units = listOf(
            unit("u1", 0, 4, 5, cueBefore = "张三说："),
            unit("u2", 0, 16, 18, cueBefore = "又说：")
        )
        val result = OfflineRoleResolver.resolve(paragraphs, units, characters)
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("张三", result.segments[1].characterName)
        // 锁内也可能由 NER 同段人名命中，二者都是"同一角色"的合法证据
        assertTrue(result.segments[1].reason in setOf("lock", "ner"))
    }

    @Test
    fun `轮转在已见角色间切换`() {
        val paragraphs = listOf(
            "张三说：“一。”",
            "李四说：“二。”",
            "她说：“三。”"
        )
        val units = listOf(
            unit("u1", 0, 4, 5, cueBefore = "张三说："),
            unit("u2", 1, 4, 5, cueBefore = "李四说："),
            unit("u3", 2, 3, 5, cueBefore = "")
        )
        val result = OfflineRoleResolver.resolve(paragraphs, units, characters)
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("李四", result.segments[1].characterName)
        // 第三句无 cue，代词"她"指女性，上一句是李四（女）→ 只能落未识别（不猜同一人）
        assertEquals("unknown", result.segments[2].reason)
    }

    @Test
    fun `段与角色按区间交集映射`() {
        val segments = listOf(
            OfflineRoleSegment(paragraphIndex = 0, start = 10, end = 30, roleType = "character",
                characterName = "张三", characterId = 1L, confidence = 0.9, reason = "cue-hit")
        )
        val hit = OfflineRoleResolver.lookup(segments, 0, 12, 8)
        assertNotNull(hit)
        assertEquals("张三", hit!!.characterName)

        assertNull(OfflineRoleResolver.lookup(segments, 0, 0, 8))
    }

    @Test
    fun `旁白段不参与角色匹配`() {
        val paragraphs = listOf("风很大，夜里没有月亮。")
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 0, 11, roleType = "narrator", kind = "narrator")),
            characters
        )
        assertEquals(1, result.narration)
        assertEquals("narrator", result.segments[0].roleType)
        assertTrue(result.segments[0].characterName.isBlank())
    }
}
