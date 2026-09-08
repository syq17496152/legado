package io.legado.app.help.readaloud.speech

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import org.json.JSONObject
import splitties.init.appCtx

/**
 * 系统 TTS 引擎级独立参数存取（AD-05）
 * 单一 PreferKey（ttsEngineParamsJson）承载全部引擎参数，键=engineValue（包名），
 * 规避动态 PreferKey 游离于 allPreferenceKeys 注册表之外的问题（备份/清理遗漏防护）。
 */
data class TtsEngineParams(
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f
)

object TtsEngineParamsStore {

    private var cache: MutableMap<String, TtsEngineParams>? = null

    private fun load(): MutableMap<String, TtsEngineParams> {
        cache?.let { return it }
        val json = appCtx.getPrefString(PreferKey.ttsEngineParamsJson)
        val map = mutableMapOf<String, TtsEngineParams>()
        runCatching {
            val obj = JSONObject(json ?: return@runCatching)
            val keys = obj.keys()
            for (key in keys) {
                val item = obj.optJSONObject(key) ?: continue
                map[key] = TtsEngineParams(
                    speechRate = item.optDouble("speechRate", 1.0).toFloat(),
                    pitch = item.optDouble("pitch", 1.0).toFloat(),
                    volume = item.optDouble("volume", 1.0).toFloat()
                )
            }
        }
        cache = map
        return map
    }

    private fun save(map: MutableMap<String, TtsEngineParams>) {
        cache = map
        val obj = JSONObject()
        for ((key, p) in map) {
            obj.put(
                key,
                JSONObject()
                    .put("speechRate", p.speechRate.toDouble())
                    .put("pitch", p.pitch.toDouble())
                    .put("volume", p.volume.toDouble())
            )
        }
        appCtx.putPrefString(PreferKey.ttsEngineParamsJson, obj.toString())
    }

    fun get(engineValue: String): TtsEngineParams {
        if (engineValue.isBlank()) return TtsEngineParams()
        return load()[engineValue] ?: TtsEngineParams()
    }

    fun save(
        engineValue: String,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        volume: Float = 1.0f
    ) {
        if (engineValue.isBlank()) return
        val map = load()
        map[engineValue] = TtsEngineParams(speechRate, pitch, volume)
        save(map)
    }
}
