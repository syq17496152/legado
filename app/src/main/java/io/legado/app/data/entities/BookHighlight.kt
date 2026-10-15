package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * 正文高亮(手动划线)
 * 位置语义: [chapterPos, chapterPosEnd) 半开区间, 与 createBookmark 同一套口径
 * 样式存储: style = HighlightStyle 的 JSON(见 [HighlightStyle])
 *
 * [chapterUrl] 是**跨目录重排的稳定锚**: [chapterIndex] 会随目录更新插章 / 切换「反转目录」
 * 整体平移, 只按 index 定位会让划线落到错误章节; 载入时先按 [chapterUrl] 找回正确章节,
 * 失败再回退按 [chapterName] 匹配(见 `model/HighlightSyncPolicy`)。
 * 老数据该列为空 ⇒ 载入时按当前位置一次性回填后即受锚保护。
 */
@Parcelize
@Entity(
    tableName = "highlights",
    indices = [(Index(value = ["bookName", "bookAuthor"], unique = false))]
)
data class BookHighlight(
    @PrimaryKey
    val time: Long = System.currentTimeMillis(),
    val bookName: String = "",
    val bookAuthor: String = "",
    var chapterIndex: Int = 0,
    var chapterPos: Int = 0,
    var chapterPosEnd: Int = 0,
    var chapterName: String = "",
    // 与 migration_113_114 的 `DEFAULT ''` 成对声明: Room 运行时 schema 校验会比对 defaultValue
    @ColumnInfo(defaultValue = "")
    var chapterUrl: String = "",
    var bookText: String = "",
    var style: String = "",
    var note: String = ""
) : Parcelable {

    @IgnoredOnParcel
    @Ignore
    @Transient
    private var styleCache: HighlightStyle? = null

    /** 解析后的样式(惰性缓存) */
    fun styleObj(): HighlightStyle {
        styleCache?.let { return it }
        // D4 修复：GSON 反序列化缺失字段会写入 null（fontPath 等），直接缓存后 copy() 会抛 NPE
        // → 必须经 sanitized() 归一化再缓存
        return (GSON.fromJsonObject<HighlightStyle>(style).getOrNull() ?: HighlightStyle())
            .sanitized()
            .also { styleCache = it }
    }

    /** 写入样式并同步 JSON 列 */
    fun applyStyle(s: HighlightStyle) {
        styleCache = s
        style = GSON.toJson(s)
    }
}
