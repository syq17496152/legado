package io.legado.app.service.kernel

import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppearanceKit
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.TopBarConfig
import io.legado.app.model.BookCover
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * ㉑ 外观资源域业务内核（web-mcp-productization 二期 · tasks 2.24b / 2.28）。
 *
 * 契约同 [BookKernel]：只返回领域对象 / 结构化 Map、全链挂起、零阻塞调用、失败抛异常。
 *
 * ## AD-17 边界声明（定义源暴露 / 渲染实现不暴露）
 * 本内核**只暴露「数据定义源」**：各 PackageManager 的 `Config` / `Entry` 列表与读写（主题包 / 应用套件 /
 * 顶栏包 / 底栏包 / 气泡模板 / 分享模板 / 封面图集 / 封面规则）。
 * **不暴露渲染实现**：状态栏落地（`BaseActivity` / `Activity.setStatusBarColorAuto`）、View 栈取色
 * （`ThemeStore.statusBarColor`）、沉浸开关消费、启动图标（`LauncherIconHelp`）、Compose 渲染骨架
 * （`LegadoTheme` / `ThemeSpec.toM3Scheme`）、主题刷新链路（`ThemeSync`）、气泡渲染消费点
 * （`ParagraphBubbleRenderer`）——本内核一概不 import。
 *
 * ## 已知上限 / 降级
 * 1. [themePackInstall] / [kitSave]（import）/ [bookInfoLayoutSave] 为**写盘/导入类操作，属危险操作**，
 *    危险属性由工具层标记；内核只执行、不拦截。
 * 2. [bookInfoLayoutGet] / [bookInfoLayoutSave]：勘查报告未核实独立的「书籍详情页布局」定义源，
 *    此处以报告 §㉑ 唯一已核实的书籍展示侧定义源 `BookCover.CoverRule`（**全局封面规则，非按书**）为口径；
 *    `bookUrl` 参数不参与规则定位（降级说明）。输出侧剔除 `header` / `jsLib` / `loginUi` / `loginUrl`（可携带凭据/脚本）。
 * 3. 各 `*Save` 只**落盘定义**，**不触发应用/渲染**（应用选择由上层决定）。
 */
object AppearanceKernel {

    // ---------------------------------------------------------------- 主题包

    /** `theme_pack_list`：主题包列表（`includeRemote=true` 时合并云端；默认仅本地 + 内置）。 */
    suspend fun themePackList(
        isNight: Boolean = AppConfig.isNightTheme,
        includeRemote: Boolean = false,
    ): Map<String, Any?> = withContext(IO) {
        val entries = if (includeRemote) {
            ThemePackageManager.load(isNight)
        } else {
            ThemePackageManager.loadLocalOnly(isNight)
        }
        mapOf(
            "isNight" to isNight,
            "total" to entries.size,
            "items" to entries.map {
                mapOf(
                    "name" to it.packageInfo.name,
                    "dirName" to it.dirName,
                    "isNightTheme" to it.packageInfo.isNightTheme,
                    "source" to it.source.name,
                    "updatedAt" to it.packageInfo.updatedAt,
                    "remoteUpdatedAt" to it.remoteUpdatedAt,
                )
            },
        )
    }

    /** `theme_pack_install`：安装主题包（`ThemePackageManager.importPackageDetailed`；危险操作，属性由工具层标记）。 */
    suspend fun themePackInstall(filePath: String): Map<String, Any?> = withContext(IO) {
        require(filePath.isNotBlank()) { "filePath 不能为空" }
        val file = File(filePath)
        require(file.isFile) { "主题包文件不存在：$filePath" }
        val result = ThemePackageManager.importPackageDetailed(file)
        mapOf(
            "installed" to true,
            "sourceName" to result.sourceName,
            "themeCount" to result.themes.size,
            "navigationBarCount" to result.navigationBars.size,
            "coverCollectionCount" to result.coverCollections.size,
        )
    }

    // ---------------------------------------------------------------- 应用套件

    /** `kit_list`：外观套件（内置 + 已导入）。 */
    suspend fun kitList(): Map<String, Any?> = withContext(IO) {
        val current = AppearanceKitManager.currentKitId()
        val builtin = AppearanceKitManager.builtinKits()
        val imported = AppearanceKitManager.importedThemeKits()
        fun kitBrief(kit: AppearanceKit): Map<String, Any?> = mapOf(
            "id" to kit.id,
            "name" to kit.name,
            "summary" to kit.summary,
            "type" to kit.type.name,
            "current" to (kit.id == current),
        )
        mapOf(
            "currentKitId" to current,
            "builtin" to builtin.map { kitBrief(it) },
            "imported" to imported.map { kitBrief(it) },
        )
    }

    /**
     * `kit_save`：套件操作。
     *
     * `action`：`import`（需 `filePath`，`AppearanceKitManager.importPackage`）/ `create`（需 `name`，
     * 从当前外观创建）/ `rename`（需 `kitId` + `name`）/ `delete`（需 `kitId`，危险操作）。
     */
    suspend fun kitSave(
        action: String,
        filePath: String? = null,
        kitId: String? = null,
        name: String? = null,
    ): Map<String, Any?> = withContext(IO) {
        when (action) {
            "import" -> {
                val cleanPath = filePath?.trim().orEmpty()
                require(cleanPath.isNotBlank()) { "import 需要 filePath" }
                val file = File(cleanPath)
                require(file.isFile) { "套件包文件不存在：$cleanPath" }
                val result = AppearanceKitManager.importPackage(file)
                mapOf(
                    "action" to action,
                    "themeCount" to result.themeCount,
                    "topBarCount" to result.topBarCount,
                    "navigationBarCount" to result.navigationBarCount,
                    "coverCollectionCount" to result.coverCollectionCount,
                    "kitCount" to result.kitCount,
                    "total" to result.total,
                )
            }

            "create" -> {
                val cleanName = name?.trim().orEmpty()
                require(cleanName.isNotBlank()) { "create 需要 name" }
                val kit = AppearanceKitManager.createFromCurrent(appCtx, cleanName)
                mapOf("action" to action, "id" to kit.id, "name" to kit.name)
            }

            "rename" -> {
                val cleanId = kitId?.trim().orEmpty()
                val cleanName = name?.trim().orEmpty()
                require(cleanId.isNotBlank()) { "rename 需要 kitId" }
                require(cleanName.isNotBlank()) { "rename 需要 name" }
                val kit = AppearanceKitManager.importedThemeKits().firstOrNull { it.id == cleanId }
                    ?: throw NoSuchElementException("套件不存在：$cleanId")
                mapOf("action" to action, "changed" to AppearanceKitManager.renameKit(kit, cleanName))
            }

            "delete" -> {
                val cleanId = kitId?.trim().orEmpty()
                require(cleanId.isNotBlank()) { "delete 需要 kitId" }
                val kit = AppearanceKitManager.importedThemeKits().firstOrNull { it.id == cleanId }
                    ?: throw NoSuchElementException("套件不存在：$cleanId")
                mapOf("action" to action, "changed" to AppearanceKitManager.deleteImportedTheme(appCtx, kit))
            }

            else -> throw IllegalArgumentException(
                "未知 action：$action（支持 import/create/rename/delete）"
            )
        }
    }

    // ---------------------------------------------------------------- 顶栏包

    /** `top_bar_pack_list`：顶栏包列表（`active` 为当前生效）。 */
    suspend fun topBarPackList(
        isNight: Boolean = AppConfig.isNightTheme,
        includeRemote: Boolean = false,
    ): Map<String, Any?> = withContext(IO) {
        val active = TopBarConfig.activeDirName(isNight)
        val entries = TopBarConfig.loadEntries(appCtx, isNight, includeRemote)
        mapOf(
            "isNight" to isNight,
            "activeDirName" to active,
            "total" to entries.size,
            "items" to entries.map {
                mapOf(
                    "dirName" to it.dirName,
                    "name" to it.config.name,
                    "isNightMode" to it.config.isNightMode,
                    "style" to it.config.style,
                    "source" to it.source.name,
                    "updatedAt" to it.config.updatedAt,
                    "active" to (it.dirName == active),
                )
            },
        )
    }

    /** `top_bar_pack_save`：新增 / 更新顶栏包（`oldDirName` 给定时按原目录覆盖，否则新建）。 */
    suspend fun topBarPackSave(
        config: TopBarConfig.Config,
        oldDirName: String? = null,
    ): Map<String, Any?> = withContext(IO) {
        val oldEntry = oldDirName?.takeIf { it.isNotBlank() }?.let { dir ->
            TopBarConfig.loadEntries(appCtx, config.isNightMode, includeRemote = false)
                .firstOrNull { it.dirName == dir }
        }
        val entry = TopBarConfig.addOrUpdate(config, oldEntry)
        mapOf(
            "saved" to true,
            "dirName" to entry.dirName,
            "name" to entry.config.name,
            "isNightMode" to entry.config.isNightMode,
        )
    }

    // ---------------------------------------------------------------- 底栏包（导航图标）

    /** `nav_bar_pack_list`：底栏包列表。 */
    suspend fun navBarPackList(
        isNight: Boolean = AppConfig.isNightTheme,
        includeRemote: Boolean = false,
    ): Map<String, Any?> = withContext(IO) {
        val active = NavigationBarIconConfig.activeDirName(isNight)
        val entries = NavigationBarIconConfig.loadEntries(isNight, includeRemote)
        mapOf(
            "isNight" to isNight,
            "activeDirName" to active,
            "total" to entries.size,
            "items" to entries.map {
                mapOf(
                    "dirName" to it.dirName,
                    "name" to it.config.name,
                    "isNightMode" to it.config.isNightMode,
                    "layoutMode" to it.config.layoutMode,
                    "effectMode" to it.config.effectMode,
                    "source" to it.source.name,
                    "updatedAt" to it.config.updatedAt,
                    "active" to (it.dirName == active),
                )
            },
        )
    }

    /** `nav_bar_pack_save`：新增 / 更新底栏包。 */
    suspend fun navBarPackSave(
        config: NavigationBarIconConfig.Config,
        oldDirName: String? = null,
    ): Map<String, Any?> = withContext(IO) {
        val oldEntry = oldDirName?.takeIf { it.isNotBlank() }?.let { dir ->
            NavigationBarIconConfig.loadEntries(config.isNightMode, includeRemote = false)
                .firstOrNull { it.dirName == dir }
        }
        val entry = NavigationBarIconConfig.addOrUpdate(config, oldEntry)
        mapOf(
            "saved" to true,
            "dirName" to entry.dirName,
            "name" to entry.config.name,
            "isNightMode" to entry.config.isNightMode,
        )
    }

    // ---------------------------------------------------------------- 气泡模板

    /** `bubble_template_list`：段落气泡模板列表（含内置）。 */
    suspend fun bubbleTemplateList(): Map<String, Any?> = withContext(IO) {
        val active = BubblePackageManager.activeDirName()
        val entries = BubblePackageManager.loadEntries()
        mapOf(
            "activeDirName" to active,
            "total" to entries.size,
            "items" to entries.map {
                mapOf(
                    "dirName" to it.dirName,
                    "name" to it.config.name,
                    "sizeScale" to it.config.sizeScale,
                    "source" to it.source.name,
                    "updatedAt" to it.config.updatedAt,
                    "active" to (it.dirName == active),
                )
            },
        )
    }

    /** `bubble_template_save`：新增 / 更新气泡模板（内置模板不可覆盖）。 */
    suspend fun bubbleTemplateSave(
        config: BubblePackageManager.Config,
        oldDirName: String? = null,
    ): Map<String, Any?> = withContext(IO) {
        val oldEntry = oldDirName?.takeIf { it.isNotBlank() }?.let { dir ->
            BubblePackageManager.loadEntries().firstOrNull { it.dirName == dir }
        }
        val entry = BubblePackageManager.addOrUpdate(config, oldEntry)
        mapOf("saved" to true, "dirName" to entry.dirName, "name" to entry.config.name)
    }

    // ---------------------------------------------------------------- 分享模板

    /** `share_template_list`：分享便签模板列表（含内置）。 */
    suspend fun shareTemplateList(): Map<String, Any?> = withContext(IO) {
        val active = ShareNoteTemplateManager.activeDirName()
        val entries = ShareNoteTemplateManager.loadEntries()
        mapOf(
            "activeDirName" to active,
            "total" to entries.size,
            "items" to entries.map {
                mapOf(
                    "dirName" to it.dirName,
                    "name" to it.meta.name,
                    "canvas" to it.meta.canvas,
                    "width" to it.meta.width,
                    "height" to it.meta.height,
                    "output" to it.meta.output,
                    "source" to it.source.name,
                    "updatedAt" to it.meta.updatedAt,
                    "active" to (it.dirName == active),
                )
            },
        )
    }

    /** `share_template_save`：新增 / 更新分享模板（传 HTML，元信息由 `parseMeta` 解析；内置模板目录外新建）。 */
    suspend fun shareTemplateSave(
        html: String,
        oldDirName: String? = null,
    ): Map<String, Any?> = withContext(IO) {
        require(html.isNotBlank()) { "html 不能为空" }
        val oldEntry = oldDirName?.takeIf { it.isNotBlank() }?.let { dir ->
            ShareNoteTemplateManager.loadEntries().firstOrNull { it.dirName == dir }
        }
        val entry = ShareNoteTemplateManager.addOrUpdate(html, oldEntry)
        mapOf("saved" to true, "dirName" to entry.dirName, "name" to entry.meta.name)
    }

    // ---------------------------------------------------------------- 封面图集

    /** `cover_collection_list`：封面图集列表（`includeRemote=true` 时合并云端）。 */
    suspend fun coverCollectionList(
        isNight: Boolean = AppConfig.isNightTheme,
        includeRemote: Boolean = false,
    ): Map<String, Any?> = withContext(IO) {
        val selectedId = CoverCollectionManager.selectedEntry(isNight)?.collection?.id
        if (includeRemote) {
            val entries = CoverCollectionManager.loadEntries(isNight)
            mapOf(
                "isNight" to isNight,
                "selectedId" to selectedId,
                "total" to entries.size,
                "items" to entries.map {
                    mapOf(
                        "id" to it.collection.id,
                        "name" to it.collection.name,
                        "dirName" to it.dirName,
                        "mode" to it.collection.mode,
                        "imageCount" to it.collection.images.size,
                        "source" to it.source.name,
                        "updatedAt" to it.collection.updatedAt,
                        "selected" to (it.collection.id == selectedId),
                    )
                },
            )
        } else {
            val collections = CoverCollectionManager.load(isNight)
            mapOf(
                "isNight" to isNight,
                "selectedId" to selectedId,
                "total" to collections.size,
                "items" to collections.map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "dirName" to it.dirName,
                        "mode" to it.mode,
                        "imageCount" to it.images.size,
                        "source" to CoverCollectionManager.Source.LOCAL.name,
                        "updatedAt" to it.updatedAt,
                        "selected" to (it.id == selectedId),
                    )
                },
            )
        }
    }

    /**
     * `cover_collection_save`：封面图集操作。
     *
     * `action`：`create`（需 `name`）/ `rename`（需 `id` + `name`）/ `select`（需 `id`，切换当前）/ `delete`（需 `id`，危险操作）。
     */
    suspend fun coverCollectionSave(
        action: String,
        id: String? = null,
        name: String? = null,
        isNight: Boolean = AppConfig.isNightTheme,
    ): Map<String, Any?> = withContext(IO) {
        when (action) {
            "create" -> {
                val cleanName = name?.trim().orEmpty()
                require(cleanName.isNotBlank()) { "create 需要 name" }
                val collection = CoverCollectionManager.create(cleanName, isNight)
                mapOf("action" to action, "id" to collection.id, "name" to collection.name)
            }

            "rename" -> {
                val cleanId = id?.trim().orEmpty()
                val cleanName = name?.trim().orEmpty()
                require(cleanId.isNotBlank()) { "rename 需要 id" }
                require(cleanName.isNotBlank()) { "rename 需要 name" }
                val collection = CoverCollectionManager.get(isNight, cleanId)
                    ?: throw NoSuchElementException("封面图集不存在：$cleanId")
                val updated = CoverCollectionManager.rename(collection, cleanName)
                mapOf("action" to action, "id" to updated.id, "name" to updated.name)
            }

            "select" -> {
                val cleanId = id?.trim().orEmpty()
                require(cleanId.isNotBlank()) { "select 需要 id" }
                CoverCollectionManager.setSelected(isNight, cleanId)
                mapOf("action" to action, "selectedId" to cleanId, "isNight" to isNight)
            }

            "delete" -> {
                val cleanId = id?.trim().orEmpty()
                require(cleanId.isNotBlank()) { "delete 需要 id" }
                val collection = CoverCollectionManager.get(isNight, cleanId)
                    ?: throw NoSuchElementException("封面图集不存在：$cleanId")
                CoverCollectionManager.delete(collection)
                mapOf("action" to action, "deleted" to true, "id" to cleanId)
            }

            else -> throw IllegalArgumentException(
                "未知 action：$action（支持 create/rename/select/delete）"
            )
        }
    }

    // ---------------------------------------------------------------- 书籍展示侧规则（降级口径见类 KDoc 上限 2）

    /**
     * `book_info_layout_get`：书籍展示侧封面规则（`BookCover.CoverRule`）。
     *
     * 输出侧剔除 `header` / `jsLib` / `loginUi` / `loginUrl`（可携带凭据或脚本）。
     */
    suspend fun bookInfoLayoutGet(bookUrl: String): Map<String, Any?> = withContext(IO) {
        require(bookUrl.isNotBlank()) { "bookUrl 不能为空" }
        val rule = BookCover.getCoverRule()
        mapOf(
            "bookUrl" to bookUrl,
            "scope" to "global_cover_rule",
            "note" to "报告未核实按书的详情页布局定义源；此处返回全局 BookCover.CoverRule（全局规则，bookUrl 参数不参与定位）",
            "enable" to rule.enable,
            "searchUrl" to rule.searchUrl,
            "coverRule" to rule.coverRule,
            "concurrentRate" to rule.concurrentRate,
            "enabledCookieJar" to rule.enabledCookieJar,
        )
    }

    /** `book_info_layout_save`：保存全局封面规则（写 `BookCover.saveCoverRule`；危险操作，属性由工具层标记）。 */
    suspend fun bookInfoLayoutSave(config: BookCover.CoverRule): Map<String, Any?> = withContext(IO) {
        BookCover.saveCoverRule(config)
        mapOf(
            "saved" to true,
            "scope" to "global_cover_rule",
            "note" to "写入全局 BookCover.CoverRule（非按书）；如需恢复默认请用 BookCover.delCoverRule 入口",
        )
    }
}