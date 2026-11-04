package io.legado.app.help.readaloud.casting

/**
 * 智能多角色一键编排（4.7）：把「识别角色 → 配声 → 剧本就绪检查 → 激活模板 → 起播」
 * 收敛成一次点击，并把每一步的失败翻译成**可执行的下一步**，而不是笼统的"准备失败"。
 *
 * 判定做成纯函数的原因：识别/配声/分镜在真机上各有失败面，但"失败后该提示什么、
 * 哪些允许继续起播"是稳定策略，必须由单测锁定，否则文案会随实现漂移。
 */
object ReadAloudOneTapFlow {

    enum class Step {
        /** 一切就绪：直接起播 */
        START,

        /** 离线引擎未就绪：**不阻断**，回落系统语音起播 */
        ENGINE_FALLBACK,

        INSERT_BOOK,
        OPEN_CHAPTER,
        IMPORT_VOICE,
        IDENTIFY_CHARACTER,
        ASSIGN_VOICE,

        /** 剧本（分镜）未就绪：**不阻断**，先听后生成 */
        STORYBOARD_PENDING,

        /** 多角色模板启用失败：**不阻断**，用当前模板起播 */
        TEMPLATE_FALLBACK
    }

    data class Facts(
        val engineReady: Boolean,
        val hasBook: Boolean,
        val hasChapter: Boolean,
        val voiceCount: Int,
        val characterCount: Int,
        val assignedCount: Int,
        val storyboardSegments: Int
    )

    data class Result(val step: Step, val blocked: Boolean, val message: String)

    /**
     * 判定下一步。
     *
     * 阻断只限「没有书 / 没有章节 / 没有音色」三种硬缺料——其余都能用现有能力起播：
     * 引擎缺失回落系统语音，角色缺失用默认音，分镜缺失边听边生成，模板启用失败用当前模板。
     * 这样一键编排不会因为"非关键步骤"把用户挡在听书之外。
     */
    fun plan(facts: Facts, templateActivated: Boolean = true): Result {
        if (!facts.hasBook) {
            return Result(Step.INSERT_BOOK, true, "请先在阅读页打开一本书，再回来点「听书」")
        }
        if (!facts.hasChapter) {
            return Result(Step.OPEN_CHAPTER, true, "这本书还没打开章节：先翻到正文任意一章再试")
        }
        // 引擎判定先于音色：引擎没装时"去导音色"是错的下一步（导了也用不上）
        if (!facts.engineReady) {
            return Result(
                Step.ENGINE_FALLBACK, false,
                "离线引擎未就绪：本次先用系统语音朗读，可在「引擎与音色」里启用离线"
            )
        }
        if (facts.voiceCount <= 0) {
            return Result(
                Step.IMPORT_VOICE, true,
                "还没有可用音色：到「引擎与音色」下载或导入一个音色包（也可以自录）"
            )
        }
        if (facts.characterCount <= 0) {
            return Result(
                Step.IDENTIFY_CHARACTER, true,
                "没识别到角色：换到有对白的章节再试，或在「声音资源」里导入离线人名模型后重试"
            )
        }
        if (facts.assignedCount <= 0) {
            return Result(
                Step.ASSIGN_VOICE, true,
                "角色还没有音色：到「引擎与音色 → 角色配音」点「重新配声」后再回来"
            )
        }
        if (facts.storyboardSegments <= 0) {
            return Result(
                Step.STORYBOARD_PENDING, false,
                "本章分镜还没生成：先直接听，识别完成后角色换声会自动生效"
            )
        }
        if (!templateActivated) {
            return Result(
                Step.TEMPLATE_FALLBACK, false,
                "多角色模板启用失败：先用当前模板听，可在「选角模板」里手动选一个"
            )
        }
        return Result(Step.START, false, "已准备好：开始听书")
    }
}
