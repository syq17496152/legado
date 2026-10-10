package io.legado.app.help.md

/**
 * 模板**沙箱文档**（`iframe.srcdoc`）的纯字符串构造器（epub-md-rich-rendering 阶段 4.14）。
 *
 * ### 为什么需要它
 * 沙箱 iframe 是 `sandbox="allow-scripts"` 且**不带同源标记** ⇒ 宿主**无法**向它
 * `evaluateJavascript`、也读不到它的 DOM。因此沙箱文档必须**自带全部脚本**，
 * 且只能用消息通道与宿主交互（见 `assets/md/template-host.js` / `template-runtime.js`）。
 *
 * ### 段落顺序（**顺序即正确性**，逐条都有实证过的失守方式）
 * 1. `template-browser-flow.js` —— **必须最先**：`template-runtime.js` 在**加载期**执行
 *    `var flow = window.ReaderTemplateFlow || {空实现}`，顺序颠倒 ⇒ 分页永远走空实现
 *    （症状：模板渲染正常但页数恒为 1，且没有任何报错）；
 * 2. `template-runtime.js` —— 渲染作者 HTML/CSS、注入槽位与正文；
 * 3. **厂商 JS 各自独立 `<script>`**（mermaid / KaTeX / hljs）—— 与阶段 3.8 的真机铁证同理：
 *    厂商脚本尾部依赖**顶层作用域**的 `var`，一旦被包进任何函数/IIFE 即崩溃失效；
 *    独立 `<script>` 各自是独立顶层程序 ⇒ 合规；
 * 4. 注入器**运行时**（IIFE，暴露可重复运行入口 `status.run`）—— 在沙箱文档里执行 ⇒
 *    它渲染的正是模板正文（单源，不另写一套）；
 * 5. **bootstrap** —— 把宿主的 `init` 转发给沙箱运行时（`template-runtime.js` 的
 *    `handleMessage` 只接受 `RECEIVE_TYPES`（inject-mermaid / remeasure / set-theme），**`init` 会被它丢弃**）。
 *
 * ### 安全
 * 所有脚本体经 [escapeScriptBody] 处理（`</` → `<\/`）：否则脚本字符串里的 `</script>`
 * 会提前闭合标签并把后续内容变成 HTML（即"作者内容变成宿主文档结构"）。该替换在 JS 里等价
 * （字符串内的 `\/` 就是 `/`；正则内的 `\/` 同样是转义斜杠），且**不改变语义**。
 *
 * 纯字符串构造（无 Android 依赖）⇒ 段落顺序/转义/无宿主对象引用可在 JVM 单测逐条断言。
 */
object ReaderTemplateSandboxDocument {

    /** 模板根节点 id（与 `template-browser-flow.js` / `template-runtime.js` 约定一致）。 */
    const val RootId = "reader-template-root"

    data class Scripts(
        /** `template-browser-flow.js` 原文（**必须先于** runtime）。 */
        val flowJs: String? = null,
        /** `template-runtime.js` 原文。 */
        val runtimeJs: String? = null,
        /** 厂商 JS 原文列表（mermaid / KaTeX / hljs），**逐条独立 `<script>`**。 */
        val vendorScripts: List<String> = emptyList(),
        /** 注入器运行时（[MdRichRenderInjector.runtimeScript] 的产物）。 */
        val injectorRuntimeJs: String? = null,
        /** 注入器 CSS（KaTeX / 代码高亮样式）。 */
        val injectorCss: String = ""
    )

    fun build(scripts: Scripts): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        if (scripts.injectorCss.isNotBlank()) {
            append("<style id=\"legado-md-vendor-css\">").append(scripts.injectorCss).append("</style>")
        }
        append("</head><body>")
        // 1 → 2：flow 在前（runtime 加载期即捕获 window.ReaderTemplateFlow）
        appendScript(scripts.flowJs)
        appendScript(scripts.runtimeJs)
        // 3：厂商脚本逐条独立标签（绝不可合并、绝不可包 IIFE）
        scripts.vendorScripts.forEach { appendScript(it) }
        // 4：注入器运行时（沙箱内渲染模板正文）
        appendScript(scripts.injectorRuntimeJs)
        // 5：bootstrap（转发 init）
        appendScript(bootstrapScript())
        append("</body></html>")
    }

    /**
     * 沙箱 bootstrap：**只**把宿主的 `init` 交给沙箱运行时。
     *
     * 不注册 `addJavascriptInterface`、不引宿主任一对象（AD-23）；失败时经 runtime 的既有通道回发。
     */
    fun bootstrapScript(): String = buildString {
        append("(function(){")
        append("'use strict';")
        append("if(window.__legadoTemplateInitForwarded){return;}")
        append("window.__legadoTemplateInitForwarded=true;")
        append("window.addEventListener('message',function(event){")
        append("var data=event&&event.data;")
        append("if(!data||typeof data!=='object')return;")
        // 只认 init：其余类型由 runtime 自己处理（避免双重处理）
        append("if(data.type!=='init')return;")
        append("if(!window.ReaderTemplateRuntime)return;")
        append("try{window.ReaderTemplateRuntime.init(data);}")
        append("catch(error){")
        append("try{window.ReaderTemplateRuntime.send('error',{code:'template-init-failed',message:String((error&&error.message)||error)});}catch(ignored){}")
        append("}});")
        append("})();")
    }

    /** 脚本体转义：`</` → `<\/`（防止提前闭合 `<script>`，语义等价）。 */
    fun escapeScriptBody(js: String): String = js.replace("</", "<\\/")

    private fun StringBuilder.appendScript(js: String?) {
        if (js.isNullOrBlank()) return
        append("<script>").append(escapeScriptBody(js)).append("</script>")
    }
}