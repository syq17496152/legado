package io.legado.app.help.readaloud

import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.readaloud.casting.CastingProsody

/**
 * 语速分层（A2）：同一章里旁白/主角/老人儿童/激烈对白的语速略有差异，听感更"像人在讲"。
 *
 * 边界（设计 §1.8）：
 * - **±5% 上限**（0.95 / 1.05）——超过就会失真，设计表里的 0.9 与"±5% 上限"冲突时以 ±5% 为准；
 * - 只有「明确知道引擎基准语速」时才生效（跟系统语速 / 用户自定义引擎参数时不动）；
 * - 用户模板里显式配置的 `CastingProsody.rate` 优先级最高，本层不覆盖；
 * - 纯函数，JVM 可测；真机听感由 L3 A/B 盲听复核。
 */
object ReadAloudRateLayer {

    const val NORMAL = 1.0f

    /** 老人/儿童：慢一点 */
    const val SOFT = 0.95f

    /** 激烈对白：快一点 */
    const val INTENSE = 1.05f

    /** 层内浮动上限（设计：±5%） */
    const val MAX_DELTA = 0.05f

    private val CHILD_WORDS = listOf("儿童", "小孩", "孩子", "少年", "少女", "幼", "萝莉", "正太", "童")
    private val ELDER_WORDS = listOf(
        "老人", "老年", "老者", "老头", "老太", "爷爷", "奶奶", "婆婆", "翁", "叟"
    )

    /**
     * 角色年龄带：child / old / ""（未知）。
     * @param age 角色卡里的自由文本年纪（如"60岁""十二岁"）
     * @param identity 身份描述（如"少女""老者"）
     */
    fun ageBandOf(age: String, identity: String): String {
        val text = "$age $identity"
        val years = Regex("([0-9]{1,3})").find(age)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("([一二三四五六七八九十]{1,3})岁").find(age)
                ?.groupValues?.getOrNull(1)
                ?.let(::chineseNumber)
        val isChild = (years != null && years <= 14) || CHILD_WORDS.any { text.contains(it) }
        val isElder = (years != null && years >= 60) || ELDER_WORDS.any { text.contains(it) }
        return when {
            isChild -> AGE_CHILD
            isElder -> AGE_OLD
            else -> ""
        }
    }

    /** 解析 1~99 的中文数字（"十二"→12、"二十"→20、"九"→9）；非法返回 null */
    private fun chineseNumber(raw: String): Int? {
        val digits = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (raw.isEmpty()) return null
        if ('十' !in raw) {
            return if (raw.length == 1) digits[raw[0]] else null
        }
        val parts = raw.split('十')
        val tens = if (parts[0].isEmpty()) 1 else digits[parts[0].first()] ?: return null
        val ones = if (parts.getOrNull(1).isNullOrEmpty()) 0
        else (digits[parts[1].first()] ?: return null)
        return tens * 10 + ones
    }

    const val AGE_CHILD = "child"
    const val AGE_OLD = "old"

    /**
     * 激烈对白：短句且带感叹号（"怒吼/惊叫"在文本上的稳定信号）。
     * 只看标点与长度，不猜语义——误判代价只是 5% 语速差；长句即使有感叹号也不加速（避免整段赶读）。
     */
    fun isIntenseSpeech(text: String): Boolean {
        if (text.length > INTENSE_MAX_CHARS) return false
        val marks = text.count { it == '！' || it == '!' }
        return marks > 0
    }

    /** 激烈短句的长度上限（含引号/标点） */
    private const val INTENSE_MAX_CHARS = 16

    /** 分层倍率（旁白/主角恒 1.0；年龄带优先于激烈；全部夹在 ±5%） */
    fun layerOf(
        narrator: Boolean,
        roleLevel: Int,
        ageBand: String,
        intense: Boolean
    ): Float {
        val raw = when {
            narrator -> NORMAL
            ageBand == AGE_CHILD || ageBand == AGE_OLD -> SOFT
            intense -> INTENSE
            roleLevel >= BookCharacter.ROLE_MAIN -> NORMAL
            else -> NORMAL
        }
        return raw.coerceIn(NORMAL - MAX_DELTA, NORMAL + MAX_DELTA)
    }

    /**
     * 合并进模板韵律：[baseRate]=null 表示「基准语速未知（跟系统/自定义引擎参数）」⇒ 原样返回，不猜。
     * 模板显式 rate（>0）优先。
     */
    fun mergeProsody(
        prosody: CastingProsody,
        layer: Float,
        baseRate: Float?
    ): CastingProsody {
        if (baseRate == null || prosody.rate > 0f) return prosody
        val layered = (baseRate * layer).coerceIn(0.5f, 2.0f)
        return if (layered == prosody.rate) prosody else prosody.copy(rate = layered)
    }
}
