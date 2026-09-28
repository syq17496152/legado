package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.web.TokenManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Web 服务设置页（web-mcp-productization 一期 · §6 / F 线）的**纯逻辑层**。
 *
 * 只放判据与格式化：不含 Android 平台调用、不含 Compose 依赖 ⇒ 可直接 JVM 单测，
 * 也让"首启引导该不该弹 / 令牌状态怎么显示"这类判断脱离 UI 骨架单独被验证。
 */
internal object WebServiceSettingsLogic {

    /**
     * S2 首启引导卡是否展示（任务 §6.3 / SC-1-18）。
     *
     * 判据 = **服务运行中 且 首启标志未置位**：
     * - 只判标志 ⇒ 会给尚未开启服务的用户弹一张"下一步怎么做"的卡，而卡上三个动作
     *   （浏览器打开 / 复制地址 / 生成只读令牌）全部依赖服务在跑 ⇒ 属误导；
     * - 只判运行态 ⇒ 每次进页面都弹，退化为常驻卡。
     */
    fun shouldShowFirstLaunchGuide(firstLaunchDone: Boolean, serviceRunning: Boolean): Boolean =
        serviceRunning && !firstLaunchDone

    /**
     * 服务运行摘要（**设置页服务行 / 地址活字牌 / 我的页开关副标题共用一处措辞**）。
     *
     * 三种情形：未运行 → [stoppedText]；运行中且有地址 → 地址；运行中但地址未就绪 →
     * [pendingText]。第三种是关键：旧实现（`MySettingsData.webServiceUiState`）在此情形下
     * 会渲染成**空行**（`isRun=true` 却取到空地址），用户看到一片空白。
     */
    fun serviceSummaryText(
        serviceRunning: Boolean,
        hostAddress: String,
        stoppedText: String,
        pendingText: String
    ): String = when {
        !serviceRunning -> stoppedText
        hostAddress.isNotBlank() -> hostAddress
        else -> pendingText
    }

    /**
     * 令牌状态文案：未生成（`generatedAt == null`）给 [neverText]；
     * 已生成交调用方格式化 —— 本层不引入日期模式，保持纯函数。
     */
    fun tokenStatusText(
        generatedAt: Long?,
        neverText: String,
        formatted: (Long) -> String
    ): String = if (generatedAt == null || generatedAt <= 0L) neverText else formatted(generatedAt)

    /** 令牌生成时间显示格式（与页面文案 `已生成于 %s` 搭配）。 */
    fun formatGeneratedAt(millis: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("MM-dd HH:mm", locale).format(Date(millis))

    /**
     * 令牌级别 → 标题资源（三级列表的展示顺序与标题**由本表单源决定**）。
     *
     * [TokenManager.Level.NONE] 不可生成（[TokenManager.generate] 会拒绝），
     * 也不在 [TokenManager.listStatus] 的返回集合内 ⇒ 无渲染资源，直接判为编程错误。
     */
    fun levelTitleRes(level: TokenManager.Level): Int = when (level) {
        TokenManager.Level.READONLY -> R.string.web_token_level_readonly
        TokenManager.Level.MANAGE -> R.string.web_token_level_manage
        TokenManager.Level.ADMIN -> R.string.web_token_level_admin
        TokenManager.Level.NONE -> error("NONE 不是可渲染的令牌级别")
    }

    /** 令牌管理区展示顺序（权限升序，与 [TokenManager.listStatus] 返回顺序一致）。 */
    fun tokenLevelsInDisplayOrder(): List<TokenManager.Level> = listOf(
        TokenManager.Level.READONLY,
        TokenManager.Level.MANAGE,
        TokenManager.Level.ADMIN
    )
}
