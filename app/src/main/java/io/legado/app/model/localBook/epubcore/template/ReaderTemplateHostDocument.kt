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

    /**
     * **首分页异步骨架** 的 id（4.19 / AD-33）。
     *
     * 为什么需要：沙箱建帧 → 注入正文 → 分页结算有几百毫秒的窗口（实测首屏 ≈300ms，
     * 重模板更长）。这段时间里宿主文档是**空白**的（比"旧页面"更糟：用户看到闪白）。
     * 骨架提供"正在排版"的确定观感，且在首个 `rendered` 回调时**由 Kotlin 主动移除**
     * （不做 CSS 淡出：淡出会在"已可见但仍在动"的窗口里与分页重排叠加，反而制造抖动）。
     */
    const val SkeletonId = "legado-template-skeleton"

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
        // 沙箱挂载容器：**必须用视口单位定高**（`100vh`），不能用 `height:100%`/inset 百分比链——
        // 实测在 Compose `AndroidView` 承载的弹窗里，宿主视口是正常的 380，但容器 `height:100%` 解析成 0
        // （宽度 478 正常、高度 0）⇒ iframe 高 0 ⇒ 沙箱视口 0 ⇒ 页框算成负数 ⇒ "预览空白且页数恒 1"。
        // `100vh` 直接取宿主视口高，不依赖任何祖先高度是否"确定"（2026-10-10 真机铁证）。
        append("#").append(ContainerId)
            .append("{position:absolute;left:0;top:0;width:100%;height:100vh}")
        // 骨架样式：只用**灰度块 + 透明度**表达"正在排版"，不含任何动效
        // （带动效的骨架会与"分页重排"叠加成抖动，且违反 reduce-motion 克制原则）
        append("#").append(SkeletonId)
            .append("{position:absolute;left:0;top:0;width:100%;height:100vh;box-sizing:border-box;")
            .append("padding:12% 8%;background:transparent;color:inherit;opacity:.32}")
        append("#").append(SkeletonId).append(" i")
            .append("{display:block;height:12px;margin:0 0 14px;border-radius:6px;background:currentColor;opacity:.35}")
        append("#").append(SkeletonId).append(" i:nth-child(2)")
            .append("{width:72%}")
        append("#").append(SkeletonId).append(" i:nth-child(3)")
            .append("{width:88%}")
        append("#").append(SkeletonId).append(" i:nth-child(4)")
            .append("{width:64%}")
        append("</style>")
        append("</head><body>")
        // 骨架在**容器之前**：沙箱帧一旦渲染即覆盖它，不必等移除命令到达
        append("<div id=\"").append(SkeletonId).append("\"><i></i><i></i><i></i><i></i></div>")
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

    /**
     * 显隐首分页骨架（4.19）。宿主在首个 `rendered` 回调后置 `false`；失败回落 canvas 时也置 `false`
     * （否则骨架会留在屏幕上冒充"内容正在加载"）。
     */
    fun skeletonScript(visible: Boolean): String {
        val display = if (visible) "block" else "none"
        val state = if (visible) "on" else "off"
        return "(function(){var s=document.getElementById('$SkeletonId');" +
            "if(!s)return 'no-skeleton';s.style.display='$display';return 'skeleton-$state';})()"
    }

    /**
     * 容器定尺寸脚本：按宿主文档视口以 **px 显式值**定尺寸，并**回发几何自证**。
     *
     * 为什么用 px 而不是 `100%`/`100vh`：真机实测该 WebView（Compose `AndroidView` 承载）里两者都会塌成 0
     * （容器自身的 vh/百分比高度也塌）⇒ 沙箱视口 0 ⇒ 页框算成负数 ⇒ "预览空白且页数恒 1"。
     *
     * 为什么必须回发几何（2026-10-14 用户报「屏幕左右有黑边」）：真机截图上 WebView 布局框已铺满
     * `[0,0][720,1280]`，但画面左缘仍有 4 物理 px 露出引擎底色 ⇒ 缺口不在 WebView 而在**文档内部某层**。
     * 只有把宿主视口宽（`clientWidth` 与 `innerWidth` 之差即滚动条占宽）、`body` 实测宽、容器与沙箱帧
     * 各自的 `left/width` 一并回发，才能判定缺口发生在视口、容器还是帧，不必再靠截图反推
     * （本项目已多次栽在"看起来是引擎没铺满"的误判上）。
     */
    fun containerSizeScript(): String =
        "(function(){var d=document,f=d.getElementById('$ContainerId');if(!f)return 'no-container';" +
            "f.style.position='absolute';f.style.left='0';f.style.top='0';" +
            "f.style.width=d.documentElement.clientWidth+'px';" +
            "f.style.height=d.documentElement.clientHeight+'px';" +
            "var fr=f.firstElementChild,cr=f.getBoundingClientRect()," +
            "rr=fr?fr.getBoundingClientRect():null;" +
            "return 'sized|cw='+d.documentElement.clientWidth+'/iw='+window.innerWidth" +
            "+',cw2='+d.body.clientWidth+'/bw='+d.body.getBoundingClientRect().width" +
            "+',cr='+(cr.left+','+cr.width.toFixed(1))" +
            "+',fr='+(rr?rr.left+','+rr.width.toFixed(1):'none');})()"

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