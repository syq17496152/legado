package io.legado.app.help.readaloud.speech

import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
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

    private data class EngineParams(
        val speechRate: Float = 1.0f,
        val pitch: Float = 1.0f,
        val volume: Float = 1.0f
    )

    private var cache: MutableMap<String, EngineParams>? = null

    private fun load(): MutableMap<String, EngineParams> {
        cache?.let { return it }
        val json = appCtx.getPrefString(PreferKey.ttsEngineParamsJson)
        val map = runCatching {
            GSON.fromJsonObject<Map<String, EngineParams>>(json).getOrNull()
        }.getOrNull() ?: mutableMapOf()
        val safe = map.toMutableMap()
        cache = safe
        return safe
    }

    private fun save(map: MutableMap<String, EngineParams>) {
        cache = map
        appCtx.putPrefString(PreferKey.ttsEngineParamsJson, GSON.toJson(map))
    }

    fun get(engineValue: String): TtsEngineParams {
        if (engineValue.isBlank()) return TtsEngineParams()
        val p = load()[engineValue] ?: return TtsEngineParams()
        return TtsEngineParams(p.speechRate, p.pitch, p.volume)
    }

    fun save(
        engineValue: String,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        volume: Float = 1.0f
    ) {
        if (engineValue.isBlank()) return
        val map = load()
        map[engineValue] = EngineParams(speechRate, pitch, volume)
        save(map)
    }
}
