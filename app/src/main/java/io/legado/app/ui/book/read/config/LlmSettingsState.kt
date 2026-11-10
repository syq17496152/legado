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

    /** 模型就绪后的自动配置结论（用户裁定：下载/导入完成即帮用户配好本地 AI） */
    data class AutoConfigureResult(
        val enableLocalLlm: Boolean,
        val message: String
    )

    /**
     * 模型就绪 → 自动配置本地 AI。
     *
     * 只在「引擎在 + 设备够」时自动开启（与 [LocalLlmFeature] 同一判定口径，避免出现
     * 低端机/缺引擎设备被强制打开后再静默失效的假就绪）。
     * 不擅自改 AI 任务策略：用户可能刻意选了「云端优先 / 关闭」，只给出可执行提示。
     */
    fun autoConfigureAfterModelReady(
        engineAvailable: Boolean,
        totalRamMb: Long,
        policy: String
    ): AutoConfigureResult {
        val verdict = LocalLlmFeature.evaluate(
            LocalLlmFeature.Facts(
                engineAvailable = engineAvailable,
                modelInstalled = true,
                totalRamMb = totalRamMb,
                enabled = true
            )
        )
        if (!verdict.usable) {
            return AutoConfigureResult(false, "（未自动开启本地 AI：${verdict.message}）")
        }
        val policyNow = LocalLlmPrefs.policyOf(policy)
        val message = if (policyNow == LocalLlmPrefs.POLICY_LOCAL) {
            "（已自动开启本地 AI，AI 助手将优先使用端侧模型）"
        } else {
            "（已设为当前模型；AI 任务策略为「${policyLabel(policyNow)}」，可在本页切换为仅本地）"
        }
        return AutoConfigureResult(true, message)
    }

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

    /**
     * 调试注入后的 IPC 自检日志文案（仅 debug 路径会调用）。
     *
     * 为什么收在这里：`ai_tests/scripts/l3_local_ai_generate.py` 用「连接推理进程 成功」断言链路成立，
     * 文案是脚本契约的一部分 —— 放纯函数里既能单测锁定，也避免后续改字把 L3 判据改坏。
     */
    fun ipcSelfCheckMessage(connected: Boolean, phase: String = ""): String =
        if (connected) {
            "DebugLlmOverride: 连接推理进程 成功（状态=${phase.ifBlank { "未知" }}）"
        } else {
            "DebugLlmOverride: 连接推理进程 失败"
        }

    /**
     * 自检结果文案（设置页 `runBenchmark` 用）。
     *
     * 失败原因必须**带出来**：端侧最常见的失败是「模型未就绪/内存不足/超时」，
     * 只说"自检失败"会让用户完全无从下手（与 E7「静默跳过」同类）。
     */
    fun benchMessage(
        ok: Boolean,
        loadMs: Long,
        tokenCount: Int,
        tokPerSec: Float,
        failureMessage: String
    ): String = if (ok) {
        "自检完成：加载 ${loadMs}ms，生成 $tokenCount token，" +
                String.format(java.util.Locale.US, "%.1f", tokPerSec) + " tok/s"
    } else {
        "自检失败：${failureMessage.ifBlank { "未知原因" }}"
    }
}
