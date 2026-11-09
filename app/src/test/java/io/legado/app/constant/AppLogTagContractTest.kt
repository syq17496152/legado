package io.legado.app.constant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块 Tag 常量的两条硬约束（`logging_rules` 模块 Tag 规范）。
 *
 * 1. **不得重名**：两个模块共用同一个 tag 时，`logcat -s <tag>` 会把两条链路混在一起，
 *    排查时无法区分来源（历史上裸字面量 tag 就踩过）。
 * 2. **端侧 LLM 的 tag 值被外部脚本消费**：`ai_tests/scripts/l3_local_ai_generate.py` 按
 *    `LlmTrace` 采集推理链日志，改值必须同步脚本，这里锁死。
 */
class AppLogTagContractTest {

    private val tags = listOf(
        AppLog.TAG_WEB_BOOK, AppLog.TAG_ANALYZE, AppLog.TAG_HTTP, AppLog.TAG_WEB_VIEW,
        AppLog.TAG_DATA, AppLog.TAG_RSS, AppLog.TAG_CONTENT, AppLog.TAG_SOURCE_MECHANISM,
        AppLog.TAG_IMAGE_CANVAS, AppLog.TAG_IMAGE_DETAIL, AppLog.TAG_IMAGE_PLAY,
        AppLog.TAG_IMAGE_SNIFF, AppLog.TAG_CRYPTO_SCOPE, AppLog.TAG_DECOMPRESS,
        AppLog.TAG_NETWORK_LOG, AppLog.TAG_SEARCH_STORAGE, AppLog.TAG_BOOK_ORIGIN_MIGRATE,
        AppLog.TAG_SOURCE_RECYCLE_BIN, AppLog.TAG_SPECIAL_CONTENT, AppLog.TAG_SHELF_PROGRESS,
        AppLog.TAG_MEMORY_PRESSURE, AppLog.TAG_CACHE_STATS, AppLog.TAG_CACHE_CONCURRENT,
        AppLog.TAG_WEBDAV_BACKUP, AppLog.TAG_HIGHLIGHT_STYLE, AppLog.TAG_THOUGHT_EXPORT,
        AppLog.TAG_SOURCE_SANDBOX, AppLog.TAG_SOURCE_DIALOG, AppLog.TAG_SOURCE_CACHE,
        AppLog.TAG_SOURCE_GUARD, AppLog.TAG_TTS_TRACE, AppLog.TAG_LLM_TRACE,
        AppLog.TAG_IMG_DECRYPT, AppLog.TAG_HLS_REMUX, AppLog.TAG_RSS_SOURCE_EDIT,
        AppLog.TAG_READER_TEMPLATE, AppLog.TAG_CODE_EDIT, AppLog.TAG_CRASH_REPORT,
        AppLog.TAG_DEVICE_INFO
    )

    @Test
    fun `模块 Tag 全局唯一`() {
        val dup = tags.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("存在重复的模块 Tag（会让 logcat 采集串台）：$dup", dup.isEmpty())
    }

    @Test
    fun `LLM 链路 Tag 值与 L3 脚本契约一致`() {
        assertEquals("LlmTrace", AppLog.TAG_LLM_TRACE)
        assertTrue("Tag 不能为空", tags.none { it.isBlank() })
    }

    @Test
    fun `播放器浮层提示事件与错误事件分离`() {
        assertEquals("videoPlayTip", EventBus.VIDEO_PLAY_TIP)
        assertTrue(EventBus.VIDEO_PLAY_TIP != EventBus.VIDEO_PLAY_ERROR)
    }
}
