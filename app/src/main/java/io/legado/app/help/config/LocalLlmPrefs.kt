package io.legado.app.help.config

/**
 * 端侧小模型 / AI 任务策略的纯逻辑（1.2）：默认值与合法性收敛在一处，AppConfig 只做存取。
 *
 * 为什么不把判断散在 UI：出现过「开关显示开、实际没模型」的假就绪，
 * 只有把 [enabledOrDefault] 收成单一函数，才能保证「启用 ⇒ 必有档位」。
 */
object LocalLlmPrefs {

    const val POLICY_LOCAL = "local"
    const val POLICY_ONLINE = "online"
    const val POLICY_OFF = "off"

    /** 策略字符串 → 合法值；空/未知一律回落本地优先（不偷偷允许云端） */
    fun policyOf(raw: String?): String = when (raw?.trim()?.lowercase()) {
        POLICY_ONLINE -> POLICY_ONLINE
        POLICY_OFF -> POLICY_OFF
        else -> POLICY_LOCAL
    }

    /** 启用判据：显式开启且已选档位；否则一律关闭（防"开了但没模型"） */
    fun enabledOrDefault(rawEnabled: Boolean, profileId: String?): Boolean =
        rawEnabled && !profileId.isNullOrBlank()

    /** 速度文案（设置页自检结果；0/非法显示"未测"） */
    fun speedText(tokPerSec: Float): String =
        if (tokPerSec <= 0f) "未测" else String.format(java.util.Locale.US, "%.1f tok/s", tokPerSec)
}
