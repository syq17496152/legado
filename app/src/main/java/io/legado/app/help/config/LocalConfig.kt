package io.legado.app.help.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.utils.getBoolean
import io.legado.app.utils.putBoolean
import io.legado.app.utils.putLong
import io.legado.app.utils.putString
import io.legado.app.utils.remove
import splitties.init.appCtx

@Suppress("ConstPropertyName")
object LocalConfig : SharedPreferences
by appCtx.getSharedPreferences("local", Context.MODE_PRIVATE) {

    private const val versionCodeKey = "appVersionCode"

    /**
     * 本地密码,用来对需要备份的敏感信息加密,如 webdav 配置等
     */
    var password: String?
        get() = getString("password", null)
        set(value) {
            if (value != null) {
                putString("password", value)
            } else {
                remove("password")
            }
        }

    var lastBackup: Long
        get() = getLong("lastBackup", 0)
        set(value) {
            putLong("lastBackup", value)
        }

    var privacyPolicyOk: Boolean
        get() = getBoolean("privacyPolicyOk")
        set(value) {
            putBoolean("privacyPolicyOk", value)
        }

    val readHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readHelpVersion", "firstRead")

    val backupHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "backupHelpVersion", "firstBackup")

    val readMenuHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readMenuHelpVersion", "firstReadMenu")

    val bookSourcesHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "bookSourceHelpVersion", "firstOpenBookSources")

    val webDavBookHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "webDavBookHelpVersion", "firstOpenWebDavBook")

    val ruleHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "ruleHelpVersion")

    val needUpHttpTTS: Boolean
        get() = !isLastVersion(6, "httpTtsVersion")

    val needUpTxtTocRule: Boolean
        get() = !isLastVersion(4, "txtTocRuleVersion")

    val needUpRssSources: Boolean
        get() = !isLastVersion(7, "rssSourceVersion")

    val needUpDictRule: Boolean
        get() = !isLastVersion(2, "needUpDictRule")

    /** E4/F-2：选角模板内置导入旗标（per-key 独立计数器，新键从 1 起语义） */
    val needUpTtsCastingTemplates: Boolean
        get() = !isLastVersion(1, "ttsCastingTemplatesVersion")

    /** F4/4.4：内置高亮规则版本旗标（高亮为 SP 存储无 Room 旗标链；新增内置规则时 bump 推送老用户） */
    val needUpHighlightRules: Boolean
        get() = !isLastVersion(1, "highlightRuleVersion")

    // builtin-replace-id-fix：替换净化内置规则旗标已删除——isLastVersion「读后即写回」语义存在
    // 评估与执行非原子的机会丢失窗口（实测踩坑），改为 DefaultData.upVersion 每次启动无旗标幂等同步。
    // 遗留 SP 键 replaceRuleVersion 不再读取，无需清理（Room 无关，仅 SP 冗余）。

    var versionCode
        get() = getLong(versionCodeKey, 0)
        set(value) {
            edit { putLong(versionCodeKey, value) }
        }
    var lastCheckUpdate: Long
        get() = getLong("lastCheckUpdate", 0)
        set(value) {
            putLong("lastCheckUpdate", value)
        }

    val isFirstOpenApp: Boolean
        get() {
            val value = getBoolean("firstOpen", true)
            if (value) {
                edit { putBoolean("firstOpen", false) }
            }
            return value
        }

    @Suppress("SameParameterValue")
    private fun isLastVersion(
        lastVersion: Int,
        versionKey: String,
        firstOpenKey: String? = null
    ): Boolean {
        var version = getInt(versionKey, 0)
        if (version == 0 && firstOpenKey != null) {
            if (!getBoolean(firstOpenKey, true)) {
                version = 1
            }
        }
        if (version < lastVersion) {
            edit { putInt(versionKey, lastVersion) }
            return false
        }
        return true
    }

    var bookInfoDeleteAlert: Boolean
        get() = getBoolean("bookInfoDeleteAlert", true)
        set(value) {
            putBoolean("bookInfoDeleteAlert", value)
        }

    var deleteBookOriginal: Boolean
        get() = getBoolean("deleteBookOriginal")
        set(value) {
            putBoolean("deleteBookOriginal", value)
        }

    var appCrash: Boolean
        get() = getBoolean("appCrash")
        set(value) {
            putBoolean("appCrash", value)
        }

    /**
     * RssFree 自由布局诊断日志全量开关（compose-shell-binding-fix，默认关）。
     * 默认下 [RssFreeGridLayoutManager] 常规逐帧状态走 2000ms 窗口节流；
     * 专项排查自由布局时打开恢复全量。
     * ⚠️ 该开关供 LayoutManager 布局路径消费，读取方必须实例级缓存，
     * 禁止每帧调用 getter（底层 CacheManager 为 runBlocking(IO)）。
     */
    var rssFreeFullLog: Boolean
        get() = getBoolean("rssFreeFullLog")
        set(value) {
            putBoolean("rssFreeFullLog", value)
        }

}
