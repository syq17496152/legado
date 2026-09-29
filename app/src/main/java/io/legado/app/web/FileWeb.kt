package io.legado.app.web

import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.AppLog
import io.legado.app.web.utils.AssetsWeb
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream

/**
 * 控制台**按需分发**静态源（web-mcp-productization 四期 · tasks 1.2 / REQ-4-104~106）。
 *
 * 解析顺序（**文件源优先，miss 回 boot 壳**）：
 * 1. `filesDir/console/current/<rel>` —— 用户安装的控制台包（按需下载产物，四期 ConsoleInstaller 落盘处）；
 * 2. miss ⇒ `assets/web/boot/boot.html` —— 内置 ≈7KB 引导壳（App 只内置它，**不内置 1MB 控制台**，AD-05）。
 *
 * **为什么单独一个类**：`HttpServer.serve()` 的分发链已有六步（注册表查表 → 鉴权 → 装信封 → 静态兜底），
 * 控制台的"文件优先 + boot 兜底 + 路径穿越校验"是**独立的资源解析策略**，塞进 `serve()` 会让分发链继续膨胀。
 * 故 `serve()` 只加**一个前缀分支**（`uri.startsWith(FileWeb.CONSOLE_PREFIX)`），余下交给本类。
 *
 * **与门禁 G-21 的关系**：本改动**不引入端点级 `when (uri)` 分支**、也**不写字面量端点路径**
 * （前缀用常量 [CONSOLE_PREFIX]）⇒ `serve()` 仍是"注册表 + 单一静态兜底"两段式。
 *
 * **安全**：路径穿越双重防护 —— ①复用 [AssetsWeb.isSafePath]（拒 `..` / `\u0000` / 非 `/` 开头）
 * ②落盘文件再做 **canonicalPath 前缀校验**（防 symlink/规范化绕过）。
 */
object FileWeb {

    /** 控制台 URL 前缀（**常量而非字面量**：避免在 `serve()` 里出现端点路径字面量，见 G-21 口径）。 */
    const val CONSOLE_PREFIX = "/console"

    /** 已安装控制台在 `filesDir` 下的相对目录。 */
    private const val CONSOLE_ROOT = "console/current"

    /** 未安装 / 安装中 / 文件 miss 时的内置引导壳（assets 相对路径）。 */
    private const val BOOT_ASSET = "/boot/boot.html"

    private val assetsWeb by lazy { AssetsWeb("web") }

    /** 控制台包落盘根目录（`ConsoleInstaller` 安装目标，亦是本类的文件源根）。 */
    val consoleDir: File get() = File(appCtx.filesDir, CONSOLE_ROOT)

    /**
     * 解析「console 前缀」请求（即 [CONSOLE_PREFIX] 开头的全部子路径）。
     *
     * ⚠️ 注意：KDoc 里**不能**写该前缀加通配的连写形式（`斜杠 + 星号 + 星号`）—— Kotlin 块注释可嵌套，
     * 那会开一个永不闭合的注释层（`Unclosed comment`）。故此处只用文字描述。
     *
     * @param uri 原始请求路径（**必须以 [CONSOLE_PREFIX] 开头**）
     */
    fun getResponse(uri: String): NanoHTTPD.Response {
        // ① 基础路径安全（与 assets 同口径；控制台资源同样无 `..` 的合法用途）
        if (!AssetsWeb.isSafePath(uri)) {
            return plain(NanoHTTPD.Response.Status.BAD_REQUEST, "bad request path")
        }
        // ② 去前缀 → 缺省首页
        var rel = uri.removePrefix(CONSOLE_PREFIX)
        if (rel.isEmpty() || rel.endsWith("/")) rel += "index.html"

        // ③ 文件源优先（已安装的控制台）
        val root = consoleDir
        val target = File(root, rel.trimStart('/'))
        // ④ canonicalPath 前缀校验：即使路径安全函数被绕过，也不允许落到 consoleDir 之外
        val canonicalRoot = runCatching { root.canonicalPath }.getOrNull()
        val canonicalTarget = runCatching { target.canonicalPath }.getOrNull()
        if (canonicalRoot != null && canonicalTarget != null &&
            canonicalTarget.startsWith(canonicalRoot) && target.isFile
        ) {
            return fileResponse(target)
        }

        // ⑤ miss ⇒ 内置 boot 壳（未安装/安装中引导页）
        return runCatching { assetsWeb.getResponse(BOOT_ASSET) }.getOrElse { e ->
            AppLog.put("控制台 boot 壳读取失败（assets/web/boot/boot.html 缺失？）", e)
            plain(NanoHTTPD.Response.Status.NOT_FOUND, "console not installed")
        }
    }

    private fun fileResponse(file: File): NanoHTTPD.Response =
        runCatching {
            NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.OK,
                AssetsWeb.mimeOf(file.name),
                FileInputStream(file),
            )
        }.getOrElse { e ->
            AppLog.put("控制台静态文件读取失败：${file.name}", e)
            plain(NanoHTTPD.Response.Status.INTERNAL_ERROR, "read failed")
        }

    private fun plain(status: NanoHTTPD.Response.IStatus, body: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain", body)
}