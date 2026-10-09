package io.legado.app.model.localBook.epubcore.template

/**
 * `asset://alias` 安全策略（epub-md-rich-rendering 阶段 4.4；AD-23 / blueprint §七）。
 *
 * **背景**：模板包可声明素材别名（`package.json` 的 `resources[].alias/path`），作者 HTML 用
 * `asset://bg` 引用。若不校验，模板即可读取**宿主任意文件**（`asset://../../databases/legado.db`）
 * 或发起网络请求——这是模板系统最大的越权面。
 *
 * 准入规则（**全部为拒收式白名单**，纯函数 ⇒ JVM 逐条断言）：
 * 1. 只接受 `asset://<alias>` 形态（**无路径段**：别名→包内路径的映射由包清单决定，
 *    作者不得在 URL 里自带路径，从根上消除 `..`/绝对路径/编码穿越）；
 * 2. 别名必须在包清单中声明；
 * 3. 清单声明的路径必须经归一化后仍位于允许根内（`assets/`，兼容 Lottie 的 `images/`）；
 * 4. 拒绝反斜杠、`..` 段、绝对路径、`file://`/`content://`/网络 scheme；
 * 5. 扩展名须与声明的资源类型一致，且（可选）与**文件头嗅探**结果一致；
 * 6. 单文件与总量限额。
 */
internal object ReaderAssetPathPolicy {

    const val Scheme = "asset"

    /** 包内允许的素材根（`assets/` 为标准根；`images/` 兼容既有 Lottie 套件打包范式）。 */
    val AllowedRoots = listOf("assets/", "images/")

    /** 单文件上限（默认 8Mi）。 */
    const val MaxAssetBytes = 8L * 1024 * 1024

    /** 单包素材总量上限（默认 64Mi）。 */
    const val MaxPackageBytes = 64L * 1024 * 1024

    /** 资源类型 → 允许扩展名。 */
    private val typeExtensions: Map<String, Set<String>> = mapOf(
        "image" to setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "avif"),
        "font" to setOf("ttf", "otf", "woff", "woff2"),
        "audio" to setOf("mp3", "ogg", "wav", "m4a"),
        "video" to setOf("mp4", "webm"),
        "json" to setOf("json"),
        "css" to setOf("css"),
        "js" to setOf("js")
    )

    /** 包清单中的一条素材声明。 */
    data class ResourceDeclaration(
        val alias: String,
        val path: String,
        val type: String
    )

    data class Rejection(val code: String, val message: String)

    /** 解析结果：命中的声明（宿主据此从包内读取字节）。 */
    data class Resolved(val declaration: ResourceDeclaration)

    /**
     * 解析 `asset://<alias>`。
     *
     * @param url 作者书写的引用。
     * @param declarations 包清单声明的素材表（alias 唯一）。
     */
    fun resolve(url: String?, declarations: List<ResourceDeclaration>): Result<Resolved> {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return failure("empty", "空引用")
        val prefix = "$Scheme://"
        if (!raw.startsWith(prefix, ignoreCase = true)) {
            return failure("bad-scheme", "仅允许 $Scheme:// 引用：$raw")
        }
        val alias = raw.substring(prefix.length)
        if (alias.isEmpty()) return failure("empty-alias", "缺少别名")
        // 只接受纯别名：含 `/`、`\`、`?`、`#`、`%` 一律拒（从根上消除路径穿越/编码绕行）。
        if (alias.any { it == '/' || it == '\\' || it == '?' || it == '#' || it == '%' }) {
            return failure("path-in-url", "别名不得携带路径/编码：$alias")
        }
        if (alias == "." || alias == "..") return failure("bad-alias", "非法别名：$alias")
        val declaration = declarations.firstOrNull { it.alias == alias }
            ?: return failure("unknown-alias", "未声明的素材别名：$alias")
        validateDeclaration(declaration)?.let { return Result.failure(IllegalArgumentException(it.message)) }
        return Result.success(Resolved(declaration))
    }

    /** 校验一条声明（路径安全 + 类型/扩展名一致）。合法返回 null。 */
    fun validateDeclaration(declaration: ResourceDeclaration): Rejection? {
        if (declaration.alias.isBlank()) return Rejection("blank-alias", "别名不能为空")
        if (declaration.alias.any { it == '/' || it == '\\' }) return Rejection("alias-path", "别名不得含路径分隔符")
        val path = declaration.path
        if (path.isBlank()) return Rejection("blank-path", "路径不能为空")
        normalizeInPackage(path)?.let { return it }
        val allowed = typeExtensions[declaration.type]
            ?: return Rejection("unknown-type", "不支持的素材类型：${declaration.type}")
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension !in allowed) {
            return Rejection("ext-mismatch", "类型 ${declaration.type} 不接受扩展名 .$extension")
        }
        return null
    }

    /** 校验总量限额；超限返回拒收原因。 */
    fun validatePackageSize(
        declarations: List<ResourceDeclaration>,
        sizeOf: (ResourceDeclaration) -> Long
    ): Rejection? {
        var total = 0L
        declarations.forEach { declaration ->
            val size = sizeOf(declaration)
            if (size > MaxAssetBytes) {
                return Rejection("asset-too-large", "素材超限：${declaration.alias} 为 $size 字节")
            }
            total += size
        }
        if (total > MaxPackageBytes) {
            return Rejection("package-too-large", "素材总量超限：$total 字节")
        }
        return null
    }

    /**
     * 扩展名与**文件头**一致性（内容嗅探）：防"改后缀绕过类型白名单"。
     *
     * 只做**保守判定**：能识别出明确类型且与扩展名冲突 ⇒ 拒；无法识别 ⇒ 放行（不误杀）。
     */
    fun matchesFileHeader(path: String, header: ByteArray): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase()
        val detected = detectType(header) ?: return true
        return when (detected) {
            "png" -> extension == "png"
            "jpeg" -> extension == "jpg" || extension == "jpeg"
            "gif" -> extension == "gif"
            "webp" -> extension == "webp"
            // SVG/JSON/CSS/JS 均为文本，无可靠魔数 ⇒ 不做判定（交给渲染层按 MIME 处理）。
            else -> true
        }
    }

    private fun detectType(header: ByteArray): String? {
        fun startsWith(vararg expected: Int): Boolean {
            if (header.size < expected.size) return false
            return expected.indices.all { index -> (header[index].toInt() and 0xff) == expected[index] }
        }
        return when {
            startsWith(0x89, 0x50, 0x4e, 0x47) -> "png"
            startsWith(0xff, 0xd8, 0xff) -> "jpeg"
            startsWith(0x47, 0x49, 0x46, 0x38) -> "gif"
            startsWith(0x52, 0x49, 0x46, 0x46) && header.size >= 12 &&
                (header[8].toInt() and 0xff) == 0x57 && (header[9].toInt() and 0xff) == 0x45 -> "webp"
            else -> null
        }
    }

    /**
     * 包内路径归一化与根校验。
     *
     * @return 拒收原因；null 表示合法。
     */
    private fun normalizeInPackage(path: String): Rejection? {
        if (path.contains('\\')) return Rejection("backslash", "路径不得含反斜杠：$path")
        val lower = path.lowercase()
        if (lower.startsWith("file:") || lower.startsWith("content:") ||
            lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("//")
        ) {
            return Rejection("external-path", "不得引用包外/网络资源：$path")
        }
        if (path.startsWith('/')) return Rejection("absolute-path", "不得使用绝对路径：$path")
        if (path.contains('%')) return Rejection("encoded-path", "路径不得含百分号编码：$path")
        val segments = path.split('/')
        if (segments.any { it == ".." || it == "." || it.isEmpty() }) {
            return Rejection("dot-segment", "路径不得含点段/空段：$path")
        }
        if (AllowedRoots.none { lower.startsWith(it) }) {
            return Rejection("outside-root", "素材必须位于 ${AllowedRoots.joinToString("/")} 之内：$path")
        }
        return null
    }

    private fun failure(code: String, message: String): Result<Resolved> =
        Result.failure(IllegalArgumentException("$code: $message"))
}