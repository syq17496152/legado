package io.legado.app.model

/**
 * 朗读跨章推进的「待续播」判定（纯函数，IF-14-L 真机铁证 2026-11-01）。
 *
 * 背景：朗读在章末 `nextChapter()` → `moveToNextChapter(fromReadAloud = true)` 推进到相邻章时，
 * 若该章已预载但**尚未排版完成**（`isCompleted == false`），
 * `curPageChanged(fromReadAloud = true)` 会因 `isCompleted` 判定直接跳过续播；
 * 而排版完成回调只认 `readAloudPendingLoadChapterIndex` 命中才补播
 * ⇒ 不登记就表现为「章末朗读静默中断、整章 1 秒读完后再无声音」。
 */
internal object ReadAloudChapterAdvance {

    /**
     * @param fromReadAloud 本次推进是否由朗读驱动
     * @param chapterCompleted 目标章是否已完成排版（true 时可立即续播，无需登记）
     * @param chapterIndex 目标章索引
     * @return 需要登记为待续播的章索引；-1 表示无需登记
     */
    fun pendingIndex(fromReadAloud: Boolean, chapterCompleted: Boolean, chapterIndex: Int): Int =
        if (fromReadAloud && !chapterCompleted && chapterIndex >= 0) chapterIndex else -1

    /**
     * 章节「已载入但未排版完成」时发起朗读，是否应改为「置待播标记、等排版完成再补播」。
     *
     * 原 `ReadBook.readAloud()` 在 `isCompleted == false` 时**静默 return**（也不置待播标记），
     * 章末续播正好命中该窗口 ⇒ 表现为跳章成功但无任何声音（IF-14-M 真机 2026-11-01 铁证）。
     *
     * @return true = 暂缓起播（需置待播标记）；false = 可立即起播
     */
    fun shouldDeferUntilCompleted(chapterCompleted: Boolean): Boolean = !chapterCompleted

    /**
     * 服务内部续章 / 媒体键跨章时，`moveToNextChapter` 的 `fromReadAloud` 入参取值。
     *
     * IF-14-M 根因：`BaseReadAloudService.nextChapter()` 曾以 `moveToNextChapter(true)` 调用 ——
     * `true` 绑定的是首个形参 `upContent`，`fromReadAloud` 走默认 `false`
     * ⇒ 朗读驱动的跳章被当成手动导航（跟随状态机脱离 + 待续播索引不登记）
     * ⇒ 跳章成功后服务再无起播指令，表现为无声断流。
     * 因此这里以「朗读服务是否在运行」作为唯一判据，禁止再靠位置入参隐式取值。
     */
    fun fromReadAloudForService(readAloudServiceRunning: Boolean): Boolean =
        readAloudServiceRunning
}
