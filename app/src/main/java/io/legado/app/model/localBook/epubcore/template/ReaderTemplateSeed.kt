package io.legado.app.model.localBook.epubcore.template

/**
 * 模板**稳定随机种子**（epub-md-rich-rendering 阶段 4.15 / TPL-15 C4）。
 *
 * 契约：`seed = f(书名, 章节)` —— 同一章**任何时候**都得到同一个种子，
 * 使作者可以用 `--rp-seed` 做"看似随机"的装饰（星图点位、纸纹角度）而**不牺牲可复现性**：
 * 同章重排/回首页/读快照/切主题再回来，看到的都是同一张画面。
 *
 * 为什么不用 `Math.random()` 或时间戳：那会让每次重排（翻页回首页、改字号、切主题）
 * 都换一张图 —— 用户感受是"页面在乱动"，且**无法复现**（截图与他人的观感都不一致）。
 *
 * 纯函数（无 Android 依赖）⇒ JVM 逐项断言稳定性与区分度。
 */
internal object ReaderTemplateSeed {

    /** 种子取值上限（作者把它当"随机索引"用，取 0..9999 足够且便于书写）。 */
    const val Modulus = 10_000

    /**
     * 计算种子（FNV-1a 32 位，再取模）。
     *
     * 为什么用 FNV-1a 而不是 `String.hashCode()`：后者在不同 JDK/ART 上**实现稳定**但
     * 属于实现细节（且对短字符串分布不佳，相邻章节易撞）；FNV-1a 是显式算法的定值实现，
     * 跨平台/跨版本可复现，且分布足够均匀。
     */
    fun of(bookName: String, chapterTitle: String): Int {
        val source = "$bookName\u0000$chapterTitle"
        var hash = -0x7ee3623b // FNV offset basis (2166136261)
        for (char in source) {
            hash = hash xor char.code
            hash *= 0x01000193 // FNV prime (16777619)
        }
        // 取非负余数：Java/Kotlin 的 % 保留符号 ⇒ 直接取模会出现负种子（作者按索引用时越界）
        return (hash and 0x7FFFFFFF) % Modulus
    }
}