package io.legado.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.legado.app.data.dao.AiAgentDao
import io.legado.app.data.dao.AiGeneratedImageDao
import io.legado.app.data.dao.AiImageGroupDao
import io.legado.app.data.dao.AiMemoryDao
import io.legado.app.data.dao.AiReadAloudRoleCacheDao
import io.legado.app.data.dao.AiReadAloudUsageRecordDao
import io.legado.app.data.dao.AutoTaskRuleDao
import io.legado.app.data.dao.BookAiChapterSummaryDao
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookCharacterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookHighlightDao
import io.legado.app.data.dao.BookGroupDao
import io.legado.app.data.dao.BookSourceDao
import io.legado.app.data.dao.BookmarkDao
import io.legado.app.data.dao.CacheDao
import io.legado.app.data.dao.CookieDao
import io.legado.app.data.dao.CoverGalleryDao
import io.legado.app.data.dao.DictRuleDao
import io.legado.app.data.dao.HttpTTSDao
import io.legado.app.data.dao.KeyboardAssistsDao
import io.legado.app.data.dao.PlayHistoryDao
import io.legado.app.data.dao.SourceRecycleBinDao
import io.legado.app.data.dao.ParagraphRuleDao
import io.legado.app.data.dao.ReadMenuCustomButtonDao
import io.legado.app.data.dao.ReadAloudBgmDao
import io.legado.app.data.dao.ReadAloudSpeakerGroupDao
import io.legado.app.data.dao.ReadRecentBookDao
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.dao.ReadRecordDailyDao
import io.legado.app.data.dao.ReplaceRuleDao
import io.legado.app.data.dao.RssArticleDao
import io.legado.app.data.dao.RssReadRecordDao
import io.legado.app.data.dao.RssSourceDao
import io.legado.app.data.dao.RssStarDao
import io.legado.app.data.dao.RuleSubDao
import io.legado.app.data.dao.SearchBookDao
import io.legado.app.data.dao.SearchKeywordDao
import io.legado.app.data.dao.SourceGroupCoverDao
import io.legado.app.data.dao.DownloadTaskDao
import io.legado.app.data.dao.ServerDao
import io.legado.app.data.dao.TxtTocRuleDao
import io.legado.app.data.dao.UrlRecordDao
import io.legado.app.data.entities.AiAgentJob
import io.legado.app.data.entities.AiAgentSession
import io.legado.app.data.entities.AiAgentTrace
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.data.entities.AiImageGroup
import io.legado.app.data.entities.AiMemoryFragment
import io.legado.app.data.entities.AiMemoryFragmentFts
import io.legado.app.data.entities.AiMemoryItem
import io.legado.app.data.entities.AiMemoryItemFts
import io.legado.app.data.entities.AiReadAloudRoleCache
import io.legado.app.data.entities.AiReadAloudUsageRecord
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookAiChapterSummary
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.BookCharacterRelation
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookParagraphRule
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.Cookie
import io.legado.app.data.entities.CoverGalleryGroup
import io.legado.app.data.entities.CoverGalleryImage
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.DownloadTaskEntity
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ParagraphRuleVar
import io.legado.app.data.entities.PlayHistory
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.data.entities.ReadAloudBgmAssignmentCache
import io.legado.app.data.entities.ReadAloudBgmGroup
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.data.entities.ReadAloudSpeakerGroup
import io.legado.app.data.entities.ReadAloudSpeakerGroupItem
import io.legado.app.data.entities.ReadRecentBook
import io.legado.app.data.entities.SourceGroupCover
import io.legado.app.data.entities.SourceRecycleBin
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReadRecordDetail
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.UrlRecord
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DefaultData
import io.legado.app.model.AutoTaskRule
import org.intellij.lang.annotations.Language
import splitties.init.appCtx
import java.util.Locale

val appDb by lazy {
    Room.databaseBuilder(appCtx, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
        .fallbackToDestructiveMigrationFrom(false, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .addMigrations(*DatabaseMigrations.migrations)
        .allowMainThreadQueries()
        .addCallback(AppDatabase.dbCallback)
        .build()
}

@Database(
    version = 109,
    exportSchema = true,
    entities = [Book::class, BookGroup::class, BookSource::class, BookChapter::class,
        ReplaceRule::class, SearchBook::class, SearchKeyword::class, Cookie::class,
        RssSource::class, Bookmark::class, RssArticle::class, RssReadRecord::class,
        RssStar::class, TxtTocRule::class, ReadRecord::class, HttpTTS::class, Cache::class,
        RuleSub::class, DictRule::class, KeyboardAssist::class, Server::class,
        CoverGalleryGroup::class, CoverGalleryImage::class, ReadRecordDetail::class,
        AutoTaskRule::class, BookHighlight::class, PlayHistory::class, SourceRecycleBin::class,
        UrlRecord::class, SourceGroupCover::class, ReadRecordDaily::class, ReadRecentBook::class,
        ParagraphRule::class, BookParagraphRule::class, ParagraphRuleVar::class,
        ReadMenuCustomButton::class,
        AiImageGroup::class, AiGeneratedImage::class,
        BookCharacter::class, BookCharacterRelation::class,
        BookAiChapterSummary::class, AiReadAloudRoleCache::class,
        ReadAloudBgmGroup::class, ReadAloudBgmTrack::class, ReadAloudBgmAssignmentCache::class,
        ReadAloudSpeakerGroup::class, ReadAloudSpeakerGroupItem::class,
        AiReadAloudUsageRecord::class,
        AiAgentSession::class, AiAgentJob::class, AiAgentTrace::class,
        AiMemoryItem::class, AiMemoryFragment::class, AiMemoryItemFts::class, AiMemoryFragmentFts::class,
        DownloadTaskEntity::class],
    views = [BookSourcePart::class],
    autoMigrations = [
        AutoMigration(from = 43, to = 44),
        AutoMigration(from = 44, to = 45),
        AutoMigration(from = 45, to = 46),
        AutoMigration(from = 46, to = 47),
        AutoMigration(from = 47, to = 48),
        AutoMigration(from = 48, to = 49),
        AutoMigration(from = 49, to = 50),
        AutoMigration(from = 50, to = 51),
        AutoMigration(from = 51, to = 52),
        AutoMigration(from = 52, to = 53),
        AutoMigration(from = 53, to = 54),
        AutoMigration(from = 54, to = 55, spec = DatabaseMigrations.Migration_54_55::class),
        AutoMigration(from = 55, to = 56),
        AutoMigration(from = 56, to = 57),
        AutoMigration(from = 57, to = 58),
        AutoMigration(from = 58, to = 59),
        AutoMigration(from = 59, to = 60),
        AutoMigration(from = 60, to = 61),
        AutoMigration(from = 61, to = 62),
        AutoMigration(from = 62, to = 63),
        AutoMigration(from = 63, to = 64),
        AutoMigration(from = 64, to = 65, spec = DatabaseMigrations.Migration_64_65::class),
        AutoMigration(from = 65, to = 66),
        AutoMigration(from = 66, to = 67),
        AutoMigration(from = 67, to = 68),
        AutoMigration(from = 68, to = 69),
        AutoMigration(from = 69, to = 70),
        AutoMigration(from = 70, to = 71),
        AutoMigration(from = 71, to = 72),
        AutoMigration(from = 72, to = 73),
        AutoMigration(from = 73, to = 74),
        AutoMigration(from = 74, to = 75),
        AutoMigration(from = 75, to = 76),
        AutoMigration(from = 76, to = 77),
        AutoMigration(from = 77, to = 78),
        AutoMigration(from = 78, to = 79),
        AutoMigration(from = 79, to = 80),
        AutoMigration(from = 80, to = 81, spec = DatabaseMigrations.Migration_80_81::class),
        AutoMigration(from = 81, to = 82),
        AutoMigration(from = 82, to = 83),
        AutoMigration(from = 83, to = 84, spec = DatabaseMigrations.Migration_83_84::class),
        AutoMigration(from = 84, to = 85, spec = DatabaseMigrations.Migration_84_85::class),
        AutoMigration(from = 85, to = 86),
        AutoMigration(from = 86, to = 87),
        AutoMigration(from = 87, to = 88),
        AutoMigration(from = 88, to = 89)
        // F-P0-2: 89→90 使用手动 Migration（DatabaseMigrations.migration_89_90），新增 3 张表
        // F-P1-1: 90→91 使用手动 Migration（DatabaseMigrations.migration_90_91），新增 auto_task_rules 表
        // F-P1-2: 91→92 使用手动 Migration（DatabaseMigrations.migration_91_92），新增 highlights 表
        // rss-cache-first: 92→93 使用手动 Migration（DatabaseMigrations.migration_92_93），重建 rssSources 表将 cacheFirst 默认值 0→1
        // rss-concurrency: 93→94 使用手动 Migration（DatabaseMigrations.migration_93_94）
        // rss-weight: 94→95 使用手动 Migration（DatabaseMigrations.migration_94_95），rssSources 表新增 parseConcurrency + weight 字段
        // rss-unified-search: 98→99 使用手动 Migration（DatabaseMigrations.migration_98_99），search_keywords 表改为复合主键(word, type)
        // precise-manage: 102→103 使用手动 Migration（DatabaseMigrations.migration_102_103），新增 url_records 表（网址记录）
        // source-folder-cover: 103→104 使用手动 Migration（DatabaseMigrations.migration_103_104），新增 source_group_covers 表（发现/订阅源分组封面）
        // archive-ui P1-B: 104→105 使用手动 Migration（DatabaseMigrations.migration_104_105），新增 6 张表（readRecordDaily/readRecentBooks/paragraph_rules/book_paragraph_rules/paragraph_rule_vars/read_menu_custom_buttons）
        // archive-ui P1-F: 105→106 使用手动 Migration（DatabaseMigrations.migration_105_106），新增 AI agent/images/memory/read-aloud bgm/speaker/book character/chapter summary 等共 19 张表（含 2 张 FTS4 虚拟表）
        // download-manager: 106→107 使用手动 Migration（DatabaseMigrations.migration_106_107），新增 download_tasks 表（下载任务持久化）
        // download-manager-optimize B8: 107→108 使用手动 Migration（migration_107_108），download_tasks 删除 errorMsg/resumePointJson/segmentsJson 僵尸列（建新表迁数据）
        // video-sniff-403-and-rss-classic-fix 4.8e: 108→109 使用手动 Migration（migration_108_109），playHistories 主键扩为 (articleUrl, videoUrl, rssSourceId)（建新表迁数据）
    ]
)
abstract class AppDatabase : RoomDatabase() {

    abstract val bookDao: BookDao
    abstract val bookGroupDao: BookGroupDao
    abstract val bookSourceDao: BookSourceDao
    abstract val bookChapterDao: BookChapterDao
    abstract val replaceRuleDao: ReplaceRuleDao
    abstract val searchBookDao: SearchBookDao
    abstract val searchKeywordDao: SearchKeywordDao
    abstract val rssSourceDao: RssSourceDao
    abstract val bookmarkDao: BookmarkDao
    abstract val rssArticleDao: RssArticleDao
    abstract val rssStarDao: RssStarDao
    abstract val rssReadRecordDao: RssReadRecordDao
    abstract val cookieDao: CookieDao
    abstract val txtTocRuleDao: TxtTocRuleDao
    abstract val readRecordDao: ReadRecordDao
    abstract val httpTTSDao: HttpTTSDao
    abstract val cacheDao: CacheDao
    abstract val ruleSubDao: RuleSubDao
    abstract val dictRuleDao: DictRuleDao
    abstract val keyboardAssistsDao: KeyboardAssistsDao
    abstract val serverDao: ServerDao
    abstract val coverGalleryDao: CoverGalleryDao
    abstract val autoTaskRuleDao: AutoTaskRuleDao
    abstract val bookHighlightDao: BookHighlightDao
    abstract val playHistoryDao: PlayHistoryDao
    abstract val sourceRecycleBinDao: SourceRecycleBinDao
    abstract val urlRecordDao: UrlRecordDao
    abstract val sourceGroupCoverDao: SourceGroupCoverDao
    abstract val readRecordDailyDao: ReadRecordDailyDao
    abstract val readRecentBookDao: ReadRecentBookDao
    abstract val paragraphRuleDao: ParagraphRuleDao
    abstract val readMenuCustomButtonDao: ReadMenuCustomButtonDao
    abstract val bookCharacterDao: BookCharacterDao
    abstract val aiImageGroupDao: AiImageGroupDao
    abstract val aiGeneratedImageDao: AiGeneratedImageDao
    abstract val bookAiChapterSummaryDao: BookAiChapterSummaryDao
    abstract val aiReadAloudRoleCacheDao: AiReadAloudRoleCacheDao
    abstract val readAloudBgmDao: ReadAloudBgmDao
    abstract val readAloudSpeakerGroupDao: ReadAloudSpeakerGroupDao
    abstract val aiReadAloudUsageRecordDao: AiReadAloudUsageRecordDao
    abstract val aiAgentDao: AiAgentDao
    abstract val aiMemoryDao: AiMemoryDao
    abstract val downloadTaskDao: DownloadTaskDao

    companion object {

        const val DATABASE_NAME = "legado.db"

        const val BOOK_TABLE_NAME = "books"
        const val BOOK_SOURCE_TABLE_NAME = "book_sources"
        const val RSS_SOURCE_TABLE_NAME = "rssSources"

        val dbCallback = object : Callback() {

            override fun onCreate(db: SupportSQLiteDatabase) {
                try {
                    db.setLocale(Locale.CHINESE)
                    Log.d("AppDatabaseCallback", "成功 设置 locale.")
                } catch (e: Exception) {
                    Log.e("AppDatabaseCallback", "错误 设置 locale in onCreate", e)
                }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                @Language("sql")
                val insertBookGroupAllSql = """
                    insert into book_groups(groupId, groupName, 'order', show) 
                    select ${BookGroup.IdAll}, '全部', -10, 1
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdAll})
                """.trimIndent()
                db.execSQL(insertBookGroupAllSql)
                @Language("sql")
                val insertBookGroupLocalSql = """
                    insert into book_groups(groupId, groupName, 'order', enableRefresh, show) 
                    select ${BookGroup.IdLocal}, '本地', -9, 0, 1
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdLocal})
                """.trimIndent()
                db.execSQL(insertBookGroupLocalSql)
                @Language("sql")
                val insertBookGroupMusicSql = """
                    insert into book_groups(groupId, groupName, 'order', show) 
                    select ${BookGroup.IdAudio}, '音频', -8, 1
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdAudio})
                """.trimIndent()
                db.execSQL(insertBookGroupMusicSql)
                @Language("sql")
                val insertBookGroupNetNoneGroupSql = """
                    insert into book_groups(groupId, groupName, 'order', show) 
                    select ${BookGroup.IdNetNone}, '网络未分组', -7, 1
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdNetNone})
                """.trimIndent()
                db.execSQL(insertBookGroupNetNoneGroupSql)
                @Language("sql")
                val insertBookGroupLocalNoneGroupSql = """
                    insert into book_groups(groupId, groupName, 'order', show) 
                    select ${BookGroup.IdLocalNone}, '本地未分组', -6, 0
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdLocalNone})
                """.trimIndent()
                db.execSQL(insertBookGroupLocalNoneGroupSql)
                @Language("sql")
                val insertBookGroupVideoSql = """
                    insert into book_groups(groupId, groupName, 'order', show) 
                    select ${BookGroup.IdVideo}, '视频', -5, 1
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdVideo})
                    """.trimIndent()
                db.execSQL(insertBookGroupVideoSql)
                @Language("sql")
                val insertBookGroupErrorSql = """
                    insert into book_groups(groupId, groupName, 'order', show) 
                    select ${BookGroup.IdError}, '更新失败', -1, 1
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdError})
                """.trimIndent()
                db.execSQL(insertBookGroupErrorSql)
                @Language("sql")
                val upBookSourceLoginUiSql =
                    "update book_sources set loginUi = null where loginUi = 'null'"
                db.execSQL(upBookSourceLoginUiSql)
                @Language("sql")
                val upRssSourceLoginUiSql =
                    "update rssSources set loginUi = null where loginUi = 'null'"
                db.execSQL(upRssSourceLoginUiSql)
                @Language("sql")
                val upHttpTtsLoginUiSql =
                    "update httpTTS set loginUi = null where loginUi = 'null'"
                db.execSQL(upHttpTtsLoginUiSql)
                @Language("sql")
                val upHttpTtsConcurrentRateSql =
                    "update httpTTS set concurrentRate = '0' where concurrentRate is null"
                db.execSQL(upHttpTtsConcurrentRateSql)
                db.query("select * from keyboardAssists order by serialNo").use {
                    if (it.count == 0) {
                        DefaultData.keyboardAssists.forEach { keyboardAssist ->
                            val contentValues = ContentValues().apply {
                                put("type", keyboardAssist.type)
                                put("key", keyboardAssist.key)
                                put("value", keyboardAssist.value)
                                put("serialNo", keyboardAssist.serialNo)
                            }
                            db.insert(
                                "keyboardAssists",
                                SQLiteDatabase.CONFLICT_REPLACE,
                                contentValues
                            )
                        }
                    }
                }
            }
        }

    }

}