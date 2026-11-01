package io.legado.app.help.storage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-05 / 1.2.7：**备份 ↔ 恢复对等性**不变量（防「勾了却不写 / 写了却不还原」再犯）。
 *
 * 缺陷背景（用户报障 #1）：`BackupSelectorConfig.allItems` 共 31 项，但 `Restore.restore()`
 * 只还原 26 项 ⇒ **5 类在换设备/重装后静默丢失**（封面图集 / 高亮规则 / 书源运行数据 /
 * 背景图片 / 书籍缓存）；Web 备份路径另漏 5 类。此类缺口**编译期完全无感**，只能靠不变量守住。
 *
 * 为何用「源码不变量」而非运行时断言：`Backup`/`Restore` 均依赖 `appCtx`（`splitext.init`）
 * 与真实文件系统根目录，纯 JVM 单测无法完整驱动其落库路径；本仓既有同形态先例
 * `AutoTaskBackupSyncTest`（已验证有效）。运行时往返（Web 备份产物含新类 / 恢复后可见）
 * 由 §12.3 D3-III「数据往返一致」真机档覆盖。
 */
class BackupRestoreParityTest {

    @Test
    fun retiredPrefKeysStayInIgnoreList() {
        // 2026-10-15 死件普查：已退役的 pref 键（旧备份里可能仍存在）必须留在 ignorePrefKeys，
        // 否则旧备份会把已删键写回（详见 constant/RetiredPreferKeyTest 的「删键三处」约定）
        val cfg = read("src/main/java/io/legado/app/help/storage/BackupConfig.kt")
        listOf(
            "floatingBottomBarHideSearch",   // 2026-09-27 底栏悬浮搜索按钮裁决
            "downloadDismissedIds",          // 2026-10-15 死件普查（能力已由 DB 承载）
            "highlightRuleBuiltinMergedV2",  // 2026-10-15 死件普查（一次性迁移标志，使命已完成）
            // NEW-52（2026-10-16）：「设置项形同虚设」审计命中 —— 两项功能已迁移，键与 XML 条目均已删
            "cleanCache",
            "clearWebViewData",
            // NEW-87（2026-10-16）：幽灵设置「回复栏跟随圆角」存储链退役（消费点 `UiCorner.replyRadius`
            // 零调用、仓内无「回复栏」视图）⇒ 日/夜两键防旧备份写回
            "uiCornerReplyFollow",
            "uiCornerReplyFollowNight",
        ).forEach { key ->
            assertTrue("已退役键必须纳入 ignorePrefKeys（防写回）：$key", cfg.contains("\"$key\""))
        }
    }

    private fun read(rel: String): String =
        listOf(File(rel), File("../app/$rel"), File("app/$rel")).first { it.isFile }.readText()

    private val selector by lazy {
        read("src/main/java/io/legado/app/help/storage/BackupSelectorConfig.kt")
    }
    private val backup by lazy { read("src/main/java/io/legado/app/help/storage/Backup.kt") }
    private val restore by lazy { read("src/main/java/io/legado/app/help/storage/Restore.kt") }

    /**
     * `allItems` 第 2 个构造参数（文件名）的**别名**表。
     *
     * 只登记「两侧用常量而非字面量书写」的情形 —— 字面量一致的条目无需别名。
     */
    private val fileNameAliases = mapOf(
        "\"highlightRule.json\"" to listOf("HighlightRuleStore.backupFileName"),
        "\"封面图集\"" to listOf("CoverGalleryRepository.backupDirName"),
        "\"shareReadConfig.json\"" to listOf("ReadBookConfig.shareConfigFileName"),
        "\"readConfig.json\"" to listOf("ReadBookConfig.configFileName"),
        "\"themeConfig.json\"" to listOf("ThemeConfig.configFileName"),
        "\"coverRule.json\"" to listOf("BookCover.configFileName"),
        "\"directLinkUploadRule.json\"" to listOf("DirectLinkUpload.ruleFileName"),
        // 备份侧写 `"videoConfig.xml"`，恢复侧读 SharedPreferences 名 `VIDEO_PREF_NAME`
        "\"videoConfig.xml\"" to listOf("VIDEO_PREF_NAME"),
        // 备份侧写 `"config.xml"`，恢复侧读 SharedPreferences 名 `"config"`
        "\"config.xml\"" to listOf("\"config\""),
        // 目录类：两侧用常量而非字面量
        "\"bg\"" to listOf("Backup.READ_BG_DIR", "READ_BG_DIR"),
        "\"book_cache\"" to listOf("Backup.bookCacheFolderName", "bookCacheFolderName"),
        // NEW-51：朗读配乐/音效亦是**目录项**（同 `bg`）——备份/恢复两侧统一走常量
        // `Backup.READ_ALOUD_AUDIO_DIR`（单源，与存储侧目录名防漂移）
        "\"readAloudAudio\"" to listOf("Backup.READ_ALOUD_AUDIO_DIR", "READ_ALOUD_AUDIO_DIR"),
        // 2026-11-01：音色库/角色规则同为目录项 —— 选择器写全限定名 `Backup.Xxx`，
        // `Backup.kt` 自身只有短名声明 ⇒ 别名同时登记两种写法（恢复侧用全限定名）
        "Backup.TTS_VOICES_DIR" to listOf("TTS_VOICES_DIR", "Backup.TTS_VOICES_DIR"),
        "Backup.LEGADO_RULES_DIR" to listOf("LEGADO_RULES_DIR", "Backup.LEGADO_RULES_DIR")
    )

    /** 从 `BackupItem("key", "fileName", …)` 中提取第 2 个构造参数的字面文本（剔除注释行）。 */
    private fun declaredFileNames(): List<String> {
        val codeOnly = selector.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val regex = Regex("""BackupItem\(\s*"[^"]+",\s*([^,]+),""")
        return regex.findAll(codeOnly).map { it.groupValues[1].trim() }.toList()
    }

    private fun SourceContains(source: String, token: String) = source.contains(token)

    private fun matches(source: String, fileNameToken: String): Boolean {
        val tokens = listOf(fileNameToken) + fileNameAliases[fileNameToken].orEmpty()
        return tokens.any { SourceContains(source, it) }
    }

    @Test
    fun selectorItemsAreAllWrittenByBackup() {
        val declared = declaredFileNames()
        assertTrue("allItems 解析失败（期望 ≥ 31 项，实得 ${declared.size}）", declared.size >= 31)
        declared.forEach { token ->
            assertTrue("备份侧未写出该类别：$token", matches(backup, token))
        }
    }

    @Test
    fun selectorItemsAreAllRestoredByRestore() {
        val declared = declaredFileNames()
        declared.forEach { token ->
            assertTrue("恢复侧未读回该类别（对等性缺口）：$token", matches(restore, token))
        }
    }

    @Test
    fun deadBackupFileNamesListIsRemoved() {
        // 该 lazy 数组全仓零运行时引用，却让人误以为「清单已覆盖」⇒ 已删（防被重新加回）。
        // 断言的是**声明**而非词面（注释里会提到它为何被删）。
        assertTrue(
            "死清单 Backup.backupFileNames 不得重新出现",
            !Regex("""val\s+backupFileNames""").containsMatchIn(backup)
        )
    }

    @Test
    fun bgRestoreReversesBackupLayout() {
        // 背景图备份「平铺根目录 + <prefKey>/ 子目录」⇒ 恢复必须反向映射到 externalFiles 下
        assertTrue("恢复须使用 Backup.READ_BG_DIR 单源常量", restore.contains("Backup.READ_BG_DIR"))
        assertTrue("恢复须回填主题背景目录", restore.contains("PreferKey.bgImage") && restore.contains("PreferKey.bgImageN"))
    }
}