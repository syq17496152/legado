package io.legado.app.help.storage

import io.legado.app.data.repository.CoverGalleryRepository
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx

/**
 * F-P0-2 备份选择器（借鉴蛋蛋Max）
 * 备份项选择配置，持久化用户选择备份的文件列表
 */
@Suppress("ConstPropertyName")
object BackupSelectorConfig {

    private val configPath = FileUtils.getPath(appCtx.filesDir, "backupSelector.json")

    data class BackupItem(
        val key: String,
        val fileName: String,
        val title: String,
        val group: String
    )

    val allItems = listOf(
        BackupItem("coverGallery", CoverGalleryRepository.backupDirName, "封面图集", "配置"),
        BackupItem("bookshelf", "bookshelf.json", "书架", "数据库"),
        // R17 修正（2026-09-24）：原有一行 `BackupItem("bookChapter", "bookChapter.json", "章节目录", …)`
        // 是**只列不产出的死条目** —— 全仓零处写/读 `bookChapter.json`；章节目录实际由
        // `Backup.stageBookChapterForCache()` 写出 `bookChapterCache.json`，且**由「书籍缓存」门控**
        // （其 KDoc 原文：「备份选中书籍的章节目录（与缓存一起，确保恢复后可读）」）
        // ⇒ 该条目勾选与否均无效果（R17 的分选 UI 会把它暴露给用户，属必须清掉的误导）。
        // 语义已由下方 `bookCache` 条目完整覆盖，故整条删除（非改名）。
        BackupItem("bookmark", "bookmark.json", "书签", "数据库"),
        BackupItem("bookGroup", "bookGroup.json", "书籍分组", "数据库"),
        BackupItem("bookSource", "bookSource.json", "书源", "数据库"),
        BackupItem("rssSources", "rssSources.json", "订阅源", "数据库"),
        BackupItem("rssStar", "rssStar.json", "订阅收藏", "数据库"),
        BackupItem("replaceRule", "replaceRule.json", "替换规则", "数据库"),
        // B2.5：手动划线。注意**三处必须同名同文件**：本表 + Backup.backupFileNames + Backup 导出分支
        BackupItem("highlight", "highlights.json", "划线批注", "数据库"),
        // R8（B2，2026-09-23）：自动任务规则（类别归「数据库」，与 Backup.kt 清单 / Restore 分支同名同文件）
        BackupItem("autoTask", "autoTask.json", "自动任务", "数据库"),
        BackupItem("highlightRule", "highlightRule.json", "高亮规则", "配置"),
        BackupItem("readRecord", "readRecord.json", "阅读记录", "数据库"),
        BackupItem("readRecordDetail", "readRecordDetail.json", "阅读记录详情", "数据库"),
        BackupItem("searchHistory", "searchHistory.json", "搜索历史", "数据库"),
        BackupItem("sourceSub", "sourceSub.json", "订阅源订阅", "数据库"),
        BackupItem("txtTocRule", "txtTocRule.json", "TXT目录规则", "数据库"),
        BackupItem("httpTTS", "httpTTS.json", "TTS配置", "数据库"),
        BackupItem("ttsCastingTemplates", "ttsCastingTemplates.json", "选角模板", "数据库"),
        BackupItem("keyboardAssists", "keyboardAssists.json", "键盘辅助", "数据库"),
        BackupItem("dictRule", "dictRule.json", "词典规则", "数据库"),
        BackupItem("servers", "servers.json", "服务器配置", "数据库"),
        BackupItem("runtimeSourceCache", "runtimeSourceCache.json", "书源运行数据", "数据库"),
        BackupItem("readConfig", "readConfig.json", "阅读样式配置", "配置"),
        // R17 修正（2026-09-24）：原为字面量 `"readShareConfig.json"`，与真实文件名**写反**
        // （备份侧 `Backup.kt` 与恢复侧 `Restore.kt:299` 用的是
        // `ReadBookConfig.shareConfigFileName` = `shareReadConfig.json`）⇒ 勾选该键不会裁剪真实文件。
        // 改用常量引用，与既有 `coverGallery` 条目同口径，从结构上防再次漂移。
        BackupItem("readShareConfig", ReadBookConfig.shareConfigFileName, "阅读分享配置", "配置"),
        BackupItem("themeConfig", "themeConfig.json", "主题配置", "配置"),
        BackupItem("coverRule", "coverRule.json", "封面规则", "配置"),
        BackupItem("directLinkRule", "directLinkUploadRule.json", "直链规则", "配置"),
        BackupItem("appConfig", "config.xml", "应用配置", "配置"),
        BackupItem("videoConfig", "videoConfig.xml", "视频配置", "配置"),
        BackupItem("backgroundImages", "bg", "背景图片", "其他"),
        BackupItem("bookCache", "book_cache", "书籍缓存", "其他")
    )

    val groups = allItems.map { it.group }.distinct()

    val groupItems: Map<String, List<BackupItem>> = allItems.groupBy { it.group }

    private var selectedMap: MutableMap<String, Boolean> = load()

    private fun load(): MutableMap<String, Boolean> {
        val map = HashMap<String, Boolean>()
        val file = FileUtils.createFileIfNotExist(configPath)
        if (file.exists() && file.length() > 0) {
            val json = file.readText()
            GSON.fromJsonObject<Map<String, Boolean>>(json).getOrNull()?.let {
                map.putAll(it)
            }
        }
        return map
    }

    fun isSelected(key: String): Boolean {
        return selectedMap[key] ?: true
    }

    fun setSelected(key: String, selected: Boolean) {
        selectedMap[key] = selected
    }

    fun selectAll() {
        allItems.forEach { selectedMap[it.key] = true }
    }

    fun deselectAll() {
        allItems.forEach { selectedMap[it.key] = false }
    }

    fun getSelectedFileNames(): List<String> {
        return allItems.filter { isSelected(it.key) }.map { it.fileName }
    }

    fun isAllSelected(): Boolean {
        return allItems.all { isSelected(it.key) }
    }

    fun isNoneSelected(): Boolean {
        return allItems.none { isSelected(it.key) }
    }

    fun save() {
        val json = GSON.toJson(selectedMap.toMap())
        FileUtils.createFileIfNotExist(configPath).writeText(json)
    }
}
