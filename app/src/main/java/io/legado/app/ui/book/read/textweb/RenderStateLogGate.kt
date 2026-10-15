package io.legado.app.ui.book.read.textweb

/**
 * 沙箱状态日志门禁（同状态名去重）。
 *
 * 背景（2026-10-15 真机实测）：模板沙箱在稳态（`settle` / `page-frame`）会**持续上报**同一
 * 状态名，`TemplateRenderBackend.onRenderState` 的兜底分支每次上报都写一行 `Log.e` ⇒ 实测
 * **138 行 / 10 秒**。两个后果：①JS 桥线程上的同步日志开销；②logcat 环形缓冲被刷爆，更早的
 * 关键行（如模板内容准备时的 `prepare: … manual=N`）被挤出环形缓冲 ⇒ 排查时表现为「该日志
 * 压根不存在」的假象（本轮 L2 验证即先被此假象误导，一度怀疑 HL-3 未接线）。
 *
 * 口径：**状态名（首个逗号前的 token）变化才输出**。同状态名的后续上报一律抑制 —— 状态串里
 * 的诊断数值（body/page 等）在状态名变化那一行本身就是最新值，无需重复刷屏。
 */
object RenderStateLogGate {

    /** 取状态名：`settle,shell=opening,body=0,…` ⇒ `settle` */
    fun stateTag(state: String): String = state.substringBefore(',')

    fun shouldLog(previousTag: String?, currentTag: String): Boolean = previousTag != currentTag
}
