package io.legado.app.help.config

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * EPUB 首屏加载模板的**库 + 选择 + 编解码单源**（对应参考实现的 `Store`）。
 *
 * 设计口径（逐条都有理由）：
 * 1. **id 由本模块分配**（`local.<uuid>`），**导入载荷里没有 id** —— 否则用户可构造
 *    `id = "builtin.loading.ink"` 冒充内置（绕开"内置不可删"），或覆盖别人已保存的同名自定义模板；
 * 2. **不改写内置集**：内置三套是常量（[EpubLoadingTemplate.BUILT_IN]），不进库文本；
 * 3. prefs 读写**只经 `AppConfig`**（不直读 `defaultSharedPreferences`）—— 键名的唯一归属在
 *    `PreferKey`，读写门面在 `AppConfig`，本模块只做"库/选择"语义；
 * 4. 编解码用 `org.json`（**不用 Gson**）—— 规避 R8 × Gson 泛型签名门禁（AGENTS.md 强制规则 7，
 *    未 `@Keep` 的集合字段签名会被剥离 ⇒ 元素类型回落 Object 而崩）；
 * 5. 所有校验与选择逻辑抽成**纯函数**（本文件下半部），可 JVM 逐值断言（测试铁律 T1）。
 *
 * 安全/稳健：整条链路上任何异常都收敛成 `Result.failure`（不抛到调用方），调用方据此"回落既有遮罩 / 提示"。
 */
object EpubLoadingTemplateManager {

    /** 导入单件上限（沿用参考实现口径）：prefs 是 XML 且参与备份，必须控总量。 */
    const val MAX_IMPORT_BYTES = 48 * 1024

    /** 自定义模板条数上限（32 × 48KB ≈ 1.5MB，可控上界）。 */
    const val MAX_TEMPLATES = 32

    /** 全量（内置在前 + 自定义在后，按 id 去重）。 */
    fun all(context: Context): List<EpubLoadingTemplate> =
        dedupe(EpubLoadingTemplate.BUILT_IN + parseLoadingLibrary(AppConfig.epubLoadingTemplates))

    /** 仅在自定义库里（管理页需要区分"可否编辑/删除"）。 */
    fun customTemplates(context: Context): List<EpubLoadingTemplate> =
        parseLoadingLibrary(AppConfig.epubLoadingTemplates)

    /**
     * 当前生效模板：选择键命中内置 → 直接返回（零解析快路径）；命中自定义 → 返回之；
     * 键为空 / 指向已删除的模板 → **回落默认套**（不出现"白屏无模板"）。
     */
    fun selected(context: Context): EpubLoadingTemplate {
        val id = AppConfig.epubLoadingTemplate
        val builtIn = EpubLoadingTemplate.builtInById(id)
        if (builtIn != null) return builtIn
        val custom = parseLoadingLibrary(AppConfig.epubLoadingTemplates)
        return selectLoadingTemplate(EpubLoadingTemplate.BUILT_IN + custom, id)
    }

    /** 应用某套（写入选择键）。id 不存在 ⇒ failure（不静默落默认，避免"点了却没生效还不报错"）。 */
    fun apply(context: Context, id: String): Result<Unit> = kotlin.runCatching {
        applyLoadingTemplateId(all(context), id).getOrElse { throw it }
        AppConfig.epubLoadingTemplate = id
    }

    /** 恢复默认（清空选择键 ⇒ 走 [EpubLoadingTemplate.DEFAULT]，内置集不受影响）。 */
    fun restoreDefault(context: Context) {
        AppConfig.epubLoadingTemplate = ""
    }

    /**
     * 保存（导入或编辑）。`editingId` 非空 ⇒ 覆盖该自定义项；为空 ⇒ 新增（分配新 `local.` id）。
     * 校验链：体积上限 → 载荷校验（类型/版本/白名单/区间/文案长度/色值）→ 条数上限 → 落盘。
     */
    fun save(context: Context, json: String, editingId: String?): Result<EpubLoadingTemplate> =
        kotlin.runCatching {
            val library = parseLoadingLibrary(AppConfig.epubLoadingTemplates)
            val (updated, template) = saveLoadingTemplate(library, json, editingId, newLoadingTemplateId())
                .getOrElse { throw it }
            AppConfig.epubLoadingTemplates = encodeLoadingLibrary(updated)
            template
        }

    /** 删除自定义项；内置不可删；删的正是当前选择 ⇒ 选择键回落默认。 */
    fun delete(context: Context, id: String): Result<Unit> = kotlin.runCatching {
        val library = parseLoadingLibrary(AppConfig.epubLoadingTemplates)
        val updated = deleteLoadingTemplate(library, id).getOrElse { throw it }
        AppConfig.epubLoadingTemplates = encodeLoadingLibrary(updated)
        if (AppConfig.epubLoadingTemplate == id) restoreDefault(context)
    }

    /** 导出单套为可分享 JSON（含 id：便于对方导入后仍能看出它的来源名；导入侧仍会重新分配 id）。 */
    fun export(template: EpubLoadingTemplate): String = encodeLoadingPayload(template, includeId = true)

    /** 文案摘要（管理页卡片与设置入口共用一处格式化）。 */
    fun summary(template: EpubLoadingTemplate): String =
        if (template.description.isNotBlank()) template.description else template.name
}

/**
 * 导入/导出**载荷**（**不含 id**，见 [EpubLoadingTemplateManager] 的 id 分配口径）。
 */
data class EpubLoadingTemplatePayload(
    val name: String,
    val description: String,
    val scene: Scene,
    val eyebrow: String,
    val caption: String,
    val titleFont: String,
    val titleSize: Float,
    val artScale: Float,
    val day: Palette,
    val night: Palette
) {
    /** 落成带 id 的模板（id 由调用方分配，**不来自载荷**）。 */
    fun toTemplate(id: String): EpubLoadingTemplate = EpubLoadingTemplate(
        id = id, name = name, description = description, scene = scene,
        eyebrow = eyebrow, caption = caption, titleFont = titleFont,
        titleSize = titleSize, artScale = artScale, day = day, night = night
    )
}

// ===== 纯函数（可 JVM 直测）=====

/** 解码导入载荷：类型/版本严格等值 + 白名单 + 区间 + 文案长度 + 色值，全过才 Ok。 */
fun decodeLoadingPayload(json: String): Result<EpubLoadingTemplatePayload> = kotlin.runCatching {
    val root = JSONObject(json)
    val type = root.optString("type", "")
    if (type != EpubLoadingTemplate.DOC_TYPE) throw NoStackTraceException("不是首屏加载模板：type=$type")
    val version = root.optInt("version", -1)
    if (version != EpubLoadingTemplate.DOC_VERSION) throw NoStackTraceException("模板版本不支持：$version")

    val name = root.optString("name", "")
    if (name.isBlank()) throw NoStackTraceException("模板名不得为空")
    if (name.length > EpubLoadingTemplate.MAX_NAME_LENGTH) {
        throw NoStackTraceException("模板名过长（≤${EpubLoadingTemplate.MAX_NAME_LENGTH}）")
    }
    val description = root.optString("description", "").checkLength("说明", EpubLoadingTemplate.MAX_DESCRIPTION_LENGTH)
    val eyebrow = root.optString("eyebrow", "").checkLength("题记", EpubLoadingTemplate.MAX_EYEBROW_LENGTH)
    val caption = root.optString("caption", "").checkLength("副文案", EpubLoadingTemplate.MAX_CAPTION_LENGTH)

    val sceneKey = root.optString("scene", "")
    val scene = Scene.fromKey(sceneKey) ?: throw NoStackTraceException("不支持的场景：$sceneKey")

    val titleFont = root.optString("titleFont", EpubLoadingTemplate.DEFAULT_TITLE_FONT)
    if (titleFont !in EpubLoadingTemplate.TITLE_FONTS) throw NoStackTraceException("不支持的标题字体：$titleFont")

    val titleSize = root.optDouble("titleSize", EpubLoadingTemplate.DEFAULT_TITLE_SIZE.toDouble()).toFloat()
    if (titleSize !in EpubLoadingTemplate.TITLE_SIZE_RANGE) {
        throw NoStackTraceException("标题字号越界（${EpubLoadingTemplate.TITLE_SIZE_RANGE}）：$titleSize")
    }
    val artScale = root.optDouble("artScale", EpubLoadingTemplate.DEFAULT_ART_SCALE.toDouble()).toFloat()
    if (artScale !in EpubLoadingTemplate.ART_SCALE_RANGE) {
        throw NoStackTraceException("插画缩放越界（${EpubLoadingTemplate.ART_SCALE_RANGE}）：$artScale")
    }

    EpubLoadingTemplatePayload(
        name = name, description = description, scene = scene, eyebrow = eyebrow, caption = caption,
        titleFont = titleFont, titleSize = titleSize, artScale = artScale,
        day = parsePalette(root.optJSONObject("day"), "day"),
        night = parsePalette(root.optJSONObject("night"), "night")
    )
}

/** 编码载荷（`includeId` 供导出/库存两用；库存必须带 id，导入载荷必须不带）。 */
fun encodeLoadingPayload(template: EpubLoadingTemplate, includeId: Boolean): String {
    val root = JSONObject().apply {
        put("type", EpubLoadingTemplate.DOC_TYPE)
        put("version", EpubLoadingTemplate.DOC_VERSION)
        if (includeId) put("id", template.id)
        put("name", template.name)
        put("description", template.description)
        put("scene", template.scene.key)
        put("eyebrow", template.eyebrow)
        put("caption", template.caption)
        put("titleFont", template.titleFont)
        put("titleSize", template.titleSize.toDouble())
        put("artScale", template.artScale.toDouble())
        put("day", encodePalette(template.day))
        put("night", encodePalette(template.night))
    }
    return root.toString(2)
}

/** 解析库存文本（非法/损坏 ⇒ 空表；**不抛**：坏数据不能让管理页打不开）。 */
fun parseLoadingLibrary(raw: String?): List<EpubLoadingTemplate> {
    if (raw.isNullOrBlank()) return emptyList()
    return kotlin.runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id", "").takeIf { it.startsWith(EpubLoadingTemplate.LOCAL_PREFIX) }
            decodeLoadingPayload(item.toString()).getOrNull()?.let { payload ->
                id?.let { payload.toTemplate(it) }
            }
        }
    }.getOrDefault(emptyList())
}

fun encodeLoadingLibrary(library: List<EpubLoadingTemplate>): String {
    val array = JSONArray()
    library.forEach { array.put(JSONObject(encodeLoadingPayload(it, includeId = true))) }
    return array.toString()
}

/**
 * 保存纯内核（**不碰 prefs** ⇒ 可 JVM 逐值断言）。校验链与落盘分离，Context 侧只做读写。
 *
 * 顺序即正确性：**先体积上限**（不解析超限载荷）→ **再载荷校验** → **再条数上限** → 分配 id 并 upsert。
 * `editingId` 非空时必须**已在库中**（防"编辑一个不存在/已删的模板"静默变成新增）。
 *
 * @return 新库 + 新模板；任一步不过 ⇒ `Result.failure(NoStackTraceException(中文原因))`
 */
fun saveLoadingTemplate(
    library: List<EpubLoadingTemplate>,
    json: String,
    editingId: String?,
    newId: String
): Result<Pair<List<EpubLoadingTemplate>, EpubLoadingTemplate>> = kotlin.runCatching {
    val bytes = json.toByteArray(Charsets.UTF_8).size
    if (bytes > EpubLoadingTemplateManager.MAX_IMPORT_BYTES) {
        throw NoStackTraceException(
            "模板文件过大：${bytes / 1024}KB > ${EpubLoadingTemplateManager.MAX_IMPORT_BYTES / 1024}KB"
        )
    }
    val payload = decodeLoadingPayload(json).getOrElse { throw it }
    val targetId = when {
        editingId.isNullOrBlank() -> newId
        library.any { it.id == editingId } -> editingId
        else -> throw NoStackTraceException("要编辑的模板不在自定义库中")
    }
    if (editingId.isNullOrBlank() && library.size >= EpubLoadingTemplateManager.MAX_TEMPLATES) {
        throw NoStackTraceException("最多保存 ${EpubLoadingTemplateManager.MAX_TEMPLATES} 个自定义模板")
    }
    val template = payload.toTemplate(targetId)
    upsertLoadingTemplate(library, template, editingId) to template
}

/** 删除纯内核：内置不可删；不在库中 ⇒ failure（不静默成功）。 */
fun deleteLoadingTemplate(
    library: List<EpubLoadingTemplate>,
    id: String
): Result<List<EpubLoadingTemplate>> = kotlin.runCatching {
    if (EpubLoadingTemplate.builtInById(id) != null) throw NoStackTraceException("内置模板不可删除")
    if (library.none { it.id == id }) throw NoStackTraceException("模板不存在：$id")
    library.filterNot { it.id == id }
}

/** 应用纯内核：id 必须存在于「内置 ∪ 库」中（否则 failure，不静默落默认）。 */
fun applyLoadingTemplateId(known: List<EpubLoadingTemplate>, id: String): Result<Unit> =
    kotlin.runCatching {
        if (known.none { it.id == id }) throw NoStackTraceException("模板不存在：$id")
    }

/** 选择：内置优先（常量命中），否则在库里找；都没有 ⇒ 默认套。 */
fun selectLoadingTemplate(
    library: List<EpubLoadingTemplate>,
    selectedId: String?
): EpubLoadingTemplate {
    var hit = EpubLoadingTemplate.builtInById(selectedId)
    if (hit == null && !selectedId.isNullOrBlank()) {
        hit = library.firstOrNull { it.id == selectedId && !it.builtIn }
    }
    return hit ?: EpubLoadingTemplate.DEFAULT
}

/** 插入或覆盖替换（同 id 去重；顺序 = 既有序，覆盖不挪位；新增追加尾部）。 */
fun upsertLoadingTemplate(
    library: List<EpubLoadingTemplate>,
    template: EpubLoadingTemplate,
    replacingId: String?
): List<EpubLoadingTemplate> {
    val index = library.indexOfFirst { it.id == (replacingId ?: template.id) }
    return if (index >= 0) {
        library.toMutableList().also { it[index] = template }
    } else {
        library + template
    }
}

/** 内置 + 自定义按 id 去重（内置在前）。 */
private fun dedupe(list: List<EpubLoadingTemplate>): List<EpubLoadingTemplate> =
    list.distinctBy { it.id }

/** 新自定义 id（`local.<uuid>`；**导入方给的 id 一律不用**）。 */
fun newLoadingTemplateId(): String = EpubLoadingTemplate.LOCAL_PREFIX + UUID.randomUUID().toString()

private fun String.checkLength(label: String, max: Int): String {
    if (length > max) throw NoStackTraceException("$label 过长（≤$max）")
    return this
}

private fun parsePalette(json: JSONObject?, which: String): Palette {
    val obj = json ?: throw NoStackTraceException("缺少 $which 配色")
    fun color(key: String): Int {
        val raw = obj.optString(key, "")
        return parseHexColor(raw) ?: throw NoStackTraceException("$which.$key 颜色非法：$raw")
    }
    return Palette(
        background = color("background"),
        horizon = color("horizon"),
        ink = color("ink"),
        muted = color("muted"),
        accent = color("accent"),
        glow = color("glow")
    )
}

private fun encodePalette(palette: Palette): JSONObject = JSONObject().apply {
    put("background", argbToHex(palette.background))
    put("horizon", argbToHex(palette.horizon))
    put("ink", argbToHex(palette.ink))
    put("muted", argbToHex(palette.muted))
    put("accent", argbToHex(palette.accent))
    put("glow", argbToHex(palette.glow))
}
