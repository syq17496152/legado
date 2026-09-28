package io.legado.app.data.entities

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * W8 / REQ-31（AD-12）：**名场面书签**（Scene Bookmark）。
 *
 * 三路径统一实体，`contentKind` 为**跳转路由依据**：
 *  · `0` 文字（`ReadBookActivity` 划词菜单）—— `anchor = {"chapterPos":N}`，[text] = 选中文本
 *  · `1` 漫画（`ReadMangaActivity` 长按菜单）—— `anchor = {"pageIndex":N}`
 *  · `2` 图片订阅（`ImageGalleryActivity` 菜单）—— `anchor = {"imageUrl":"..."}`
 *
 * [desc] / [tags] 由 `help/ai/AiSceneDescService` 异步生成（AI 未配置/超时/解析失败时降级为
 * 手写备注或原文截断 ⇒ **核心链路零 AI 依赖**）；[tags] 存 JSON 数组字符串。
 *
 * ⚠ 字段**全部有默认值**（Room 实体约定）；`@Keep` 防 R8 剥离成员（AGENTS.md 规则 7：
 * 本实体既经 Room 反射、又可能经 GSON/备份链路读回）。
 */
@Keep
@Parcelize
@Entity(tableName = "sceneBookmarks")
data class SceneBookmark(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    /** 创建时间（毫秒，对齐 `System.currentTimeMillis()`） */
    var time: Long = System.currentTimeMillis(),
    /** 书/源标识：本地书 path 或书源 bookUrl / 订阅源 sourceUrl */
    var bookUrl: String = "",
    var bookName: String = "",
    var bookAuthor: String = "",
    /** 章节下标（文字/漫画路径） */
    var chapterIndex: Int = 0,
    var chapterName: String = "",
    /** 0=文字 1=漫画 2=图片订阅 */
    var contentKind: Int = 0,
    /** 路由锚点 JSON（见类注释） */
    var anchor: String = "",
    /** 文字路径：选中文本（同时作为 AI 上下文） */
    var text: String = "",
    /** AI 生成或用户手写描述 */
    var desc: String = "",
    /** 标签 JSON 数组，如 `["燃","转折"]` */
    var tags: String = "[]",
    /** 预留：展示样式扩展 */
    var style: String = ""
) : Parcelable
