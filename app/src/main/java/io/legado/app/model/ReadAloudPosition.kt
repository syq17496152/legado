package io.legado.app.model

/**
 * C1 朗读原语化（AD-C1-5）：全引擎与阅读 UI 共享的朗读位置（章节绝对字符位）。
 * 字段与 legadoC 对齐，禁止混入 AI 多角色链字段（cueIndex 等）——多角色引擎
 * 同样只需发布此位置流（R7 接口预留）。
 */
data class ReadAloudPosition(
    val chapterIndex: Int,
    val chapterPosition: Int,
)

/**
 * 引擎确认的位置更新：携带被替换的前一位置与单调代数（ReadAloudPositionUpdate 五字段不扩）。
 *
 * @param position 本次朗读位置
 * @param previousPosition 被替换的前一位置（首个事件为 null，UI 跟随规则据此不跟随）
 * @param switchConfirmed 是否确认了 beginPositionSwitch 登记的起点切换（两阶段握手）
 * @param generation 单调递增代数，消费端防乱序闸门
 * @param syncView 用户显式传送标记（拖进度条/上一章/下一章）：观察者直接走原语 B，不走跟随判定
 */
data class ReadAloudPositionUpdate(
    val position: ReadAloudPosition,
    val previousPosition: ReadAloudPosition?,
    val switchConfirmed: Boolean,
    val generation: Long,
    val syncView: Boolean,
)
