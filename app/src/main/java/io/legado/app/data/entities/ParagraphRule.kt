package io.legado.app.data.entities

import android.os.Parcelable
import androidx.media3.common.C
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "paragraph_rules",
    indices = [Index(value = ["id"])]
)
data class ParagraphRule(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0L,
    @ColumnInfo(defaultValue = "")
    var name: String = "",
    @ColumnInfo(defaultValue = "")
    var jsLib: String = "",
    @ColumnInfo(defaultValue = "")
    var loginUrl: String = "",
    @ColumnInfo(defaultValue = "")
    var loginUi: String = "",
    @ColumnInfo(defaultValue = "0")
    var enabledCookieJar: Boolean = false,
    @ColumnInfo(defaultValue = "")
    var script: String = "",
    @ColumnInfo(defaultValue = "3000")
    var timeoutMillisecond: Long = 3000L,
    @ColumnInfo(name = "sortOrder", defaultValue = "0")
    var order: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var updateTime: Long = System.currentTimeMillis()
) : Parcelable {

    fun displayName(): String = name.ifBlank { "段落规则" }

    fun validTimeout(): Long = timeoutMillisecond.takeIf { it > 0 } ?: C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS

    /**
     * 内容相等判定（**忽略 `id` / `order` / `updateTime`**）——供编辑页「退出未保存拦截」判定是否有改动。
     *
     * 口径对齐同族实体（`BookSource.equal` / `RssSource.equal` / `HttpTTS.equal`）：
     * ①字符串按「null 与空串等价」比较，避免「原值为 null、控件回读为空串」造成**假脏**（每次退出都弹确认）；
     * ②超时按 [validTimeout] 比较——编辑页回填用的是 `validTimeout()` 的归一值，
     *   直接比原始 `timeoutMillisecond` 会让「库里存的 ≤0 值」被归一后判成改动。
     */
    fun equal(rule: ParagraphRule): Boolean {
        return equal(name, rule.name)
                && equal(jsLib, rule.jsLib)
                && equal(loginUrl, rule.loginUrl)
                && equal(loginUi, rule.loginUi)
                && enabledCookieJar == rule.enabledCookieJar
                && equal(script, rule.script)
                && validTimeout() == rule.validTimeout()
    }

    private fun equal(a: String?, b: String?) = a == b || (a.isNullOrEmpty() && b.isNullOrEmpty())
}
