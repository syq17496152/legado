package io.legado.app.help.source

import com.script.rhino.RhinoClassShutter
import io.legado.app.constant.SourceType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.SharedJsScope
import org.mozilla.javascript.Scriptable
import kotlin.coroutines.CoroutineContext

fun BaseSource.getShareScope(coroutineContext: CoroutineContext? = null): Scriptable? {
    return SharedJsScope.getScope(jsLib, coroutineContext)
        ?: if (jsLib.isNullOrBlank()) SharedJsScope.getCryptoScope(coroutineContext) else null
}

/**
 * P0-S4 类导入策略灰度：书源上下文启用 Rhino 类策略（书源模式）——
 * 宿主 App 类观察放行（D5）+ CookieManager/CookieSyncManager 实拦（D11）。
 * enabled=`this is BookSource`：RssSource/纯 JS 加密任务（cryptoScope）不启用，零行为变化。
 * sourceLabel=ns 短码（D16 脱敏），仅日志用途。
 */
fun <T> BaseSource?.withBookSourceClassPolicy(block: () -> T): T {
    val label = SourceSandboxExtensions.nsShortLabel(this)
    return RhinoClassShutter.withBookSourceClassPolicy(
        enabled = this is BookSource,
        sourceLabel = label.takeIf { it != "-" },
        block = block,
    )
}

fun BaseSource.getSourceType(): Int {
    return when (this) {
        is BookSource -> SourceType.book
        is RssSource -> SourceType.rss
        else -> error("unknown source type: ${this::class.simpleName}.")
    }
}
