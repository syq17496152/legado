package io.legado.app.help.config

import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx

/**
 * 阅读产品指标（epub-md-rich-rendering 补充任务 S4；审计 SP-09「无产品指标/埋点」）。
 *
 * 四项指标（全部为**比率**，分母为 0 时按 0 计）：
 * - **md 使用率** = `bookOpenMd / bookOpen`（打开的书里 Markdown 占比）
 * - **模板启用率** = `templateOpen / bookOpen`（打开的书里**真正用上模板面**的占比）
 * - **降级触发率** = `templateDegrade / templateOpen`（用上模板面的书里发生书级降级的占比）
 * - **富渲染失败率** = `richFailure / richAttempt`（请求富渲染注入后未成功渲染的占比）
 *
 * **隐私边界（硬约束，本类即契约）**：只写**固定键**（[MetricKeys]）且取值**只能是 Int**——
 * 不含书名/链接/正文/设备标识/时间戳；**不上报、不联网**，仅存本机偏好供只读查看。
 *
 * 纯逻辑（存储由 [MetricStore] 注入）⇒ 可 JVM 单测（含"只写白名单键"的隐私断言）。
 */
class ReaderMetrics(private val store: MetricStore) {

    /** 计数存取（注入以便单测；Android 侧见 [PrefMetricStore]）。 */
    interface MetricStore {
        fun getInt(key: String): Int
        fun putInt(key: String, value: Int)
    }

    /** 只读快照（含派生比率；不持有任何业务数据）。 */
    data class Snapshot(
        val bookOpens: Int,
        val mdOpens: Int,
        val templateOpens: Int,
        val templateDegrades: Int,
        val richAttempts: Int,
        val richFailures: Int
    ) {
        val mdUsageRate: Double get() = rate(mdOpens, bookOpens)
        val templateUsageRate: Double get() = rate(templateOpens, bookOpens)
        val degradeTriggerRate: Double get() = rate(templateDegrades, templateOpens)
        val richFailureRate: Double get() = rate(richFailures, richAttempts)

        /** 分母 <= 0 ⇒ 0.0（**不得**出现 NaN/Infinity）。 */
        private fun rate(numerator: Int, denominator: Int): Double =
            if (denominator <= 0) 0.0 else numerator.toDouble() / denominator
    }

    /** 打开一本书（`isMarkdown` ⇒ md 使用率分子）。 */
    fun bookOpened(isMarkdown: Boolean) {
        inc(KeyBookOpen)
        if (isMarkdown) inc(KeyBookOpenMd)
    }

    /** 模板面**真正被用上**一次（同书只记一次，由调用方把关）。 */
    fun templateSurfaceUsed() = inc(KeyTemplateOpen)

    /** 模板面发生**书级降级**（AD-28②）。 */
    fun templateDegraded() = inc(KeyTemplateDegrade)

    /** 请求一次富渲染注入（mermaid / KaTeX）。 */
    fun richRenderRequested() = inc(KeyRichAttempt)

    /** 富渲染注入未成功（运行时缺失 / 沙箱报错）。 */
    fun richRenderFailed() = inc(KeyRichFailure)

    fun snapshot(): Snapshot = Snapshot(
        bookOpens = store.getInt(full(KeyBookOpen)),
        mdOpens = store.getInt(full(KeyBookOpenMd)),
        templateOpens = store.getInt(full(KeyTemplateOpen)),
        templateDegrades = store.getInt(full(KeyTemplateDegrade)),
        richAttempts = store.getInt(full(KeyRichAttempt)),
        richFailures = store.getInt(full(KeyRichFailure))
    )

    /** 清零（只动本类白名单键，不误伤其它偏好）。 */
    fun reset() = Keys.forEach { store.putInt(full(it), 0) }

    private fun inc(key: String) {
        val k = full(key)
        store.putInt(k, store.getInt(k) + 1)
    }

    companion object {
        /** 键前缀：与其它偏好隔离，便于审计"只写了这些键"。 */
        const val KeyPrefix = "readerMetrics."

        const val KeyBookOpen = "bookOpen"
        const val KeyBookOpenMd = "bookOpenMd"
        const val KeyTemplateOpen = "templateOpen"
        const val KeyTemplateDegrade = "templateDegrade"
        const val KeyRichAttempt = "richAttempt"
        const val KeyRichFailure = "richFailure"

        /** 允许落盘的键全集（**隐私契约**：持久层只允许出现这些键，取值只能是 Int）。 */
        val Keys = listOf(
            KeyBookOpen, KeyBookOpenMd, KeyTemplateOpen,
            KeyTemplateDegrade, KeyRichAttempt, KeyRichFailure
        )

        /** 落盘键的完整形态（带前缀）。 */
        val MetricKeys: Set<String> = Keys.map { KeyPrefix + it }.toSet()

        fun full(key: String): String = KeyPrefix + key

        /** 应用单例门面（走应用偏好；只写 [MetricKeys]）。 */
        val app: ReaderMetrics by lazy { ReaderMetrics(PrefMetricStore) }
    }

    /** 应用偏好实现（只做 Int 读写，无任何业务字段）。 */
    private object PrefMetricStore : MetricStore {
        override fun getInt(key: String): Int = appCtx.getPrefInt(key, 0)
        override fun putInt(key: String, value: Int) = appCtx.putPrefInt(key, value)
    }
}
