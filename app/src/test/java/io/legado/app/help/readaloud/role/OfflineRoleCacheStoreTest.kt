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
        // 近场 PER 增加"窗口内仅一个已登记角色"稳定闸后同输入结果不同 ⇒ v6 命名空间
        assertEquals(
            "offline-ner-v6|" + ReadAloudRolePreprocessor.VERSION,
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
}
