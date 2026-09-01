package io.legado.app.service

/**
 * C1 朗读原语化：段进度快照（对齐 LC/service/ReadAloudProgress.kt）。
 * 经 [BaseReadAloudService.publishReadAloudProgress] 发布于 EventBus.READ_ALOUD_PARAGRAPH_PROGRESS。
 *
 * AD-C1-9：内嵌 enum Kind 用 enum 而非 @IntDef——非位标志场景，
 * 需 name 持久化反解 + entries 遍历展示（对齐 C5 DR-C5-2 同款裁决）。
 */
data class ReadAloudProgress(
    val chapterIndex: Int,
    val position: Int,
    val total: Int,
    val kind: Kind,
) {
    init {
        require(chapterIndex >= 0) { "chapterIndex must be non-negative: $chapterIndex" }
        require(total > 0) { "total must be positive: $total" }
        when (kind) {
            Kind.PARAGRAPH -> require(position in 0 until total) {
                "paragraph position must be within total: position=$position, total=$total"
            }
            Kind.TIME -> require(position in 0..total) {
                "time position must be within total: position=$position, total=$total"
            }
        }
    }

    enum class Kind { PARAGRAPH, TIME }
}
