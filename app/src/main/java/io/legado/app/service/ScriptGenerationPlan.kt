package io.legado.app.service

/**
 * 3.5 剧本生成的「请求规划」纯函数（服务与 UI 同源，避免两处各写一套窗口规则）。
 *
 * 抽出来的理由：窗口（当前章 + 预取几章）与正文切段规则一旦在 Activity 与服务里各写一份，
 * 就会出现「页面排了 2 章、服务只跑 1 章」这类对不上的问题，且无法在 JVM 上验证。
 */
object ScriptGenerationPlan {

    /** 自动生成时预取的后继章数（与 `ScriptSchedulerPolicy.windowSize` 的常态 2 章一致） */
    const val PREFETCH_CHAPTERS = 1

    /**
     * @param currentIndex 当前章号（非法 <0 返回空表，绝不入队脏章号）
     * @param force 手动「重新生成」只做当前章；自动生成才预取后继章
     */
    fun chaptersFor(currentIndex: Int, force: Boolean): List<Int> = when {
        currentIndex < 0 -> emptyList()
        force -> listOf(currentIndex)
        else -> (currentIndex..currentIndex + PREFETCH_CHAPTERS).toList()
    }

    /** 正文按行切段：去首尾空白、丢空行（与朗读侧 `contentList` 的可见文本口径一致） */
    fun paragraphsOf(content: String?): List<String> =
        content?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
}
