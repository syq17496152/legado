package io.legado.app.ui.book.read.config

/**
 * 术语单源（AD-07）：界面只认新词，旧词只用于迁移识别与验收断言。
 *
 * 只影响**显示文案**，不改 key / 类名 / `PageTab.key` / 日志 tag。
 */
object TtsUxTerms {

    const val LISTEN = "听书"
    const val PROFILE = "音质档"
    const val VOICE = "音色"
    const val ROLE_VOICE = "角色音色"
    const val CASTING = "演播方式"
    const val SEGMENT = "分段"
    const val PREBUILD = "提前生成音频"
    const val VOICE_ASSET = "声音资源"
    const val ONBOARDING = "首次配置向导"
    const val LISTEN_MODE = "收听方式"

    /** 旧词 → 新词（仅用于提示与验收，不在运行时做全局替换） */
    val legacyMap: Map<String, String> = mapOf(
        "朗读" to LISTEN,
        "内置朗读" to LISTEN,
        "离线多角色引擎" to LISTEN,
        "引擎档位" to PROFILE,
        "发言人" to VOICE,
        "角色配音" to ROLE_VOICE,
        "配声" to ROLE_VOICE,
        "分镜" to SEGMENT,
        "批量预合成" to PREBUILD,
        "声音资源中心" to VOICE_ASSET,
        "开书向导" to ONBOARDING,
        "开通三步" to ONBOARDING
    )

    /** 专有名词：允许保留原词，不参与替换 */
    val whitelist: Set<String> = setOf("系统 TTS", "HTTP TTS", "模型", "音色包")

    /** 该文案是否含白名单外旧词（验收用，不做自动替换） */
    fun containsLegacy(text: String): Boolean {
        if (text.isBlank()) return false
        if (whitelist.any { text.contains(it) }) return false
        return legacyMap.keys.any { text.contains(it) }
    }
}
