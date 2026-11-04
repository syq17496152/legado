package io.legado.app.help.readaloud.casting

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import splitties.init.appCtx

/**
 * 四层继承解析器（4.8）：同一项朗读配置按
 * 「L3 本章段落 > L2 本书 > L1 全局 > 内置默认」取值，并回传**来源层**。
 *
 * 为什么必须回传来源层：只给最终值的话，用户改了书级覆盖后看不到"它压住了全局"，
 * 想恢复全局也不知该去哪一层清空（此前只能猜）；设置页因此把"当前来自哪一层"直接写在摘要里。
 *
 * 纯函数 [resolve] 不读任何存储（JVM 可测）；[resolveForBook] 才做真实存储读取，
 * 供页面/服务同步调用（无挂起点，杜绝 runBlocking 回潮）。
 */
object EffectiveReadAloudConfig {

    enum class Layer(val title: String) {
        SEGMENT("本章"),
        BOOK("本书"),
        GLOBAL("全局"),
        BUILTIN("内置默认")
    }

    data class Value<T>(val value: T, val layer: Layer)

    data class Resolved(
        /** 生效模板 id；null = 单声（未激活多角色模板） */
        val templateId: Value<String?>,
        val speechRate: Value<Int>,
        /** 是否跟随系统语速（全局口径；书级/章段暂无独立覆盖） */
        val followSystemRate: Value<Boolean>
    )

    /** 与 `AppConfig.defaultSpeechRate` 一致的内置默认（0~45 档位，5 = 1.0x） */
    const val DEFAULT_SPEECH_RATE = 5

    /**
     * 纯函数（JVM 可测）：只做层级判定，不读存储。
     * 每项独立取值，空值（null 或空白字符串）视为「该层未覆盖」。
     *
     * @param segmentTemplateId L3 章段模板覆盖（剧本层 3.1 落地前恒空）
     * @param segmentSpeechRate L3 章段语速覆盖
     * @param bookTemplateId L2 书级模板覆盖（空 = 跟随全局）
     * @param bookSpeechRate L2 书级语速覆盖（null = 跟随全局；0 是合法档位，不当空值）
     * @param globalTemplateId L1 全局激活模板（空 = 单声）
     * @param globalSpeechRate L1 全局语速（null = 用内置默认）
     * @param globalFollowSystem L1 是否跟随系统语速（null = 用内置默认 true）
     */
    fun resolve(
        segmentTemplateId: String? = null,
        segmentSpeechRate: Int? = null,
        bookTemplateId: String? = null,
        bookSpeechRate: Int? = null,
        globalTemplateId: String? = null,
        globalSpeechRate: Int? = null,
        globalFollowSystem: Boolean? = null,
        builtinSpeechRate: Int = DEFAULT_SPEECH_RATE,
        builtinFollowSystem: Boolean = true
    ): Resolved {
        val template: Value<String?> =
            pick<String?>(nonBlank(segmentTemplateId), nonBlank(bookTemplateId), nonBlank(globalTemplateId))
                ?: Value(null, Layer.BUILTIN)
        val rate = pick(segmentSpeechRate, bookSpeechRate, globalSpeechRate)
            ?: Value(builtinSpeechRate, Layer.BUILTIN)
        val follow = globalFollowSystem
            ?.let { Value(it, Layer.GLOBAL) }
            ?: Value(builtinFollowSystem, Layer.BUILTIN)
        return Resolved(templateId = template, speechRate = rate, followSystemRate = follow)
    }

    /**
     * 真实存储口径（L2 书级覆盖来自 prefs / L1 来自 AppConfig；L3 由剧本层后续注入）。
     * 只读 SharedPreferences，无挂起点，可被页面与播放服务同步调用。
     */
    fun resolveForBook(
        bookKey: String?,
        segmentTemplateId: String? = null,
        segmentSpeechRate: Int? = null
    ): Resolved = resolve(
        segmentTemplateId = segmentTemplateId,
        segmentSpeechRate = segmentSpeechRate,
        // 直接读原始覆盖（不能复用 resolveActiveTemplateId：它已回落到全局，无法区分来源层）
        bookTemplateId = bookKey?.let {
            appCtx.getPrefString(PreferKey.ttsCastingBookOverridePrefix + it)?.ifBlank { null }
        },
        bookSpeechRate = bookKey?.let { bookSpeechRateOf(it) },
        globalTemplateId = TtsCastingStore.activeTemplateId(),
        globalSpeechRate = AppConfig.ttsSpeechRate,
        globalFollowSystem = AppConfig.ttsFlowSys
    )

    /** 书级语速覆盖；未设置返回 null（= 跟随全局）。0 是合法档位，故用 -1 作哨兵 */
    fun bookSpeechRateOf(bookKey: String): Int? =
        appCtx.getPrefInt(BOOK_SPEECH_RATE_PREFIX + bookKey, -1).takeIf { it >= 0 }

    fun setBookSpeechRate(bookKey: String, rate: Int?) {
        appCtx.putPrefInt(BOOK_SPEECH_RATE_PREFIX + bookKey, rate ?: -1)
    }

    /** 设置页摘要用：把来源层翻译成用户能看懂的一句话（"跟随全局" = 清掉本书覆盖） */
    fun layerHint(layer: Layer): String = when (layer) {
        Layer.SEGMENT -> "本章覆盖"
        Layer.BOOK -> "本书覆盖（可在选择器里改回跟随全局）"
        Layer.GLOBAL -> "全局"
        Layer.BUILTIN -> "内置默认"
    }

    /**
     * 逐层取值：段 > 书 > 全局；全部未覆盖返回 null（由调用方落内置默认）。
     * 用 [Value] 同时携带来源层，避免调用方再各自回推"这个值是哪来的"。
     */
    private fun <T> pick(segment: T?, book: T?, global: T?): Value<T>? = when {
        segment != null -> Value(segment, Layer.SEGMENT)
        book != null -> Value(book, Layer.BOOK)
        global != null -> Value(global, Layer.GLOBAL)
        else -> null
    }

    private fun nonBlank(value: String?): String? = value?.takeIf { it.isNotBlank() }

    /**
     * 书级语速覆盖键（4.8）。
     * 不登记到 PreferKey：这是**书级**动态键（与 `ttsCastingBookOverride_*` 同类），
     * 只在解析器内使用，避免它成为全局设置项。
     */
    private const val BOOK_SPEECH_RATE_PREFIX = "readAloudBookSpeechRate_"

}
