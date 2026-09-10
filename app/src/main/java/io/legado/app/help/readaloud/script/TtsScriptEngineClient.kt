package io.legado.app.help.readaloud.script

import com.script.rhino.RhinoClassShutter
import com.script.rhino.RhinoScriptEngine
import com.script.buildScriptBindings
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.withBookSourceClassPolicy
import kotlinx.coroutines.CancellationException
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
 * - 独立 TTS 脚本档（E6/方向⑤）：RhinoClassShutter.withTtsScriptClassPolicy——宿主 App 类
 *   （io.legado.app.*）实拦（书源档的"观察放行"对第三方脚本不适用）；非 app 前缀 Java 类维持放行
 * - ⚠️ 契约：禁止向 bindings 注入 Java 对象（RhinoWrapFactory 的 visibleToScripts(Object) 重载
 *   不经类名档位判定链，注入即绕过沙箱）；当前仅暴露 sourceLabel 字符串，保持
 * - 三函数统一 withTimeout(10s)（eval 传协程上下文，Rhino 指令观察器可打断阻塞执行）
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
     * - 超时：withTimeout(EXEC_TIMEOUT_MS)（防脚本死循环）；eval 传入当前协程上下文，
     *   使 Rhino 指令观察器能在超时取消时打断无挂起点的阻塞执行（否则 10s 超时形同虚设）
     * - 返回：JSON 序列化字符串（ Rhino 内 JSON.stringify 归一化）
     */
    private suspend fun evalFunction(
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
        // 协程上下文须在 withTtsScriptClassPolicy（非 inline）块外捕获，块内调用挂起函数无法编译
        val coroutineCtx = currentCoroutineContext()
        return RhinoClassShutter.withTtsScriptClassPolicy(sourceLabel = sourceLabel) {
            val bindings = buildScriptBindings { bindings ->
                bindings["sourceLabel"] = sourceLabel
            }
            val scope = RhinoScriptEngine.getRuntimeScope(bindings)
            RhinoScriptEngine.eval(callJs, scope, coroutineCtx)
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
     * 音色目录拉取（含动态 URL 解析）：voices() 返回 {type:"url",url} 或 {voicesUrl} 时由宿主 GET 拉取目录
     * 返回原始目录 JSON（调用方写 speakersJson）
     */
    suspend fun fetchVoicesCatalog(httpTts: HttpTTS): String? {
        val raw = fetchVoices(httpTts)
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        // 兼容两种动态目录键：voicesUrl（宿主约定）与 url（内置模板 multitts/clonetts 实际返回键）
        val dynamicUrl = obj?.optString("voicesUrl")?.ifBlank { null }
            ?: obj?.optString("url")?.ifBlank { null }
        if (dynamicUrl == null) {
            return raw
        }
        return withContext(Dispatchers.IO) {
            val connection = java.net.URL(dynamicUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            try {
                if (connection.responseCode !in 200..299) {
                    AppLog.put("TTS 音色目录拉取失败：HTTP ${connection.responseCode}")
                    return@withContext null
                }
                // 体积限额 2MB：防异常目录撑爆内存
                val bytes = connection.inputStream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > 2 * 1024 * 1024) {
                            throw NoStackTraceException("TTS 音色目录超过 2MB 限额")
                        }
                        buffer.write(chunk, 0, n)
                    }
                    buffer.toByteArray()
                }
                String(bytes, Charsets.UTF_8)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("TTS 音色目录拉取失败：${e.message}")
                null
            } finally {
                connection.disconnect()
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
                // TtsTrace 真机联调：脚本引擎 synthesize 调用（MultiTTS/CloneTTS 适配联调核心证据）
                AppLog.putDebugWithTag(
                    AppLog.TAG_TTS_TRACE,
                    "script synthesize 引擎=${httpTts.id} type=${httpTts.type} textLen=${text.length} voice=${voiceId ?: "null"}",
                    level = AppLog.Level.INFO
                )
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
        // 纯 URL 返回：JSON.stringify 会给字符串包一层引号转义，先解包再校验（防 URL 携带字面引号）
        val pureUrl = runCatching {
            org.json.JSONTokener(resultJson).nextValue() as? String
        }.getOrNull() ?: resultJson
        checkSize("url", pureUrl.length, MAX_URL_LENGTH)
        return TtsScriptRequest(url = pureUrl)
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
