package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 2026-11-01 真机缺陷回归：选中可写目录后点「备份」被反复拉起选择器。
 *
 * 根因：`DocumentFile.checkWrite()` 的可写探针用 `FileUtils.getMimeType(文件名)`，
 * 而探针文件名无扩展名 ⇒ 返回通配 MIME（星号-斜杠-星号）；部分 DocumentsProvider 拒绝通配 MIME ⇒ 探针抛异常
 * ⇒ 可写目录被误判为不可写 ⇒ `BackupConfigFragment.backup()` 每次改走「重新选择目录」分支。
 *
 * 修复：探针固定具体 MIME `text/plain`（它写的就是纯文本）。本用例锁住「永不通配/永不为空」。
 */
class FileDocWriteProbeMimeTest {

    @Test
    fun `可写探针使用具体 MIME`() {
        assertEquals("text/plain", writeProbeMimeType())
    }

    @Test
    fun `可写探针不得退回通配或空串`() {
        assertNotEquals("*/*", writeProbeMimeType())
        assertNotEquals("", writeProbeMimeType())
    }
}
