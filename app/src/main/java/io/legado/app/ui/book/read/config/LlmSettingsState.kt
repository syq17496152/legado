package io.legado.app.ui.book.read.config

import io.legado.app.help.config.LocalLlmPrefs
import io.legado.app.help.llm.LocalLlmFeature

/**
 * 2.4 本地 AI 设置页的纯状态（JVM 可单测）。
 *
 * 设计口径（AD-14 / 7.7）：
 * - 低端机或引擎缺失 ⇒ `showEntry=false`（整块不显示，不是灰色开关）；
 * - 开关只在「引擎在 + 设备够 + 有模型」时可拨动，其余情况给出**可执行原因**；
 * - 任务路由三档（local/online/off）与 `LocalLlmPrefs` 单源，页面不自己解释字符串。
 */
data class LlmModelRow(
    val id: String,
    val title: String,
    val sizeText: String,
    val active: Boolean
)

data class LlmSettingsUi(
    val showEntry: Boolean,
    val switchChecked: Boolean,
    val switchEnabled: Boolean,
    val reason: String,
    val models: List<LlmModelRow>,
    val policy: String,
    val policyLabel: String,
    val speedText: String,
    val importHint: String
)

object LlmSettingsState {

    data class Facts(
        val engineAvailable: Boolean,
        val modelInstalled: Boolean,
        val totalRamMb: Long,
        val enabled: Boolean,
        val policy: String,
        val speedTokPerSec: Float,
        /** (id, 标题, 字节) */
        val models: List<Triple<String, String, Long>> = emptyList(),
        val activeModelId: String = ""
    )

    fun build(facts: Facts): LlmSettingsUi {
        val verdict = LocalLlmFeature.evaluate(
            LocalLlmFeature.Facts(
                engineAvailable = facts.engineAvailable,
                modelInstalled = facts.modelInstalled,
                totalRamMb = facts.totalRamMb,
                enabled = facts.enabled
            )
        )
        val switchEnabled = facts.engineAvailable && !verdict.hideEntry && facts.modelInstalled
        return LlmSettingsUi(
            showEntry = !verdict.hideEntry,
            switchChecked = facts.enabled && switchEnabled,
            switchEnabled = switchEnabled,
            reason = verdict.message,
            models = facts.models.map { (id, title, bytes) ->
                LlmModelRow(
                    id = id,
                    title = title,
                    sizeText = sizeText(bytes),
                    active = id == facts.activeModelId.ifBlank { facts.models.firstOrNull()?.first.orEmpty() }
                )
            },
            policy = facts.policy,
            policyLabel = policyLabel(facts.policy),
            speedText = speedText(facts.speedTokPerSec),
            importHint = if (facts.engineAvailable) {
                "支持 .gguf 模型；导入后可在「剧本/规则助手」里使用"
            } else {
                "当前版本未集成推理引擎，导入的模型暂不可用"
            }
        )
    }

    fun sizeText(bytes: Long): String {
        val mb = bytes / (1024f * 1024f)
        return if (mb >= 1024f) String.format("%.1fGB", mb / 1024f) else String.format("%.0fMB", mb)
    }

    fun policyLabel(policy: String): String = when (LocalLlmPrefs.policyOf(policy)) {
        LocalLlmPrefs.POLICY_LOCAL -> "仅本地（不出端）"
        LocalLlmPrefs.POLICY_ONLINE -> "云端优先"
        else -> "关闭 AI 任务"
    }

    fun speedText(tokPerSec: Float): String =
        if (tokPerSec <= 0f) "未自检" else String.format("%.1f tok/s", tokPerSec)

    /**
     * 「下载推荐档」入口的显示标题（空串 = 不显示）。
     *
     * 已装同 id 时不能重复引导下载：用户会以为"下载失败还得再下一次"，
     * 实际只是把同一个文件重复拉一遍（396MB）。
     */
    fun downloadEntryTitle(
        profileId: String?,
        profileTitle: String?,
        installedIds: List<String>
    ): String {
        if (profileId.isNullOrBlank() || profileTitle.isNullOrBlank()) return ""
        return if (profileId in installedIds) "" else profileTitle
    }
}
