package io.legado.app.help.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * 2026-11-01 真机缺陷回归：用「系统文件夹选择器」导出备份**必失败**。
 *
 * 根因：`Backup.copyBackup` 以**空串 MIME** 调 `DocumentFile.createFile("", fileName)`，
 * Android 10+ 的 `DocumentsContract` 直接抛 `IllegalArgumentException: mimeType can't be null`
 * （真机日志定位到 `Backup.copyBackup`），界面回执「创建文件失败」。
 *
 * 修复：MIME 由扩展名派生（`Backup.safMimeTypeFor`），本用例锁住派生规则，
 * 防「再次退回空串」导致 SAF 备份不可用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupSafMimeTest {

    @Before
    fun setUp() {
        // Backup 的 object 初始化会读取 appCtx.externalFiles ⇒ 需注入 Context
        ApplicationProvider.getApplicationContext<Application>().injectAsAppCtx()
    }

    @Test
    fun `zip 备份包派生 application zip`() {
        assertEquals("application/zip", Backup.safMimeTypeFor("backup.zip"))
        assertEquals("application/zip", Backup.safMimeTypeFor("backup2026-11-01-151000.zip"))
        assertEquals("application/zip", Backup.safMimeTypeFor("BACKUP.ZIP"))
    }

    @Test
    fun `非 zip 回退八位字节流而不是空串`() {
        assertEquals("application/octet-stream", Backup.safMimeTypeFor("legado.db"))
        assertEquals("application/octet-stream", Backup.safMimeTypeFor("backup"))
        assertEquals("application/octet-stream", Backup.safMimeTypeFor(""))
        // 空串 MIME 正是真机崩溃点，必须永不产出
        assertNotEquals("", Backup.safMimeTypeFor(""))
    }
}
