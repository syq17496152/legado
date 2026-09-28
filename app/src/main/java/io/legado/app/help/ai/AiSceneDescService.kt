package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.SceneBookmark
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * W8 / REQ-32（AD-12）：**名场面 AI 描述生成**。
 *
 * 复用既有 AI 通道 [`AiChatService.chatStream`]（模型取「文章总结」场景模型
 * `AppConfig.aiSummaryModelConfig`），输出契约为紧凑 JSON：
 * `{"desc":"一句话 ≤30 字","tags":["标签1","标签2","标签3"]}`。
 *
 * **降级链（核心链路零 AI 依赖）**：
 *  · 开关关闭（`PreferKey.aiSceneDescEnabled`）/ 场景模型未配置 / 供应商缺失 ⇒ [isAvailable] = false，
 *    调用方跳过生成，仅存原文片段（书签主体照常落库，不阻塞）；
 *  · 超时（[TIMEOUT_MILLIS]）/ 通道异常 / 返回非 JSON ⇒ [parse] 走原文截断降级，tags 置空。
 *
 * ⚠ 本类不含任何 UI 依赖（Toast/悬浮提示由调用方给出），以便 JVM 单测覆盖提示词与解析口径。
 */
object AiSceneDescService {

    /** 生成结果：一句话描述 + 标签（均为**已清洗**的展示态） */
    data class SceneDesc(val desc: String, val tags: List<String>)

    /** 描述长度上限（与详设输出契约一致：≤30 字） */
    const val MAX_DESC_LEN = 30

    /** 标签数量上限（详设输出契约：3 个） */
    const val MAX_TAG_COUNT = 3

    /** 生成超时：超过即降级，不阻塞阅读（详设 §3.3 降级链） */
    const val TIMEOUT_MILLIS = 30_000L

    /**
     * AI 是否可用：开关开启 **且** 「文章总结」场景模型已配置 **且** 其供应商存在。
     * 任一不满足 ⇒ 调用方跳过生成（降级为原文片段）。此判定**不做网络探测**，纯配置判断。
     */
    fun isAvailable(): Boolean {
        if (!AppConfig.aiSceneDescEnabled) return false
        val model = AppConfig.aiSummaryModelConfig ?: return false
        return AppConfig.aiProviderForModel(model) != null
    }

    /**
     * 生成名场面描述；**永不抛异常**（超时/异常/解析失败均在内部降级）。
     *
     * @param onStatus 透传给 AI 通道的状态回调（供调用方展示进度，可空实现）
     * @return 生成或降级后的描述（调用方直接落库即可）
     */
    suspend fun generate(
        bookmark: SceneBookmark,
        onStatus: (JSONObject) -> Unit = {}
    ): SceneDesc {
        val fallback = fallbackSource(bookmark)
        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = buildPrompt(bookmark)
            )
        )
        val raw = kotlin.runCatching {
            withTimeoutOrNull(TIMEOUT_MILLIS) {
                AiChatService.chatStream(
                    messages = messages,
                    onPartial = {},
                    onStatus = onStatus,
                    includeStructuredBlocks = false,
                    useAllTools = false,
                    modelConfigOverride = AppConfig.aiSummaryModelConfig
                )
            }
        }.onFailure {
            // 取消异常必须继续抛出，否则会把「调用方协程被取消」误报为「生成失败」
            if (it is CancellationException) throw it
            AppLog.put("名场面描述生成失败", it)
        }.getOrNull()
        return parse(raw.orEmpty(), fallback)
    }

    /**
     * 解析 AI 返回文本（容错）：允许 ```json 围栏与前后说明文字，只取首个 `{...}` 块。
     *
     * 降级口径：无法解析出有效 `desc` 时，用 [fallbackText] 截断 30 字；tags 置空。
     */
    fun parse(raw: String, fallbackText: String): SceneDesc {
        val trimmed = raw.trim()
        val json = extractJsonObject(trimmed)
        if (json != null) {
            val desc = sanitizeDesc(json.optString("desc"))
            val tags = sanitizeTags(json.optJSONArray("tags"))
            if (desc.isNotBlank()) {
                return SceneDesc(desc, tags)
            }
        }
        val text = if (trimmed.isNotEmpty() && json == null) trimmed else fallbackText
        return SceneDesc(sanitizeDesc(text), emptyList())
    }

    /** 提示词：输入书名 + 章节名 + 选中文本（图片/漫画路径无文本时以章节名为上下文） */
    fun buildPrompt(bookmark: SceneBookmark): String {
        val context = buildString {
            append("- 书名：").append(bookmark.bookName.ifBlank { "未知" }).append('\n')
            append("- 章节：").append(bookmark.chapterName.ifBlank { "未知" }).append('\n')
            if (bookmark.text.isNotBlank()) {
                append("- 原文片段：").append(bookmark.text.take(600))
            }
        }
        return """
            你是阅读应用的名场面描述助手。请根据下面的阅读位置信息，生成一句便于回看的描述与标签。

            要求：
            1. 描述一句话，不超过 $MAX_DESC_LEN 个字，抓取该处最鲜明的画面或情绪，不要复述原文。
            2. 标签 $MAX_TAG_COUNT 个，每个 2-4 字，用于分类回看。
            3. 只输出 JSON，不要解释，不要使用 Markdown 代码块。

            输出格式：
            {"desc": "描述", "tags": ["标签1", "标签2", "标签3"]}

            阅读位置：
            $context
        """.trimIndent()
    }

    /**
     * 降级描述：用户手写备注 > 原文片段前 30 字 > 章节名 > 书名。
     * 注意：仅使用**已有字段**拼装，绝不发网络请求。
     */
    fun fallbackDesc(bookmark: SceneBookmark): String {
        return sanitizeDesc(fallbackSource(bookmark))
    }

    private fun fallbackSource(bookmark: SceneBookmark): String {
        return bookmark.desc.ifBlank { bookmark.text }.ifBlank { bookmark.chapterName }
            .ifBlank { bookmark.bookName }
    }

    /** 提取首个平衡的 `{...}` 片段；无花括号则返回 null（交由文本降级处理） */
    private fun extractJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return kotlin.runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    /** 清洗描述：折叠所有空白（含换行）后截断至 [MAX_DESC_LEN] */
    fun sanitizeDesc(raw: String): String {
        val collapsed = raw.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= MAX_DESC_LEN) collapsed else collapsed.take(MAX_DESC_LEN)
    }

    /** 清洗标签：去空、去重、限 [MAX_TAG_COUNT] 个 */
    private fun sanitizeTags(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length())
            .map { sanitizeDesc(array.optString(it)) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_TAG_COUNT)
    }
}
