package io.legado.app.help.readaloud.script

import com.script.rhino.RhinoClassShutter
import com.script.rhino.RhinoScriptEngine
import com.script.buildScriptBindings
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.withBookSourceClassPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 脚本引擎头注契约（本项目自定义，借鉴 legado_NG 概念）：
 * //@name: 名称
 * //@schema: 1
 * //@capabilities: speed,pitch,volume（能力声明，未声明维度不进缓存键且不下发）
 * //@defaultSpeed: 50
 */
data class TtsScriptHeader(
    val name: String = "",
    val schema: Int = 1,
    val capabilities: List<String> = emptyList(),
    val defaultSpeed: Int = 50
)

/**
 * synthesize 返回的请求对象（四字段边界：url/method/headers/body，§AD-04）
 */
data class TtsScriptRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

/**
 * 脚本引擎执行器（AD-04）：Rhino 执行 options()/voices()/synthesize() 三函数
 * - 强制 P0 沙箱：RhinoClassShutter 类策略显式启用（HttpTTS 非 BookSource，包装默认不生效）
 * - 三函数统一 withTimeout(10s)（Rhino 指令观察器仅协作式取消，不能防 while(true)）
 * - 体积限额：脚本源码≤512KB、synthesized URL≤8KB、请求体≤256KB
 * - 作用域暴露面：不暴露 HttpTTS 对象，仅脱敏 sourceLabel
 */
object TtsScriptEngineClient {

    private const val EXEC_TIMEOUT_MS = 10_000L
    private const val MAX_URL_LENGTH = 8 * 1024
    private const val MAX_BODY_LENGTH = 256 * 1024
    private const val MAX_SCRIPT_LENGTH = 512 * 1024

    /** 已编译脚本缓存（engineId → 编译产物/解析头注），防重复解析 */
    private val headerCache = ConcurrentHashMap<String, TtsScriptHeader>()

    fun parseHeader(script: String): TtsScriptHeader {
        var name = ""
        var schema = 1
        val capabilities = mutableListOf<String>()
        var defaultSpeed = 50
        script.lineSequence().take(60).forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("//")) return@forEach
            val body = trimmed.removePrefix("//").trim()
            val idx = body.indexOf(':')
            if (idx <= 0) return@forEach
            val key = body.substring(0, idx).trim().removePrefix("@")
            val value = body.substring(idx + 1).trim()
            when (key) {
                "name" -> name = value
                "schema" -> schema = value.toIntOrNull() ?: 1
                "capabilities" -> capabilities.addAll(
                    value.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
                )
                "defaultSpeed" -> defaultSpeed = value.toIntOrNull() ?: 50
            }
        }
        return TtsScriptHeader(name, schema, capabilities, defaultSpeed)
    }

    fun validateScriptSize(script: String) {
        if (script.length > MAX_SCRIPT_LENGTH) {
            throw NoStackTraceException("TTS 脚本源码超过 512KB 限额")
        }
    }

    /**
     * 执行脚本内指定函数（options/voices/synthesize）
     * - 沙箱：显式启用类策略（HttpTTS 非 BookSource，包装默认不生效，见 design §3.3）
     * - 超时：withTimeout(EXEC_TIMEOUT_MS)（防脚本死循环）
     * - 返回：JSON 序列化字符串（ Rhino 内 JSON.stringify 归一化）
     */
    private fun evalFunction(
        httpTts: HttpTTS,
        function: String,
        argsJson: String
    ): String {
        val script = httpTts.script
        validateScriptSize(script)
        val sourceLabel = "tts:${httpTts.id}"
        val callJs = buildString {
            append(script)
            append("\n;JSON.stringify(")
            append(function)
            append('(')
            append(argsJson)
            append("))")
        }
        return RhinoClassShutter.withBookSourceClassPolicy(enabled = true, sourceLabel = sourceLabel) {
            val bindings = buildScriptBindings { bindings ->
                bindings["sourceLabel"] = sourceLabel
            }
            val scope = RhinoScriptEngine.getRuntimeScope(bindings)
            RhinoScriptEngine.eval(callJs, scope, null)
                ?.toString()
                ?: throw NoStackTraceException("TTS 脚本函数 $function 返回空")
        }
    }

    /**
     * voices()：返回音色目录 JSON（由调用方缓存进 speakersJson）
     */
    suspend fun fetchVoices(httpTts: HttpTTS): String {
        return withContext(Dispatchers.IO) {
            withTimeout(EXEC_TIMEOUT_MS) {
                evalFunction(httpTts, "voices", "null")
            }
        }
    }

    /**
     * options()：返回引擎配置项 JSON（本期仅透出，参数化 UI 登记后续）
     */
    suspend fun fetchOptions(httpTts: HttpTTS): String {
        return withContext(Dispatchers.IO) {
            withTimeout(EXEC_TIMEOUT_MS) {
                evalFunction(httpTts, "options", "null")
            }
        }
    }

    /**
     * synthesize(text, voice, params, options, ctx) → 返回 URL 或请求对象
     * 映射为 TtsScriptRequest（仅承接 url/method/headers/body 四字段，越界字段拒绝）
     */
    suspend fun synthesize(
        httpTts: HttpTTS,
        text: String,
        voiceId: String?,
        prosodyRate: Float?,
        prosodyPitch: Float?,
        prosodyVolume: Float?
    ): TtsScriptRequest {
        val paramsJson = buildString {
            append("{\"rate\":")
            append((prosodyRate ?: 0f).toDouble())
            append(",\"pitch\":")
            append((prosodyPitch ?: 0f).toDouble())
            append(",\"volume\":")
            append((prosodyVolume ?: 0f).toDouble())
            append("}")
        }
        val voiceJson = voiceId?.let { JSONObject.quote(it) } ?: "null"
        val argsJson = buildString {
            append(JSONObject.quote(text))
            append(',')
            append(voiceJson)
            append(',')
            append(paramsJson)
            append(",{},null")
        }
        val resultJson = withContext(Dispatchers.IO) {
            withTimeout(EXEC_TIMEOUT_MS) {
                evalFunction(httpTts, "synthesize", argsJson)
            }
        }
        val obj = runCatching { JSONObject(resultJson) }.getOrNull()
        if (obj != null) {
            val url = obj.optString("url")
            if (url.isBlank()) {
                throw NoStackTraceException("TTS 脚本 synthesize 返回缺少 url")
            }
            checkSize("url", url.length, MAX_URL_LENGTH)
            val method = obj.optString("method", "GET").uppercase()
            if (method !in setOf("GET", "POST")) {
                throw NoStackTraceException("TTS 脚本请求 method 越界：仅支持 GET/POST")
            }
            val headers = mutableMapOf<String, String>()
            obj.optJSONObject("headers")?.let { h ->
                h.keys().forEach { key -> headers[key] = h.optString(key) }
            }
            val body = obj.optString("body").ifBlank { null }
            body?.let { checkSize("body", it.length, MAX_BODY_LENGTH) }
            return TtsScriptRequest(url, method, headers, body)
        }
        // 纯 URL 返回
        checkSize("url", resultJson.length, MAX_URL_LENGTH)
        return TtsScriptRequest(url = resultJson)
    }

    private fun checkSize(field: String, length: Int, max: Int) {
        if (length > max) {
            throw NoStackTraceException("TTS 脚本产物 $field 超过 ${max / 1024}KB 限额")
        }
    }

    /** 脚本能力声明解析缓存（幂等） */
    fun capabilitiesOf(httpTts: HttpTTS): List<String> {
        return parseHeader(httpTts.script).capabilities
    }
}
