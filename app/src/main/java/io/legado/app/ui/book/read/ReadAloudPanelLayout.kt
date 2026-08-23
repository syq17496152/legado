package io.legado.app.ui.book.read

object ReadAloudPanelLayout {

    fun playbackTargetChanged(
        currentCueIndex: Int,
        currentChapterIndex: Int,
        currentPlanKey: String,
        nextCueIndex: Int,
        nextChapterIndex: Int,
        nextPlanKey: String
    ): Boolean {
        return currentCueIndex != nextCueIndex ||
                currentChapterIndex != nextChapterIndex ||
                currentPlanKey != nextPlanKey
    }

    fun centeredScrollDelta(
        viewportStartOffset: Int,
        viewportEndOffset: Int,
        itemOffset: Int,
        itemSize: Int
    ): Float {
        val viewportCenter = (viewportStartOffset + viewportEndOffset) / 2f
        val itemCenter = itemOffset + itemSize.coerceAtLeast(0) / 2f
        return itemCenter - viewportCenter
    }
}
