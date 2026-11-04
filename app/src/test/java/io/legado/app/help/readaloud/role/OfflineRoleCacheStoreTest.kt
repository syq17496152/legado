package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归因缓存命名空间（AD-16 升版约束）：算法换主路径后旧缓存不得复用。
 */
class OfflineRoleCacheStoreTest {

    @Test
    fun `算法升版后缓存命名空间为 NER 版本`() {
        assertTrue(OfflineRoleCacheStore.mode().startsWith(OfflineRoleCacheStore.MODE_PREFIX))
        assertTrue(OfflineRoleCacheStore.MODE_PREFIX.contains("ner"))
        assertFalse(OfflineRoleCacheStore.MODE_PREFIX.contains("rule"))
        // 模型 cue 宽松分支（人名+动词紧邻）上线后同输入结果不同 ⇒ v7 命名空间
        assertEquals(
            "offline-ner-v7|" + ReadAloudRolePreprocessor.VERSION,
            OfflineRoleCacheStore.mode()
        )
    }

    @Test
    fun `正文变化时缓存键不同`() {
        val bookKey = "work:作者/书名"
        val first = listOf("第一段", "第二段")
        val second = listOf("第一段", "第三段")
        assertNotEquals(
            OfflineRoleCacheStore.key(bookKey, 3, first),
            OfflineRoleCacheStore.key(bookKey, 3, second)
        )
    }

    @Test
    fun `分镜序列化可无损往返`() {
        val segments = listOf(
            OfflineRoleSegment(
                paragraphIndex = 1, start = 2, end = 8, roleType = "character",
                characterName = "张三", characterId = 7L, confidence = 0.9, reason = "ner-cue"
            )
        )
        assertEquals(segments, OfflineRoleCacheStore.parse(OfflineRoleCacheStore.serialize(segments)))
    }

    /** 3.1/3.2：剧本层把归因结果收敛成段级剧本，来源可诊断、拒不改写高置信规则 */
    @Test
    fun `剧本草稿保留来源并把轮转段标记为需仲裁`() {
        val draft = ChapterScriptBuilder.build(
            bookKey = "b", chapterIndex = 1, paragraphs = listOf("甲说：走吧。"),
            segments = listOf(
                OfflineRoleSegment(
                    paragraphIndex = 0, start = 0, end = 1, roleType = "character",
                    characterName = "张三", characterId = 7L, confidence = 0.95, reason = "alias"
                ),
                OfflineRoleSegment(
                    paragraphIndex = 0, start = 2, end = 5, roleType = "other",
                    characterName = "", characterId = 0L, confidence = 0.60, reason = "turn-taking"
                )
            ),
            ruleVersion = "r1"
        )
        assertEquals(2, draft.segments.size)
        assertEquals("alias", draft.segments[0].source)
        assertEquals("dialogue", draft.segments[0].roleType)
        assertFalse(draft.segments[0].needsArbitration)
        // other → dialogue，turn 段必须送仲裁（即使置信 0.60 已过最低门槛）
        assertEquals("turn", draft.segments[1].source)
        assertEquals("dialogue", draft.segments[1].roleType)
        assertTrue(draft.segments[1].needsArbitration)
        // 章节指纹可复算且与段指纹同源
        assertEquals(
            ChapterScriptBuilder.hashOf(listOf("甲说：走吧。")),
            draft.contentHash
        )
    }
}
