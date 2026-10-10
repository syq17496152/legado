package io.legado.app.help.md

/**
 * Markdown 富渲染注入器（epub-md-rich-rendering 阶段 3.8 / 3.9）。
 *
 * **职责**：为「文本渲染文档」与（阶段 4 的）模板沙箱文档生成**注入片段**——
 * KaTeX/mermaid/代码高亮的初始化 JS、渲染完成信号、失败降级。**纯字符串构造**（无 Android 依赖）
 * ⇒ 安全选项与降级分支可在 JVM 单测中逐条断言；真正的渲染行为由真机 L2 覆盖。
 *
 * 安全与稳定性契约（逐条对 AD-22 与审计 SEC/PERF 项）：
 * - mermaid：`startOnLoad:false`（由我们在 DOM/字体就绪后显式 `run()`）、`securityLevel:'strict'`
 *   （禁脚本与外部资源）、`htmlLabels:false`（禁 HTML 标签注入，也避免 foreignObject 在旧内核异常）、
 *   `run({suppressErrors:true})`（单图失败不中断整页）；
 * - KaTeX：`trust:false`（拒 `\href`/`\htmlClass` 等）、`maxSize` / `maxExpand`（防巨型公式与宏展开炸弹）、
 *   `strict:'warn'`、`throwOnError:false`（错误渲染为红色文本而非抛异常）；
 * - **降级**：`katex`/`mermaid` 缺失（旧内核注入失败）或单点异常 ⇒ 该处保留原文本并加
 *   `md-rich-fallback` 类（可见的降级外观），**不白屏、不中断**；
 * - **二次渲染不可重复**：渲染过的节点打 `data-md-rendered` 标记；同源文本 hash 命中时跳过
 *   （`hash` 由宿主传入，宿主负责跨页面缓存）。
 */
object MdRichRenderInjector {

    /** 资产路径（相对 `assets/`），与阶段 0 导入的 vendor 目录保持一致。 */
    const val MdReaderCssAsset = "md/md-reader.css"
    const val MermaidAsset = "md/vendor/mermaid.min.js"
    const val KatexAsset = "md/vendor/katex.min.js"
    const val KatexCssAsset = "md/vendor/katex.min.css"
    const val HighlightAsset = "md/vendor/highlight.min.js"
    const val HighlightLightCssAsset = "md/vendor/hljs-light.min.css"
    const val HighlightDarkCssAsset = "md/vendor/hljs-dark.min.css"

    /**
     * 模板**装饰层基线 CSS**（epub-md-rich-rendering 阶段 4.8d）：
     * 按 `<html>` 上的 `data-rp-decoration` / `data-reader-motion` 施加装饰强度与动效闸门。
     * 只在模板沙箱文档中挂载（普通 md 富渲染面没有模板装饰层，挂上也是死规则）。
     */
    const val TemplateDecorationCssAsset = "md/template-decoration.css"

    /** 渲染完成信号：宿主在 `onPageFinished` 后轮询该全局变量（避免竞态）。 */
    const val StatusGlobal = "__legadoMdRichRender"

    /** 已渲染标记属性（防二次渲染）。 */
    const val RenderedAttribute = "data-md-rendered"

    data class Assets(
        val mermaidJs: String? = null,
        val katexJs: String? = null,
        val katexCss: String? = null,
        val highlightJs: String? = null,
        val highlightCss: String? = null
    )

    data class Options(
        val renderMermaid: Boolean = true,
        val renderMath: Boolean = true,
        val highlightCode: Boolean = true,
        /** mermaid 主题（跟随阅读主题：default / dark）。 */
        val mermaidTheme: String = MermaidThemeDefault,
        /** KaTeX 单点渲染上限（防巨型公式卡死）。 */
        val katexMaxSize: Int = DefaultKatexMaxSize,
        /** 宏展开上限（防 `\def` 炸弹）。 */
        val katexMaxExpand: Int = DefaultKatexMaxExpand,
        /** 渲染总超时（毫秒）：超时即视为完成并回报，避免分页永久等待。 */
        val timeoutMillis: Long = DefaultTimeoutMillis
    )

    data class Injection(
        val css: String,
        val script: String
    ) {
        val isEmpty: Boolean get() = css.isBlank() && script.isBlank()
    }

    /**
     * 生成注入片段。
     *
     * @param needsMermaid 构建期标注（`MdDocumentBuilder.Built.hasMermaid`）⇒ 无 mermaid 时不注入 2.4MB 运行时。
     * @param needsMath 构建期标注（`hasMath`）⇒ 无公式时不注入 KaTeX。
     */
    fun build(
        options: Options,
        assets: Assets,
        needsMermaid: Boolean,
        needsMath: Boolean
    ): Injection {
        val wantMermaid = options.renderMermaid && needsMermaid && !assets.mermaidJs.isNullOrBlank()
        val wantMath = options.renderMath && needsMath && !assets.katexJs.isNullOrBlank()
        val wantHighlight = options.highlightCode && !assets.highlightJs.isNullOrBlank()
        val css = buildString {
            if (wantMath) append(assets.katexCss.orEmpty())
            if (wantHighlight) append(assets.highlightCss.orEmpty())
        }
        if (!wantMermaid && !wantMath && !wantHighlight) {
            return Injection(css = css, script = "")
        }
        val script = vendorScripts(wantMermaid, wantMath, wantHighlight, assets).joinToString("") +
            runtimeScript(options, wantMermaid, wantMath, wantHighlight)
        return Injection(css = css, script = script)
    }

    /**
     * 厂商运行时脚本（**必须各自作为顶层脚本执行**，绝不能包进函数或 IIFE）。
     *
     * ⚠️ 硬事实（2026-10-10 真机铁证）：`mermaid.min.js` 是 esbuild 打包的经典脚本，形如
     * `"use strict";var __esbuild_esm_mermaid=(()=>{...})()`，其**尾部**再读
     * `globalThis.__esbuild_esm_mermaid.default` 来挂 `window.mermaid`。这个 `var` 依赖
     * **顶层（全局）作用域**；一旦被包进 `(function(){ ... })()`，`var` 变成函数作用域 ⇒ 尾部读到
     * `undefined` ⇒ `TypeError: Cannot read properties of undefined (reading 'default')`，注入整体失效。
     * 因此宿主必须把它们作为**独立顶层脚本**逐条执行（`evaluateJavascript` 或独立 `<script>`）。
     */
    fun vendorScripts(
        wantMermaid: Boolean,
        wantMath: Boolean,
        wantHighlight: Boolean,
        assets: Assets
    ): List<String> = buildList {
        if (wantMermaid) assets.mermaidJs?.takeIf { it.isNotBlank() }?.let(::add)
        if (wantMath) assets.katexJs?.takeIf { it.isNotBlank() }?.let(::add)
        if (wantHighlight) assets.highlightJs?.takeIf { it.isNotBlank() }?.let(::add)
    }

    /**
     * 运行时与驱动脚本（**不含**厂商 JS 本体；须在 [vendorScripts] 之后执行）。
     *
     * 与 [build] 的关系：`build()` = 厂商脚本 + 本函数，二者拼接仅为向后兼容；
     * 正确用法是宿主把两者**分开**顶层执行（见 [vendorScripts] 的说明）。
     */
    fun runtimeScript(
        options: Options,
        wantMermaid: Boolean,
        wantMath: Boolean,
        wantHighlight: Boolean
    ): String = buildString {
        append("(function(){")
        append("var status={done:false,mermaid:0,math:0,code:0,error:null};")
        append("window.$StatusGlobal=status;")
        append("function markFallback(el,kind){")
        append("if(!el||!el.classList)return;")
        append("el.classList.add('md-rich-fallback');")
        append("el.setAttribute('data-md-fallback',kind);")
        append("}")
        if (wantMermaid) append(buildMermaidRuntime(options))
        if (wantMath) append(buildKatexRuntime(options))
        if (wantHighlight) append(buildHighlightRuntime())
        append(buildDriver(wantMermaid, wantMath, wantHighlight, options.timeoutMillis))
        append("})();")
    }

    /** 把注入片段包成可直接追加到文档末尾的 HTML（宿主只需 `appendChild`/字符串拼接）。 */
    fun wrapHtml(injection: Injection): String {
        return buildString {
            if (injection.css.isNotBlank()) {
                append("<style id=\"legado-md-vendor-css\">").append(injection.css).append("</style>")
            }
            if (injection.script.isNotBlank()) {
                append("<script id=\"legado-md-rich-render\">").append(injection.script).append("</script>")
            }
        }
    }

    // === 运行时构造 ===

    private fun buildMermaidRuntime(options: Options): String = buildString {
        append("function runMermaid(){")
        append("if(!window.mermaid){markFallback(document.body,'mermaid-missing');return Promise.resolve();}")
        append("try{")
        append("mermaid.initialize({")
        append("startOnLoad:false,")
        append("securityLevel:'strict',")
        append("htmlLabels:false,")
        // 主题跟随阅读器；'neutral' 在深色底上对比度更好，故仅 default/dark 两选一并收敛主题包
        append("theme:'").append(sanitizeTheme(options.mermaidTheme)).append("',")
        append("fontFamily:getComputedStyle(document.body).fontFamily")
        append("});")
        append("}catch(e){status.error=String(e);markFallback(document.body,'mermaid-init');return Promise.resolve();}")
        append("var nodes=Array.prototype.slice.call(document.querySelectorAll('pre.mermaid,div.mermaid'))")
        append(".filter(function(n){return !n.getAttribute('").append(RenderedAttribute).append("');});")
        append("if(!nodes.length)return Promise.resolve();")
        append("return Promise.resolve(mermaid.run({nodes:nodes,suppressErrors:true}))")
        append(".then(function(){nodes.forEach(function(n){n.setAttribute('").append(RenderedAttribute).append("','mermaid');});status.mermaid=nodes.length;})")
        append(".catch(function(e){status.error=String(e);nodes.forEach(function(n){markFallback(n,'mermaid-run');});});")
        append("}")
    }

    private fun buildKatexRuntime(options: Options): String = buildString {
        append("var KATEX_OPTS={")
        append("trust:false,")                    // 拒 \\href / \\htmlClass 等危险命令
        append("strict:'warn',")
        append("throwOnError:false,")             // 错误渲染为红色文本，不抛异常
        append("maxSize:").append(options.katexMaxSize.coerceAtLeast(1)).append(",")
        append("maxExpand:").append(options.katexMaxExpand.coerceAtLeast(1))
        append("};")
        append("function renderMathIn(root){")
        append("if(!window.katex){markFallback(root,'katex-missing');return 0;}")
        append("var count=0;")
        append("var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null,false);")
        append("var targets=[];")
        append("while(walker.nextNode()){")
        append("var node=walker.currentNode;")
        append("var parent=node.parentElement;")
        append("if(!parent||!node.nodeValue||node.nodeValue.indexOf('\\$')<0)continue;")
        append("var tag=parent.tagName?parent.tagName.toLowerCase():'';")
        append("if(tag==='code'||tag==='pre'||tag==='script'||tag==='style')continue;")
        append("if(parent.getAttribute('").append(RenderedAttribute).append("'))continue;")
        append("targets.push(node);")
        append("}")
        append("targets.forEach(function(node){")
        append("var src=node.nodeValue;")
        append("var re=/\\\$\\\$([\\s\\S]+?)\\\$\\\$|\\\$([^\\\$\\n]+?)\\\$/g;")
        append("if(!re.test(src))return;")
        append("re.lastIndex=0;")
        append("var frag=document.createDocumentFragment();var last=0;var match;")
        append("while((match=re.exec(src))!==null){")
        append("if(match.index>last)frag.appendChild(document.createTextNode(src.slice(last,match.index)));")
        append("var display=match[1]!==undefined;")
        append("var body=display?match[1]:match[2];")
        append("var host=document.createElement(display?'div':'span');")
        append("host.setAttribute('").append(RenderedAttribute).append("','math');")
        append("try{katex.render(body,host,KATEX_OPTS);count++;}")
        append("catch(e){host.textContent=(display?'\\$\\$':'\\$')+body+(display?'\\$\\$':'\\$');markFallback(host,'katex');}")
        append("frag.appendChild(host);")
        append("last=match.index+match[0].length;")
        append("}")
        append("if(last<src.length)frag.appendChild(document.createTextNode(src.slice(last)));")
        append("node.parentNode.replaceChild(frag,node);")
        append("});")
        append("return count;")
        append("}")
    }

    private fun buildHighlightRuntime(): String = buildString {
        append("function runHighlight(){")
        append("if(!window.hljs){markFallback(document.body,'hljs-missing');return 0;}")
        append("var blocks=Array.prototype.slice.call(document.querySelectorAll('pre code'))")
        append(".filter(function(b){return !b.getAttribute('").append(RenderedAttribute).append("');});")
        append("blocks.forEach(function(block){")
        append("try{hljs.highlightElement(block);}catch(e){markFallback(block,'hljs');}")
        append("block.setAttribute('").append(RenderedAttribute).append("','code');")
        append("});")
        append("return blocks.length;")
        append("}")
    }

    /** 驱动：字体/图片就绪 → 依次渲染 → 置完成标记（宿主据此重测分页）。 */
    private fun buildDriver(
        wantMermaid: Boolean,
        wantMath: Boolean,
        wantHighlight: Boolean,
        timeoutMillis: Long
    ): String = buildString {
        append("function whenReady(){")
        append("var tasks=[];")
        append("if(document.fonts&&document.fonts.ready)tasks.push(document.fonts.ready.catch(function(){}));")
        append("tasks.push(new Promise(function(resolve){")
        append("if(document.readyState==='complete')return resolve();")
        append("window.addEventListener('load',function(){resolve();},{once:true});")
        append("}));")
        append("return Promise.all(tasks);")
        append("}")
        // 可重复运行的渲染入口：**模板沙箱**在正文经 init 注入之后必须能再跑一次
        // （沙箱的自动驱动发生在文档加载时，那时正文还没进来 ⇒ 只跑一次会渲染空文档）。
        // 重置 done/error，避免上一轮失败标记污染本轮结论。
        append("function runAll(){")
        append("status.done=false;status.error=null;")
        append("var chain=Promise.resolve();")
        if (wantMermaid) chainAppend("runMermaid")
        if (wantMath) chainAppend("renderMathIn")
        if (wantHighlight) chainAppend("runHighlight")
        append("return chain;")
        append("}")
        append("status.run=runAll;")
        append("function finish(){status.done=true;}")
        append("var timeout=setTimeout(finish,").append(timeoutMillis.coerceAtLeast(1)).append(");")
        append("whenReady().then(runAll).then(function(){clearTimeout(timeout);finish();})")
        append(".catch(function(e){status.error=String(e);clearTimeout(timeout);finish();});")
    }

    /** 顺序执行：前一步失败不阻断后续（降级链语义）。 */
    private fun StringBuilder.chainAppend(fn: String) {
        append("chain=chain.then(function(){return Promise.resolve(")
            .append(fn)
            .append(if (fn == "renderMathIn") "(document.body)" else "()")
            .append(").then(function(v){")
        when (fn) {
            "runMermaid" -> append("if(typeof v==='number')status.mermaid=v;")
            "renderMathIn" -> append("if(typeof v==='number')status.math=v;")
            else -> append("if(typeof v==='number')status.code=v;")
        }
        append(";}).catch(function(e){status.error=String(e);});});")
    }

    private fun sanitizeTheme(theme: String): String {
        return if (theme == MermaidThemeDark) MermaidThemeDark else MermaidThemeDefault
    }

    const val MermaidThemeDefault = "default"
    const val MermaidThemeDark = "dark"
    const val DefaultKatexMaxSize = 200
    const val DefaultKatexMaxExpand = 1000
    const val DefaultTimeoutMillis = 4000L
}