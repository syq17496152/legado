package io.legado.app.ui.book.read.config

import io.legado.app.constant.PreferKey

/**
 * 朗读设置「常用」组（AD-06）：把高频 4 件事从 4 个工程视角分组里提出来做成默认组。
 *
 * 设计约束：
 * - **不新增设置项**：语速直接复用 `PreferKey.ttsSpeechRate`；
 * - 另外 3 项是"跳转动作"（音色 / 演播方式 / 定时），不产生新的偏好 key；
 * - 原 Base/Reading/AiRole/Engine 四组内容零改动。
 */
object ReadAloudCommonItems {

    /** 语速：真实设置项，归属仍是 Reading 组（常用组只是第二个编辑入口的展示） */
    const val KEY_RATE: String = PreferKey.ttsSpeechRate

    /** 跳转动作 key（不是偏好 key，仅用于 spec 行标识与单测断言） */
    const val KEY_VOICE = "tts_common_voice"
    const val KEY_CASTING = "tts_common_casting"
    const val KEY_TIMER = "tts_common_timer"

    val settingKeys: List<String> = listOf(KEY_RATE)

    val actionKeys: List<String> = listOf(KEY_VOICE, KEY_CASTING, KEY_TIMER)

    /** 常用组的展示顺序：语速 → 音色 → 演播方式 → 定时 */
    val orderedKeys: List<String> = listOf(KEY_RATE, KEY_VOICE, KEY_CASTING, KEY_TIMER)

    fun isCommonKey(key: String): Boolean = key in orderedKeys
}
