package io.legado.app

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    private val ALL_MIGRATIONS = DatabaseMigrations.migrations

    /**
     * 本 App 真实发布过的 DB 版本（由 `git log --follow -- AppDatabase.kt` 抽取 `version = N` 得到）。
     *
     * ⚠️ 不可用「有迁移的最早版本」当起点：本仓迁移边只覆盖 10→43 与 89→110 两段
     * （43~88 为上游 fork 遗留空档），`fallbackToDestructiveMigrationFrom` 仅兜底 1~9
     * ⇒ 从 10 起跑找不到到 110 的路径（Room 抛 "A migration from 10 to 110 was required
     * but not found"），旧版 `migrateAll(10)` 属**测试资产失实**（真跑必失败）。
     * 详见 `docs/project-rules/database-migration-safety.md` R7。
     */
    private val SHIPPED_VERSIONS = listOf(
        89, 92, 93, 94, 98, 99, 100, 101, 103, 104, 106, 107, 108, 109
    )

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrateFromEarliestShippedVersion() {
        // 起点取本 App 真实发布过的最低版本；Room 在迁移后做 schema 校验，不匹配即抛异常。
        openFromVersion(89, TEST_DB)
    }

    /**
     * 覆盖安装矩阵：逐个「历史发布版本」起跑 → 打开当前版本库 → Room 校验通过。
     * 任一版本找不到迁移路径（IllegalStateException）或 schema 校验失败都会使本用例失败。
     */
    @Test
    @Throws(IOException::class)
    fun migrateFromEveryShippedVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SHIPPED_VERSIONS.forEach { from ->
            val dbName = "$TEST_DB-$from"
            context.deleteDatabase(dbName)
            openFromVersion(from, dbName)
            context.deleteDatabase(dbName)
        }
    }

    private fun openFromVersion(from: Int, dbName: String) {
        helper.createDatabase(dbName, from).apply {
            close()
        }
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            dbName
        ).addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                openHelper.writableDatabase
                close()
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate101To102() {
        // B7: source_recycle_bin table added in 101 -> 102.
        helper.createDatabase(TEST_DB, 101).apply {
            close()
        }
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(*ALL_MIGRATIONS)
            .build().apply {
                // Verify the new table is present and usable after migration.
                val db = openHelper.writableDatabase
                val exists = db.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='source_recycle_bin'"
                ).use { cursor ->
                    cursor.moveToFirst() && cursor.count > 0
                }
                org.junit.Assert.assertTrue("source_recycle_bin table missing after 101->102", exists)
                close()
            }
    }
}
