package io.legado.app.help.readaloud

import io.legado.app.help.readaloud.speech.SpeechRoute

/**
 * 朗读引擎可用性闸门（5.6：未安装/初始化失败回退）。
 *
 * 背景（2026-11-03 真机实测）：Android 的 `TextToSpeech(context, listener, 不存在的包名)`
 * 仍会回调 `SUCCESS` 并静默改用当前默认引擎 ⇒ 用户只会发现"音色变了"，不知道目标引擎已缺失。
 * 因此必须在创建前用 PackageManager 判一次"目标包是否还在"，缺失时给**可读原因**再回退系统默认。
 *
 * 纯决策函数放这里，便于 JVM 单测（服务内只负责取 `packageInstalled`）。
 */
object TtsEngineGuard {

    /** 是否属于"需要探测包存在性"的系统引擎路由（HTTP/默认路由不探测） */
    fun shouldProbe(engineType: String, engineValue: String): Boolean =
        engineType == SpeechRoute.ENGINE_SYSTEM && engineValue.isNotBlank()

    /** 目标引擎包缺失 ⇒ 必须先回退系统默认（避免框架静默换引擎） */
    fun needsFallback(engineType: String, engineValue: String, packageInstalled: Boolean): Boolean =
        shouldProbe(engineType, engineValue) && !packageInstalled

    /** 回退提示（面向用户，含目标引擎名；label 由调用方取显示名） */
    fun fallbackMessage(label: String): String {
        val name = label.takeIf { it.isNotBlank() } ?: "目标引擎"
        return "朗读引擎「$name」未安装，已回退系统默认"
    }
}
