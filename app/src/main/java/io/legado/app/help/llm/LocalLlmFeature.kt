package io.legado.app.help.llm

/**
 * 2.5 本地 AI 三层可用性判定（design AD-14 / 7.7「特性关闭 UI 不显示入口」）。
 *
 * 判定顺序固定（先排除最不可修的原因）：
 * ① 安装包有没有引擎（so 未集成 ⇒ 任何设置都救不了，直接判不可用）
 * ② 设备够不够（RAM < 8GB ⇒ 明确不支持，不是灰色开关）
 * ③ 有没有模型（未导入/未下载 ⇒ 引导去资源中心）
 * ④ 用户开没开（前三条都满足才轮到开关）
 *
 * 纯函数：设置页与资源中心都调它，避免两处口径漂移。
 */
object LocalLlmFeature {

    enum class Reason {
        ENGINE_MISSING,
        DEVICE_TOO_WEAK,
        MODEL_MISSING,
        DISABLED,
        READY
    }

    data class Facts(
        val engineAvailable: Boolean,
        val modelInstalled: Boolean,
        val totalRamMb: Long,
        val enabled: Boolean
    )

    data class Verdict(
        val usable: Boolean,
        val reason: Reason,
        val message: String
    ) {
        /** 不显示本地 AI 入口（低端机/引擎缺失）⇒ 不是"灰色开关"，而是整块隐藏 */
        val hideEntry: Boolean
            get() = reason == Reason.ENGINE_MISSING || reason == Reason.DEVICE_TOO_WEAK
    }

    fun evaluate(facts: Facts, lowEndRamMb: Long = 8000L): Verdict = when {
        !facts.engineAvailable -> Verdict(
            usable = false,
            reason = Reason.ENGINE_MISSING,
            message = "当前安装包未包含本地 AI 引擎（可更新版本后再试）"
        )
        facts.totalRamMb < lowEndRamMb -> Verdict(
            usable = false,
            reason = Reason.DEVICE_TOO_WEAK,
            message = "设备内存不足（本地 AI 需 8GB 及以上，当前约 ${ramText(facts.totalRamMb)}）"
        )
        !facts.modelInstalled -> Verdict(
            usable = false,
            reason = Reason.MODEL_MISSING,
            message = "还没有模型：请在声音资源中心导入或下载"
        )
        !facts.enabled -> Verdict(
            usable = false,
            reason = Reason.DISABLED,
            message = "本地 AI 未开启"
        )
        else -> Verdict(usable = true, reason = Reason.READY, message = "本地 AI 已就绪")
    }

    /** RAM 文本：整数 MB → "x.xGB"，设置页与降级文案共用 */
    fun ramText(totalRamMb: Long): String = String.format("%.1fGB", totalRamMb / 1024f)
}
