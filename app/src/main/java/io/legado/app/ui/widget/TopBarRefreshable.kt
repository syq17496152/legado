package io.legado.app.ui.widget

/**
 * 顶栏样式刷新统一入口（顶栏包 §2.1 / §2.2，2026-09-25）。
 *
 * 现状问题（09-08 用户报障的真实根因）：`MainActivity.refreshMainTopBars` 对顶栏**按类型分叉**——
 * `MainTopBarView` 走全量 `refreshStyle()`、`TitleBar` 只刷背景色 ⇒ 用户观感是「不同栏刷新结果不一致」，
 * 诉求实为「四栏刷新一致」而非「消灭某一种顶栏实现」。
 *
 * 归一后：View 侧顶栏（[MainTopBarView] / [TitleBar]）统一实现本接口，宿主遍历时**只按接口调用**，
 * 不再关心顶栏类型；**签名早退**（§1.3/§2.2）在各实现内部完成，判据统一为
 * `TopBarConfig.currentSignature(...)`（含主题色签名 + 顶栏包/样式/搜索态）。
 *
 * Compose 侧顶栏（`GlassTopAppBar`）不在此接口范围：它是 Compose 组合（非 View 树节点），
 * 刷新由 `ThemeSync.version` 订阅驱动，但**判据同源**（`TopBarConfig.currentSignature`）。
 */
interface TopBarRefreshable {

    /**
     * 主题 / 顶栏包 / 壁纸 / 圆角 / 字号等样式变化后刷新本顶栏。
     *
     * @param force `true` = 跳过签名早退强制全量刷新（用于「变更事件」类触发，如 `TOP_BAR_CHANGED`）；
     *              `false`（默认）= 签名未变则直接返回，避免重复刷新。
     */
    fun refreshTopBarStyle(force: Boolean = false)
}