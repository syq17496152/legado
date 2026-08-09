package io.legado.app.data

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType

object DatabaseMigrations {

    val migrations: Array<Migration> by lazy {
        arrayOf(
            migration_10_11, migration_11_12, migration_12_13, migration_13_14,
            migration_14_15, migration_15_17, migration_17_18, migration_18_19,
            migration_19_20, migration_20_21, migration_21_22, migration_22_23,
            migration_23_24, migration_24_25, migration_25_26, migration_26_27,
            migration_27_28, migration_28_29, migration_29_30, migration_30_31,
            migration_31_32, migration_32_33, migration_33_34, migration_34_35,
            migration_35_36, migration_36_37, migration_37_38, migration_38_39,
            migration_39_40, migration_40_41, migration_41_42, migration_42_43,
            migration_89_90, migration_90_91, migration_91_92, migration_92_93,
            migration_93_94, migration_94_95, migration_95_96, migration_96_97,
            migration_97_98, migration_98_99, migration_99_100, migration_100_101,
            migration_101_102, migration_102_103
        )
    }

    private val migration_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE txtTocRules")
            db.execSQL(
                """CREATE TABLE txtTocRules(id INTEGER NOT NULL, 
                    name TEXT NOT NULL, rule TEXT NOT NULL, serialNumber INTEGER NOT NULL, 
                    enable INTEGER NOT NULL, PRIMARY KEY (id))"""
            )
        }
    }

    private val migration_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssSources ADD style TEXT ")
        }
    }

    private val migration_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssSources ADD articleStyle INTEGER NOT NULL DEFAULT 0 ")
        }
    }

    private val migration_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `books_new` (`bookUrl` TEXT NOT NULL, `tocUrl` TEXT NOT NULL, `origin` TEXT NOT NULL,
                    `originName` TEXT NOT NULL, `name` TEXT NOT NULL, `author` TEXT NOT NULL, `kind` TEXT, `customTag` TEXT, `coverUrl` TEXT, 
                    `customCoverUrl` TEXT, `intro` TEXT, `customIntro` TEXT, `charset` TEXT, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, 
                    `latestChapterTitle` TEXT, `latestChapterTime` INTEGER NOT NULL, `lastCheckTime` INTEGER NOT NULL, `lastCheckCount` INTEGER NOT NULL, 
                    `totalChapterNum` INTEGER NOT NULL, `durChapterTitle` TEXT, `durChapterIndex` INTEGER NOT NULL, `durChapterPos` INTEGER NOT NULL, 
                    `durChapterTime` INTEGER NOT NULL, `wordCount` TEXT, `canUpdate` INTEGER NOT NULL, `order` INTEGER NOT NULL, 
                    `originOrder` INTEGER NOT NULL, `useReplaceRule` INTEGER NOT NULL, `variable` TEXT, PRIMARY KEY(`bookUrl`))"""
            )
            db.execSQL("INSERT INTO books_new select * from books ")
            db.execSQL("DROP TABLE books")
            db.execSQL("ALTER TABLE books_new RENAME TO books")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_name_author` ON `books` (`name`, `author`) ")
        }
    }

    private val migration_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE bookmarks ADD bookAuthor TEXT NOT NULL DEFAULT ''")
        }
    }

    private val migration_15_17 = object : Migration(15, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `readRecord` (`bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, PRIMARY KEY(`bookName`))")
        }
    }

    private val migration_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `httpTTS` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
    }

    private val migration_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `readRecordNew` (`androidId` TEXT NOT NULL, `bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, 
                    PRIMARY KEY(`androidId`, `bookName`))"""
            )
            db.execSQL("INSERT INTO readRecordNew(androidId, bookName, readTime) select '${AppConst.androidId}' as androidId, bookName, readTime from readRecord")
            db.execSQL("DROP TABLE readRecord")
            db.execSQL("ALTER TABLE readRecordNew RENAME TO readRecord")
        }
    }
    private val migration_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE book_sources ADD bookSourceComment TEXT")
        }
    }

    private val migration_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE book_groups ADD show INTEGER NOT NULL DEFAULT 1")
        }
    }

    private val migration_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `books_new` (`bookUrl` TEXT NOT NULL, `tocUrl` TEXT NOT NULL, `origin` TEXT NOT NULL, 
                    `originName` TEXT NOT NULL, `name` TEXT NOT NULL, `author` TEXT NOT NULL, `kind` TEXT, `customTag` TEXT, 
                    `coverUrl` TEXT, `customCoverUrl` TEXT, `intro` TEXT, `customIntro` TEXT, `charset` TEXT, `type` INTEGER NOT NULL, 
                    `group` INTEGER NOT NULL, `latestChapterTitle` TEXT, `latestChapterTime` INTEGER NOT NULL, `lastCheckTime` INTEGER NOT NULL, 
                    `lastCheckCount` INTEGER NOT NULL, `totalChapterNum` INTEGER NOT NULL, `durChapterTitle` TEXT, `durChapterIndex` INTEGER NOT NULL, 
                    `durChapterPos` INTEGER NOT NULL, `durChapterTime` INTEGER NOT NULL, `wordCount` TEXT, `canUpdate` INTEGER NOT NULL, 
                    `order` INTEGER NOT NULL, `originOrder` INTEGER NOT NULL, `variable` TEXT, `readConfig` TEXT, PRIMARY KEY(`bookUrl`))"""
            )
            db.execSQL(
                """INSERT INTO books_new select `bookUrl`, `tocUrl`, `origin`, `originName`, `name`, `author`, `kind`, `customTag`, `coverUrl`, 
                    `customCoverUrl`, `intro`, `customIntro`, `charset`, `type`, `group`, `latestChapterTitle`, `latestChapterTime`, `lastCheckTime`, 
                    `lastCheckCount`, `totalChapterNum`, `durChapterTitle`, `durChapterIndex`, `durChapterPos`, `durChapterTime`, `wordCount`, `canUpdate`, 
                    `order`, `originOrder`, `variable`, null
                    from books"""
            )
            db.execSQL("DROP TABLE books")
            db.execSQL("ALTER TABLE books_new RENAME TO books")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_name_author` ON `books` (`name`, `author`) ")
        }
    }

    private val migration_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chapters ADD baseUrl TEXT NOT NULL DEFAULT ''")
        }
    }

    private val migration_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `caches` (`key` TEXT NOT NULL, `value` TEXT, `deadline` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_caches_key` ON `caches` (`key`)")
        }
    }

    private val migration_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `sourceSubs` 
                    (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `type` INTEGER NOT NULL, `customOrder` INTEGER NOT NULL, 
                    PRIMARY KEY(`id`))"""
            )
        }
    }

    private val migration_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `ruleSubs` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `type` INTEGER NOT NULL, 
                    `customOrder` INTEGER NOT NULL, `autoUpdate` INTEGER NOT NULL, `update` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
            )
            db.execSQL(" insert into `ruleSubs` select *, 0, 0 from `sourceSubs` ")
            db.execSQL("DROP TABLE `sourceSubs`")
        }
    }

    private val migration_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(" ALTER TABLE rssSources ADD singleUrl INTEGER NOT NULL DEFAULT 0 ")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `bookmarks1` (`time` INTEGER NOT NULL, `bookUrl` TEXT NOT NULL, `bookName` TEXT NOT NULL, 
                        `bookAuthor` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `chapterPos` INTEGER NOT NULL, `chapterName` TEXT NOT NULL, 
                        `bookText` TEXT NOT NULL, `content` TEXT NOT NULL, PRIMARY KEY(`time`))"""
            )
            db.execSQL(
                """insert into `bookmarks1` 
                        select `time`, `bookUrl`, `bookName`, `bookAuthor`, `chapterIndex`, `pageIndex`, `chapterName`, '', `content` 
                        from bookmarks"""
            )
            db.execSQL(" DROP TABLE `bookmarks` ")
            db.execSQL(" ALTER TABLE bookmarks1 RENAME TO bookmarks ")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_time` ON `bookmarks` (`time`)")
        }
    }

    private val migration_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssArticles ADD variable TEXT")
            db.execSQL("ALTER TABLE rssStars ADD variable TEXT")
        }
    }

    private val migration_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssSources ADD sourceComment TEXT")
        }
    }

    private val migration_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chapters ADD `startFragmentId` TEXT")
            db.execSQL("ALTER TABLE chapters ADD `endFragmentId` TEXT")
            db.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `epubChapters` 
                    (`bookUrl` TEXT NOT NULL, `href` TEXT NOT NULL, `parentHref` TEXT, 
                    PRIMARY KEY(`bookUrl`, `href`), FOREIGN KEY(`bookUrl`) REFERENCES `books`(`bookUrl`) ON UPDATE NO ACTION ON DELETE CASCADE )
                """
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_epubChapters_bookUrl` ON `epubChapters` (`bookUrl`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_epubChapters_bookUrl_href` ON `epubChapters` (`bookUrl`, `href`)")
        }
    }

    private val migration_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE readRecord RENAME TO readRecord1")
            db.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `readRecord` (`deviceId` TEXT NOT NULL, `bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `bookName`))
                """
            )
            db.execSQL("insert into readRecord (deviceId, bookName, readTime) select androidId, bookName, readTime from readRecord1")
        }
    }

    private val migration_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE `epubChapters`")
        }
    }

    private val migration_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE bookmarks RENAME TO bookmarks_old")
            db.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `bookmarks` (`time` INTEGER NOT NULL,
                    `bookName` TEXT NOT NULL, `bookAuthor` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, 
                    `chapterPos` INTEGER NOT NULL, `chapterName` TEXT NOT NULL, `bookText` TEXT NOT NULL, 
                    `content` TEXT NOT NULL, PRIMARY KEY(`time`))
                """
            )
            db.execSQL(
                """
                    CREATE INDEX IF NOT EXISTS `index_bookmarks_bookName_bookAuthor` ON `bookmarks` (`bookName`, `bookAuthor`)
                """
            )
            db.execSQL(
                """
                    insert into bookmarks (time, bookName, bookAuthor, chapterIndex, chapterPos, chapterName, bookText, content)
                    select time, ifNull(b.name, bookName) bookName, ifNull(b.author, bookAuthor) bookAuthor, 
                    chapterIndex, chapterPos, chapterName, bookText, content from bookmarks_old o
                    left join books b on o.bookUrl = b.bookUrl
                """
            )
        }
    }

    private val migration_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_groups` ADD `cover` TEXT")
        }
    }

    private val migration_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_sources` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_35_36 = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_sources` ADD `loginUi` TEXT")
            db.execSQL("ALTER TABLE `book_sources` ADD`loginCheckJs` TEXT")
        }
    }

    private val migration_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `rssSources` ADD `loginUrl` TEXT")
            db.execSQL("ALTER TABLE `rssSources` ADD `loginUi` TEXT")
            db.execSQL("ALTER TABLE `rssSources` ADD `loginCheckJs` TEXT")
        }
    }

    private val migration_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_sources` ADD `respondTime` INTEGER NOT NULL DEFAULT 180000")
        }
    }

    private val migration_38_39 = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `rssSources` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_39_40 = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chapters` ADD `isVip` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `chapters` ADD `isPay` INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val migration_40_41 = object : Migration(40, 41) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `httpTTS` ADD `loginUrl` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `loginUi` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `loginCheckJs` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `header` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_41_42 = object : Migration(41, 42) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE 'httpTTS' ADD `contentType` TEXT")
        }
    }

    private val migration_42_43 = object : Migration(42, 43) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chapters` ADD `isVolume` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * F-P0-2 备份选择器：新增封面图集（分组+图片）和阅读记录详情表
     */
    private val migration_89_90 = object : Migration(89, 90) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 封面图集分组表
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `cover_gallery_groups` (
                    `id` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `isDefault` INTEGER NOT NULL,
                    `order` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id` AUTOINCREMENT)
                )""".trimIndent()
            )
            // 封面图集图片表（外键关联分组，删除分组时级联删除图片）
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `cover_gallery_images` (
                    `id` INTEGER NOT NULL,
                    `groupId` INTEGER NOT NULL,
                    `path` TEXT NOT NULL,
                    `order` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id` AUTOINCREMENT),
                    FOREIGN KEY(`groupId`) REFERENCES `cover_gallery_groups`(`id`) ON DELETE CASCADE
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_cover_gallery_images_groupId` ON `cover_gallery_images` (`groupId`)")
            // 阅读记录详情表（复合主键）
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `readRecordDetail` (
                    `deviceId` TEXT NOT NULL,
                    `bookName` TEXT NOT NULL,
                    `bookAuthor` TEXT NOT NULL DEFAULT '',
                    `date` TEXT NOT NULL,
                    `readTime` INTEGER NOT NULL DEFAULT 0,
                    `readWords` INTEGER NOT NULL DEFAULT 0,
                    `firstReadTime` INTEGER NOT NULL DEFAULT 0,
                    `lastReadTime` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`deviceId`, `bookName`, `bookAuthor`, `date`)
                )""".trimIndent()
            )
        }
    }

    /**
     * F-P1-1 自动任务系统：新增 auto_task_rules 表，存储定时任务规则
     * 借鉴自阅读T (skybbk1001/legadoT)
     */
    private val migration_90_91 = object : Migration(90, 91) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `auto_task_rules` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `enable` INTEGER NOT NULL,
                    `cron` TEXT,
                    `loginUrl` TEXT,
                    `loginUi` TEXT,
                    `loginCheckJs` TEXT,
                    `comment` TEXT,
                    `script` TEXT NOT NULL,
                    `header` TEXT,
                    `jsLib` TEXT,
                    `concurrentRate` TEXT,
                    `enabledCookieJar` INTEGER NOT NULL,
                    `lastRunAt` INTEGER NOT NULL,
                    `lastResult` TEXT,
                    `lastError` TEXT,
                    `lastLog` TEXT,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
        }
    }

    /**
     * F-P1-2 高亮规则系统：新增 highlights 表，存储手动划线高亮
     * 借鉴自阅读T (skybbk1001/legadoT)
     */
    private val migration_91_92 = object : Migration(91, 92) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `highlights` (
                    `time` INTEGER NOT NULL,
                    `bookName` TEXT NOT NULL,
                    `bookAuthor` TEXT NOT NULL,
                    `chapterIndex` INTEGER NOT NULL,
                    `chapterPos` INTEGER NOT NULL,
                    `chapterPosEnd` INTEGER NOT NULL,
                    `chapterName` TEXT NOT NULL,
                    `bookText` TEXT NOT NULL,
                    `style` TEXT NOT NULL,
                    `note` TEXT NOT NULL,
                    PRIMARY KEY(`time`)
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_highlights_bookName_bookAuthor` ON `highlights` (`bookName`, `bookAuthor`)"
            )
        }
    }

    /**
     * rss-cache-first: 重建 rssSources 表，将 cacheFirst 列默认值从 0 改为 1
     * SQLite 不支持 ALTER COLUMN 修改默认值，需重建表
     * 同时将所有现有源的 cacheFirst 字段同步设为 1（启用缓存优先加载）
     */
    private val migration_92_93 = object : Migration(92, 93) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `rssSources_new` (
                    `sourceUrl` TEXT NOT NULL, `sourceName` TEXT NOT NULL, `sourceIcon` TEXT NOT NULL,
                    `sourceGroup` TEXT, `sourceComment` TEXT, `enabled` INTEGER NOT NULL,
                    `variableComment` TEXT, `jsLib` TEXT, `enabledCookieJar` INTEGER DEFAULT 0,
                    `concurrentRate` TEXT, `header` TEXT, `loginUrl` TEXT, `loginUi` TEXT,
                    `loginCheckJs` TEXT, `coverDecodeJs` TEXT, `sortUrl` TEXT,
                    `singleUrl` INTEGER NOT NULL, `articleStyle` INTEGER NOT NULL DEFAULT 0,
                    `ruleArticles` TEXT, `ruleNextPage` TEXT, `ruleTitle` TEXT, `rulePubDate` TEXT,
                    `ruleDescription` TEXT, `ruleImage` TEXT, `ruleLink` TEXT, `ruleContent` TEXT,
                    `contentWhitelist` TEXT, `contentBlacklist` TEXT, `shouldOverrideUrlLoading` TEXT,
                    `style` TEXT, `enableJs` INTEGER NOT NULL DEFAULT 1, `loadWithBaseUrl` INTEGER NOT NULL DEFAULT 1,
                    `injectJs` TEXT, `preloadJs` TEXT, `startHtml` TEXT, `startStyle` TEXT, `startJs` TEXT,
                    `showWebLog` INTEGER NOT NULL DEFAULT 0, `lastUpdateTime` INTEGER NOT NULL DEFAULT 0,
                    `customOrder` INTEGER NOT NULL DEFAULT 0, `type` INTEGER NOT NULL DEFAULT 0,
                    `preload` INTEGER NOT NULL DEFAULT 0, `cacheFirst` INTEGER NOT NULL DEFAULT 1,
                    `searchUrl` TEXT, PRIMARY KEY(`sourceUrl`))""".trimIndent()
            )
            // 复制数据并将 cacheFirst 全部设为 1（启用缓存优先加载，与 RssSource.kt 默认值一致）
            db.execSQL(
                """INSERT INTO `rssSources_new` (`sourceUrl`, `sourceName`, `sourceIcon`, `sourceGroup`,
                    `sourceComment`, `enabled`, `variableComment`, `jsLib`, `enabledCookieJar`, `concurrentRate`,
                    `header`, `loginUrl`, `loginUi`, `loginCheckJs`, `coverDecodeJs`, `sortUrl`,
                    `singleUrl`, `articleStyle`, `ruleArticles`, `ruleNextPage`, `ruleTitle`, `rulePubDate`,
                    `ruleDescription`, `ruleImage`, `ruleLink`, `ruleContent`, `contentWhitelist`,
                    `contentBlacklist`, `shouldOverrideUrlLoading`, `style`, `enableJs`, `loadWithBaseUrl`,
                    `injectJs`, `preloadJs`, `startHtml`, `startStyle`, `startJs`, `showWebLog`,
                    `lastUpdateTime`, `customOrder`, `type`, `preload`, `cacheFirst`, `searchUrl`)
                    SELECT `sourceUrl`, `sourceName`, `sourceIcon`, `sourceGroup`, `sourceComment`, `enabled`,
                    `variableComment`, `jsLib`, `enabledCookieJar`, `concurrentRate`, `header`, `loginUrl`,
                    `loginUi`, `loginCheckJs`, `coverDecodeJs`, `sortUrl`, `singleUrl`, `articleStyle`,
                    `ruleArticles`, `ruleNextPage`, `ruleTitle`, `rulePubDate`, `ruleDescription`, `ruleImage`,
                    `ruleLink`, `ruleContent`, `contentWhitelist`, `contentBlacklist`, `shouldOverrideUrlLoading`,
                    `style`, `enableJs`, `loadWithBaseUrl`, `injectJs`, `preloadJs`, `startHtml`, `startStyle`,
                    `startJs`, `showWebLog`, `lastUpdateTime`, `customOrder`, `type`, `preload`,
                    1 AS `cacheFirst`, `searchUrl` FROM `rssSources`""".trimIndent()
            )
            db.execSQL("DROP TABLE `rssSources`")
            db.execSQL("ALTER TABLE `rssSources_new` RENAME TO `rssSources`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_rssSources_sourceUrl` ON `rssSources` (`sourceUrl`)")
        }
    }

    /**
     * rss-parse-optimization: 93→94 新增 rssArticles(origin, sort) 复合索引
     * 优化 RssArticleDao.flowByOriginSort 查询性能（按 origin+sort 排序的文章列表）
     */
    private val migration_93_94 = object : Migration(93, 94) {
        override fun migrate(db: SupportSQLiteDatabase) {
            kotlin.runCatching {
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_origin_sort` ON `rssArticles` (`origin`, `sort`)")
                AppLog.put("AppDatabase Migration 93→94: 创建 idx_origin_sort 索引成功")
            }.onFailure { e ->
                AppLog.put("AppDatabase Migration 93→94: 创建索引失败: ${e.message}")
            }
        }
    }

    /**
     * rss-concurrency-and-checksource: 94→95
     * rssSources 表新增 parseConcurrency (解析并发数,默认0=使用全局配置) + weight (权重值,校验后回填) 两个字段
     */
    private val migration_94_95 = object : Migration(94, 95) {
        override fun migrate(db: SupportSQLiteDatabase) {
            kotlin.runCatching {
                db.execSQL("ALTER TABLE rssSources ADD COLUMN parseConcurrency INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rssSources ADD COLUMN weight INTEGER NOT NULL DEFAULT 0")
                AppLog.put("AppDatabase Migration 94→95: rssSources 新增 parseConcurrency + weight 字段成功")
            }.onFailure { e ->
                AppLog.put("AppDatabase Migration 94→95: 新增字段失败: ${e.message}")
            }
        }
    }

    /**
     * 域名分组优化: 95→96
     * book_sources + rssSources 表新增 lastHost (AnalyzeUrl解析后的真实域名)
     * 解决: sourceUrl含jslib/注释/#规避等复杂情况,getSourceHost(sourceUrl)提取域名不准
     * 校验时回填source.lastHost=URI(analyzeUrl.url).host,UI分组读取此字段优先
     * 注意: BookSourcePart是DatabaseView,修改其SQL后必须在migration中DROP+CREATE重建view
     *       否则Room校验schema不匹配抛IllegalStateException: Migration didn't properly handle
     */
    private val migration_95_96 = object : Migration(95, 96) {
        override fun migrate(db: SupportSQLiteDatabase) {
            kotlin.runCatching {
                // 1. 给实体表新增 lastHost 字段
                db.execSQL("ALTER TABLE book_sources ADD COLUMN lastHost TEXT")
                db.execSQL("ALTER TABLE rssSources ADD COLUMN lastHost TEXT")
                // 2. 重建 DatabaseView: book_sources_part (新增 lastHost 列)
                //    Room在migration后会校验view的schema,必须手动重建否则抛IllegalStateException
                db.execSQL("DROP VIEW IF EXISTS book_sources_part")
                db.execSQL(
                    """CREATE VIEW book_sources_part AS
                    select bookSourceUrl, bookSourceName, bookSourceGroup, customOrder, enabled, enabledExplore,
                    (loginUrl is not null and trim(loginUrl) <> '') hasLoginUrl, lastUpdateTime, respondTime, weight,
                    (exploreUrl is not null and trim(exploreUrl) <> '') hasExploreUrl, eventListener, bookSourceType, lastHost
                    from book_sources"""
                )
                AppLog.put("AppDatabase Migration 95→96: book_sources + rssSources 新增 lastHost 字段 + 重建 book_sources_part view 成功")
            }.onFailure { e ->
                AppLog.put("AppDatabase Migration 95→96: 失败: ${e.message}")
            }
        }
    }

    /**
     * global-issue-fix Issue-1: 96→97
     * 修复覆盖安装失败问题: 之前发布的某个中间版本96(migration_95_96未包含DROP+CREATE时的版本)
     * 已存在于用户设备。重新安装修复后的96版本时version相同Room不执行migration,但设备上的view
     * 结构是旧的(没有lastHost列),Room schema校验发现不匹配抛IllegalStateException导致App闪退。
     *
     * 解决方案: version 96→97,新增migration_96_97强制重建view。无论之前96是bug版还是修复版,
     * 覆盖安装时都会执行96→97的migration,DROP+CREATE重建view确保结构正确。
     *
     * 同时增强容错: ALTER TABLE前检查列是否存在(防止重复执行报错)
     */
    private val migration_96_97 = object : Migration(96, 97) {
        override fun migrate(db: SupportSQLiteDatabase) {
            kotlin.runCatching {
                // 1. 检查并补齐 book_sources.lastHost 字段(如果之前bug版没加)
                val hasLastHostBook = db.query("PRAGMA table_info(book_sources)").use { cursor ->
                    val columnIndex = cursor.getColumnIndex("name")
                    var exists = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(columnIndex) == "lastHost") {
                            exists = true
                            break
                        }
                    }
                    exists
                }
                if (!hasLastHostBook) {
                    db.execSQL("ALTER TABLE book_sources ADD COLUMN lastHost TEXT")
                    AppLog.put("AppDatabase Migration 96→97: book_sources 补加 lastHost 字段")
                }

                // 2. 检查并补齐 rssSources.lastHost 字段
                val hasLastHostRss = db.query("PRAGMA table_info(rssSources)").use { cursor ->
                    val columnIndex = cursor.getColumnIndex("name")
                    var exists = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(columnIndex) == "lastHost") {
                            exists = true
                            break
                        }
                    }
                    exists
                }
                if (!hasLastHostRss) {
                    db.execSQL("ALTER TABLE rssSources ADD COLUMN lastHost TEXT")
                    AppLog.put("AppDatabase Migration 96→97: rssSources 补加 lastHost 字段")
                }

                // 3. 强制重建 DatabaseView: book_sources_part (无论之前是否已重建)
                //    Room在migration后会校验view的schema,必须手动重建否则抛IllegalStateException
                db.execSQL("DROP VIEW IF EXISTS book_sources_part")
                db.execSQL(
                    """CREATE VIEW book_sources_part AS
                    select bookSourceUrl, bookSourceName, bookSourceGroup, customOrder, enabled, enabledExplore,
                    (loginUrl is not null and trim(loginUrl) <> '') hasLoginUrl, lastUpdateTime, respondTime, weight,
                    (exploreUrl is not null and trim(exploreUrl) <> '') hasExploreUrl, eventListener, bookSourceType, lastHost
                    from book_sources"""
                )
                AppLog.put("AppDatabase Migration 96→97: 强制重建 book_sources_part view 成功 (修复覆盖安装失败)")
            }.onFailure { e ->
                AppLog.put("AppDatabase Migration 96→97: 失败: ${e.message}")
            }
        }
    }


    /**
     * global-issue-fix Issue-1 续修: 97→98
     * 根因: book_sources 表的 weight/eventListener/customButton 字段从未有对应 migration 添加,
     * 从旧版本(89-)覆盖安装时这些字段不存在,但 migration_95_96/96_97 的 view 引用了 weight,
     * 导致 view 创建失败 → Room schema 校验失败 → App 闪退。
     * 全新安装不触发(直接用最新 schema 建表),仅覆盖安装触发。
     *
     * 修复: 全面检查并补齐 book_sources + rssSources 表所有可能缺失的字段,然后强制重建 view。
     * 无论之前 95/96/97 是 bug 版还是修复版,覆盖安装时都会执行 97→98 的 migration。
     */
    private val migration_97_98 = object : Migration(97, 98) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 检查并补齐 book_sources 表缺失字段
            ensureColumn(db, "book_sources", "weight", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "book_sources", "eventListener", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "book_sources", "customButton", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "book_sources", "lastHost", "TEXT")
            // 2. 检查并补齐 rssSources 表缺失字段
            ensureColumn(db, "rssSources", "weight", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "rssSources", "parseConcurrency", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "rssSources", "lastHost", "TEXT")
            // 3. 强制重建 DatabaseView: book_sources_part
            //    SQL 格式必须与 @DatabaseView 注解完全一致(含反引号和缩进),否则 Room schema 校验失败
            db.execSQL("DROP VIEW IF EXISTS `book_sources_part`")
            db.execSQL(
                """CREATE VIEW `book_sources_part` AS select bookSourceUrl, bookSourceName, bookSourceGroup, customOrder, enabled, enabledExplore,
    (loginUrl is not null and trim(loginUrl) <> '') hasLoginUrl, lastUpdateTime, respondTime, weight,
    (exploreUrl is not null and trim(exploreUrl) <> '') hasExploreUrl, eventListener, bookSourceType, lastHost
    from book_sources"""
            )
            AppLog.put("AppDatabase Migration 97→98: 补齐缺失字段 + 重建 view 成功")
        }

        private fun ensureColumn(db: SupportSQLiteDatabase, table: String, column: String, definition: String) {
            val exists = db.query("PRAGMA table_info($table)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) {
                        found = true
                        break
                    }
                }
                found
            }
            if (!exists) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
                AppLog.put("AppDatabase Migration 97→98: $table 补加 $column 字段")
            }
        }
    }

    /**
     * rss-unified-search: 98→99
     *
     * search_keywords 表改为复合主键 (word, type)：
     * - 原 schema：单字段主键 word + UNIQUE INDEX(word)
     * - 新 schema：复合主键 (word, type)，无 UNIQUE INDEX
     *
     * 因 Room 不支持直接修改主键，采用 drop+create 重建表策略：
     * 1. 创建新表 search_keywords_new（复合主键）
     * 2. 从旧表迁移数据（type 默认为 0，兼容旧书源搜索历史）
     * 3. 删除旧表
     * 4. 重命名新表为 search_keywords
     * 5. 创建索引（按 type + lastUseTime 查询优化）
     *
     * 注意：表名 search_keywords（带下划线），不是 searchKeywords
     */
    private val migration_98_99 = object : Migration(98, 99) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 创建新表（复合主键 word + type）
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `search_keywords_new` (
                    `word` TEXT NOT NULL,
                    `usage` INTEGER NOT NULL DEFAULT 0,
                    `lastUseTime` INTEGER NOT NULL DEFAULT 0,
                    `type` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`word`, `type`)
                )""".trimIndent()
            )
            // 2. 从旧表迁移数据（type 默认为 0，兼容旧书源搜索历史）
            db.execSQL(
                """INSERT INTO `search_keywords_new` (`word`, `usage`, `lastUseTime`, `type`)
                   SELECT `word`, `usage`, `lastUseTime`, 0 FROM `search_keywords`""".trimIndent()
            )
            // 3. 删除旧表
            db.execSQL("DROP TABLE `search_keywords`")
            // 4. 重命名新表为 search_keywords
            db.execSQL("ALTER TABLE `search_keywords_new` RENAME TO `search_keywords`")
            // 5. 创建索引（按 type + lastUseTime 查询优化，用于 flowByTime(type) 查询）
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_search_keywords_type_lastUseTime` ON `search_keywords` (`type`, `lastUseTime`)"
            )
            AppLog.put("AppDatabase Migration 98→99: search_keywords 表改为复合主键(word, type) 成功")
        }
    }

    /**
     * multiline-on-demand-extraction: 99→100
     * rssSources 表新增 ruleRoutes (多线路规则) + ruleEpisodes (多集规则) 两个字段
     * 仅 type=2 视频源使用,支持 CSS/JSONPath/XPath/JS 四种写法
     * 用 runCatching 包裹防止重复执行报错(参考 v3.26.0717-bug-fix-batch 经验)
     */
    private val migration_99_100 = object : Migration(99, 100) {
        override fun migrate(db: SupportSQLiteDatabase) {
            kotlin.runCatching {
                db.execSQL("ALTER TABLE rssSources ADD COLUMN ruleRoutes TEXT")
                AppLog.put("AppDatabase Migration 99→100: rssSources 新增 ruleRoutes 字段成功")
            }.onFailure { e ->
                AppLog.put("AppDatabase Migration 99→100: ruleRoutes 已存在或失败: ${e.message}")
            }
            kotlin.runCatching {
                db.execSQL("ALTER TABLE rssSources ADD COLUMN ruleEpisodes TEXT")
                AppLog.put("AppDatabase Migration 99→100: rssSources 新增 ruleEpisodes 字段成功")
            }.onFailure { e ->
                AppLog.put("AppDatabase Migration 99→100: ruleEpisodes 已存在或失败: ${e.message}")
            }
        }
    }

    /**
     * AD-04: 100→101 新增 playHistories 表（播放历史跨会话进度恢复）
     */
    private val migration_100_101 = object : Migration(100, 101) {
        override fun migrate(db: SupportSQLiteDatabase) {
            kotlin.runCatching {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS playHistories(
                        articleUrl TEXT NOT NULL,
                        videoUrl TEXT NOT NULL,
                        position INTEGER NOT NULL DEFAULT 0,
                        duration INTEGER NOT NULL DEFAULT 0,
                        lastPlayTime INTEGER NOT NULL DEFAULT 0,
                        rssSourceId TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(articleUrl, videoUrl)
                    )"""
                )
                AppLog.put("AppDatabase Migration 100→101: playHistories 表创建成功")
            }.onFailure { e ->
                AppLog.put("AppDatabase Migration 100→101: playHistories 表创建失败: ${e.message}")
            }
        }
    }


    /**
     * B7: 101→102 新增 source_recycle_bin 表（规则回收站）
     */
    private val migration_101_102 = object : Migration(101, 102) {
        override fun migrate(db: SupportSQLiteDatabase) {
            kotlin.runCatching {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS source_recycle_bin(
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL DEFAULT '',
                        key TEXT NOT NULL DEFAULT '',
                        name TEXT NOT NULL DEFAULT '',
                        groupName TEXT,
                        payload TEXT NOT NULL DEFAULT '',
                        deletedAt INTEGER NOT NULL DEFAULT 0,
                        expireAt INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_source_recycle_bin_type ON source_recycle_bin(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_source_recycle_bin_key ON source_recycle_bin(key)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_source_recycle_bin_expireAt ON source_recycle_bin(expireAt)")
                AppLog.put("AppDatabase Migration 101→102: source_recycle_bin 表创建成功")
            }.onFailure { e ->
                AppLog.put("AppDatabase Migration 101→102: source_recycle_bin 表创建失败: ${e.message}")
            }
        }
    }

    /**
     * precise-manage: 102→103 新增 url_records 表（网址记录）
     */
    private val migration_102_103 = object : Migration(102, 103) {
        override fun migrate(db: SupportSQLiteDatabase) {
            kotlin.runCatching {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS url_records(
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        url TEXT NOT NULL,
                        domain TEXT NOT NULL,
                        method TEXT NOT NULL,
                        sourceName TEXT,
                        sourceUrl TEXT,
                        timestamp INTEGER NOT NULL,
                        responseCode INTEGER NOT NULL,
                        duration INTEGER NOT NULL,
                        requestBody TEXT,
                        errorMsg TEXT
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_url_records_timestamp ON url_records(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_url_records_domain ON url_records(domain)")
                AppLog.put("AppDatabase Migration 102→103: url_records 表创建成功")
            }.onFailure { e ->
                AppLog.put("AppDatabase Migration 102→103: url_records 表创建失败: ${e.message}")
            }
        }
    }


    @Suppress("ClassName")
    class Migration_54_55 : AutoMigrationSpec {

        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                update books set type = ${BookType.audio}
                where type = ${BookSourceType.audio}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = ${BookType.image}
                where type = ${BookSourceType.image}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = ${BookType.webFile}
                where type = ${BookSourceType.file}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = ${BookType.text}
                where type = ${BookSourceType.default}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = type | ${BookType.local}
                where origin like '${BookType.localTag}%' or origin like '${BookType.webDavTag}%'
            """.trimIndent()
            )
        }

    }


    @Suppress("ClassName")
    @DeleteColumn(
        tableName = "book_sources",
        columnName = "enabledReview"
    )
    class Migration_64_65 : AutoMigrationSpec

    @Suppress("ClassName")
    class Migration_80_81 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
            CREATE TABLE rssArticles_new (
                origin TEXT NOT NULL DEFAULT '',
                sort TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                `order` INTEGER NOT NULL DEFAULT 0,
                link TEXT NOT NULL DEFAULT '',
                pubDate TEXT,
                description TEXT,
                content TEXT,
                image TEXT,
                `group` TEXT NOT NULL DEFAULT '默认分组',
                read INTEGER NOT NULL DEFAULT 0,
                variable TEXT,
                PRIMARY KEY (origin, link, sort)
            )
        """.trimIndent())
            db.execSQL("""
            INSERT INTO rssArticles_new (origin, sort, title, `order`, link, pubDate, description, content, image, `group`, read, variable)
            SELECT origin, sort, title, `order`, link, pubDate, description, content, image, `group`, read, variable FROM rssArticles
        """.trimIndent())
            db.execSQL("DROP TABLE rssArticles")
            db.execSQL("ALTER TABLE rssArticles_new RENAME TO rssArticles")
        }
    }

    @Suppress("ClassName")
    @DeleteColumn(
        tableName = "rssArticles",
        columnName = "ratio"
    )
    class Migration_83_84 : AutoMigrationSpec

    @Suppress("ClassName")
    @DeleteColumn(
        tableName = "chapters",
        columnName = "lyric"
    )
    @DeleteColumn(
        tableName = "chapters",
        columnName = "reviewImg"
    )
    class Migration_84_85 : AutoMigrationSpec

}