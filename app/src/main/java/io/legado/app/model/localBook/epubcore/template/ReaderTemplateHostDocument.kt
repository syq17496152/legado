package io.legado.app.model.localBook.epubcore.template

/**
 * 模板**宿主文档**构造器（epub-md-rich-rendering 阶段 4.14 / AD-23 宿主侧）。
 *
 * 宿主 WebView 载入的**不是**模板本身，而是承载 `template-host.js` 的**空壳文档**：
 * 它只提供一个容器节点，由 `ReaderTemplateHost.init(...)` 在容器内创建**沙箱 iframe**
 * （`sandbox="allow-scripts"`、不带同源标记）。这样作者代码始终隔离在帧内，
 * 宿主文档里跑的只有我们自己的 `template-host.js`。
 *
 * ### 两段桥（本文件负责第一段）
 * | 段 | 通道 | 校验 |
 * |---|---|---|
 * | Kotlin ↔ 宿主文档 | `WebViewCompat.addWebMessageListener`（**禁** `addJavascriptInterface`） | [ReaderTemplateBridgePolicy] |
 * | 宿主文档 ↔ 沙箱 iframe | `window.postMessage` | `template-host.js` 前置粗筛 + Kotlin 权威校验 |
 *
 * ### 为什么 `onMessage` 必须由这里拼
 * `init` 的配置里 `onMessage` 是**函数**，JSON 表达不了 ⇒ 只能由构造器把函数字面量拼进去
 * （函数体固定为"把消息转投 WebMessagePort"，作者代码无法改写它，因为它在宿主文档作用域）。
 *
 * 纯字符串构造 ⇒ 转义/拼接正确性可在 JVM 单测逐条断言。
 */
object ReaderTemplateHostDocument {

    /** 沙箱 iframe 的挂载容器 id。 */
    const val ContainerId = "legado-template-container"

    /** 宿主文档里暴露给 `init` 的回调全局名（函数体由 [bridgePostScript] 定义）。 */
    const val BridgePostGlobal = "__legadoTemplatePost"

    /** 可见 WebView 的基址（`loadDataWithBaseURL` 用；决定 `addWebMessageListener` 的 origin 规则）。 */
    const val BaseUrl = "https://legado.template.local/"

    /** 宿主文档：空壳 + 容器 + `template-host.js` + 回调定义。 */
    fun build(hostJs: String): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        // 宿主文档自身不滚动：滚动/分页由沙箱内部的视口承担（模板契约 data-reader-scroll-viewport）
        append("<style>html,body{margin:0;padding:0;height:100%;overflow:hidden;background:transparent}")
        append("#").append(ContainerId).append("{position:absolute;left:0;top:0;right:0;bottom:0}</style>")
        append("</head><body>")
        append("<div id=\"").append(ContainerId).append("\"></div>")
        appendScript(hostJs)
        appendScript(bridgePostScript())
        append("</body></html>")
    }

    /**
     * 回调定义：把沙箱消息经 WebMessagePort 转投 Kotlin。
     *
     * 只在 [BridgePostGlobal] 未定义时定义（幂等：文档重载/重复注入不叠加）。
     */
    fun bridgePostScript(): String = buildString {
        append("(function(){")
        append("if(window.").append(BridgePostGlobal).append(")return;")
        append("var bridge=window.").append(WebMessageBridgeObjectName).append(";")
        append("if(!bridge||typeof bridge.postMessage!=='function')return;")
        append("window.").append(BridgePostGlobal).append("=function(message){")
        append("try{bridge.postMessage(JSON.stringify(message||{}));}catch(error){}};")
        append("})();")
    }

    /**
     * `init` 调用脚本。
     *
     * @param payloadJson 配置对象的 JSON（**不含** `onMessage`；容器/沙箱文档/模板/正文/主题等）。
     */
    fun initScript(payloadJson: String): String {
        val trimmed = payloadJson.trim()
        require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
            "init 配置必须是 JSON 对象"
        }
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        val body = if (inner.isEmpty()) {
            "onMessage:" + BridgePostGlobal
        } else {
            inner + ",onMessage:" + BridgePostGlobal
        }
        return "window.ReaderTemplateHost.init({$body});"
    }

    /** 向沙箱下发指令（`post` 的放行类型由 `template-host.js` 与桥策略双侧约束）。 */
    fun postScript(type: String, payloadJson: String? = null): String {
        val payload = payloadJson?.trim()?.takeIf { it.startsWith("{") && it.endsWith("}") }
        return if (payload == null) {
            "window.ReaderTemplateHost.post('$type');"
        } else {
            "window.ReaderTemplateHost.post('$type',$payload);"
        }
    }

    fun destroyScript(): String = "window.ReaderTemplateHost.destroy();"

    /** 沙箱文档是否已在容器内（宿主文档侧事实，用于诊断与就绪判定）。 */
    fun sandboxProbeScript(): String =
        "(function(){var c=document.getElementById('$ContainerId');" +
            "var f=c&&c.firstElementChild;" +
            "return [!!f,window.ReaderTemplateHost?'1':'0'].join('|');})()"

    /**
     * 把 JSON 文本安全地内联进 JS 字面量。
     *
     * `\u2028` / `\u2029` 在 JSON 字符串里合法，但在 ES2019 之前是**行终止符** ⇒
     * 内联进 `evaluateJavascript` 会造成"脚本莫名语法错误"（正文里出现这两个字符完全可能）。
     */
    fun escapeForJsLiteral(json: String): String = json
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    /**
     * WebMessagePort 对象名。
     *
     * 与 [ReaderTemplateHostDocument] 的文档脚本共用；由宿主经
     * `WebViewCompat.addWebMessageListener(webView, name, origins, listener)` 注入。
     */
    const val WebMessageBridgeObjectName = "legadoTemplateBridge"

    /**
     * 内联 JS 的**标签内**转义（与 `ReaderTemplateSandboxDocument.escapeScriptBody` 同口径）：
     * `</` 防提前闭合；`<!--` 防解析器进入 "script data escaped" 态（进入后 `</script>` 不闭合，
     * 后续标签文本变成 JS 语法错误 ⇒ 整段脚本不执行、宿主文档静默失效）。
     */
    fun escapeForScriptTag(js: String): String = js
        .replace("</", "<\\/")
        .replace("<!--", "<\\!--")

    private fun StringBuilder.appendScript(js: String?) {
        if (js.isNullOrBlank()) return
        append("<script>").append(escapeForScriptTag(js)).append("</script>")
    }
}