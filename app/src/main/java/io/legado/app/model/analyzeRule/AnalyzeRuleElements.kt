package io.legado.app.model.analyzeRule

/**
 * IF-01（fix-rss-json-js-parse）：把「列表规则返回的 JSON 数组字符串」解析为元素列表。
 *
 * 背景：`AnalyzeRule.getElements` 对规则返回的 `String` 原为 `listOf(it)`（恒 **1 个元素**），
 * 因此 `<js>` 里 `JSON.stringify(list)` 这类「解密后合成列表」写法恒取不到数据
 * （注：脚本**直接返回 JS 数组**不受影响——Rhino 的 `NativeArray` 本身实现 `java.util.List`，
 * 走 `is List<*>` 分支正常展开）。
 *
 * 契约（只展开数组，不猜测 HTML 结构）：
 * - `trim()` 后以 `[` 开头、`]` 结尾且为**合法 JSON 数组** ⇒ 返回解析后的元素列表（空数组返回空表）；
 * - 未命中（HTML 片段 / 纯文本 / 形似数组但非法 JSON）⇒ 返回 `null`，由调用方保持既有 `listOf(it)` 语义，
 *   保证存量源行为零变化。
 *
 * 纯函数：JSON 解析失败在构造期抛出并被 `runCatching` 吞掉，无 Android 运行期依赖，可 JVM 单测覆盖。
 */
internal fun jsonArrayStringToList(result: String): List<Any>? {
    val trimmed = result.trim()
    if (trimmed.length < 2 || !trimmed.startsWith("[") || !trimmed.endsWith("]")) {
        return null
    }
    return kotlin.runCatching { AnalyzeByJSonPath(trimmed).getList("$") }.getOrNull()
}
