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

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrateAll() {
        // Create earliest version of the database with manual migrations.
        // Versions 1-9 use fallbackToDestructiveMigrationFrom, so the earliest
        // testable version is 10 (start of manual migrations in DatabaseMigrations).
        helper.createDatabase(TEST_DB, 10).apply {
            close()
        }

        // Open latest version of the database. Room will validate the schema
        // once all migrations execute.
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB
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
