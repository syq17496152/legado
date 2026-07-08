package io.legado.app.data.entities

import android.os.Parcelable
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
        return (GSON.fromJsonObject<HighlightStyle>(style).getOrNull() ?: HighlightStyle())
            .also { styleCache = it }
    }

    /** 写入样式并同步 JSON 列 */
    fun applyStyle(s: HighlightStyle) {
        styleCache = s
        style = GSON.toJson(s)
    }
}
