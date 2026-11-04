package io.legado.app.help.readaloud.casting

import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalLlmPrefs

/**
 * AI 任务路由（6.1）：同一任务按「本地 / 云端 / 关闭」策略选择执行方，并统一回传 `source` 便于溯源。
 *
 * 关键约束（design 6.1/6.5）：
 * - 云端兜底**必须**用户允许（`onlineAllowed`），未允许时宁可失败也不偷偷出端；
 * - 本地慢（`localSlow`）才考虑回落，正常本地优先；
 * - 任何失败都返回可执行原因（不是"未知错误"）。
 */
object AiTaskRouter {

    enum class Task { SCRIPT, BGM, SUMMARY, REPLACE_RULE, HIGHLIGHT_DRAFT }

    enum class Policy { LOCAL, ONLINE, OFF }

    enum class Executor { LOCAL, ONLINE, NONE }

    data class Facts(
        val localEnabled: Boolean,
        val localReady: Boolean,
        /** 本地已就绪但实测偏慢（tok/s 低于设计止损线） */
        val localSlow: Boolean = false,
        val onlineConfigured: Boolean,
        /** 用户是否允许云端兜底（隐私边界，见 6.5） */
        val onlineAllowed: Boolean
    )

    data class Route(val executor: Executor, val reason: String) {
        /** 溯源字段（日志/UI 统一口径） */
        val source: String
            get() = when (executor) {
                Executor.LOCAL -> "local"
                Executor.ONLINE -> "online"
                Executor.NONE -> "none"
            }

        val available: Boolean get() = executor != Executor.NONE

        /** 面向用户的中文来源标签（UI 与结果消息共用，避免两套写法） */
        val sourceLabel: String
            get() = when (executor) {
                Executor.LOCAL -> "本地"
                Executor.ONLINE -> "云端"
                Executor.NONE -> "未启用"
            }
    }

    /** 本地偏慢止损线（tok/s，低于此值长文本任务才考虑回落云端） */
    const val SLOW_TOK_PER_SEC = 2f

    /**
     * 6.4 接线：调用方只提供「该任务云端是否已配置」，本地开关/就绪/速度统一从这里取，
     * 避免每个服务各写一套布尔组合（判定漂移会让"关了还用云端"这类问题复现）。
     */
    fun routeFor(onlineConfigured: Boolean): Route {
        val policy = when (LocalLlmPrefs.policyOf(AppConfig.aiTaskPolicy)) {
            LocalLlmPrefs.POLICY_OFF -> Policy.OFF
            LocalLlmPrefs.POLICY_ONLINE -> Policy.ONLINE
            else -> Policy.LOCAL
        }
        val localEnabled = AppConfig.localLlmEnabled
        val localReady = localEnabled &&
                AppConfig.localLlmProfileId.isNotBlank() &&
                AppConfig.localLlmUnsupportedReason.isBlank()
        val speed = AppConfig.localLlmSpeedTokPerSec
        return route(
            policy = policy,
            facts = Facts(
                localEnabled = localEnabled,
                localReady = localReady,
                // speed=0 表示还没自检过，不能据此判慢（否则首次使用必定回落云端）
                localSlow = localReady && speed > 0f && speed < SLOW_TOK_PER_SEC,
                onlineConfigured = onlineConfigured,
                // 云端模型由用户自行配置，沿用既有行为允许使用（正文出端边界见 6.5）
                onlineAllowed = true
            )
        )
    }

    fun route(policy: Policy, facts: Facts): Route = when (policy) {
        Policy.OFF -> Route(Executor.NONE, "该任务已关闭")
        Policy.ONLINE -> onlineOrNone(facts, "按策略使用云端")
        Policy.LOCAL -> localFirst(facts)
    }

    private fun localFirst(facts: Facts): Route {
        if (facts.localEnabled && facts.localReady) {
            if (!facts.localSlow) return Route(Executor.LOCAL, "本地就绪")
            // 本地偏慢：只有云端可用且被允许时才回落，否则仍用本地（不越权出端）
            return if (canUseOnline(facts)) {
                Route(Executor.ONLINE, "本地偏慢，按允许的云端兜底")
            } else {
                Route(Executor.LOCAL, "本地偏慢，但未允许云端：继续本地")
            }
        }
        val why = if (!facts.localEnabled) "本地 AI 已关闭" else "本地模型未就绪"
        return if (canUseOnline(facts)) {
            Route(Executor.ONLINE, "$why，回落云端")
        } else {
            Route(Executor.NONE, "$why，且未允许云端")
        }
    }

    private fun onlineOrNone(facts: Facts, reason: String): Route =
        if (canUseOnline(facts)) Route(Executor.ONLINE, reason) else Route(Executor.NONE, "云端不可用：未配置或未允许")

    private fun canUseOnline(facts: Facts): Boolean =
        facts.onlineConfigured && facts.onlineAllowed
}
