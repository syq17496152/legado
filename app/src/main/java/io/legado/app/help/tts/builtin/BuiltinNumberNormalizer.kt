package io.legado.app.help.tts.builtin

/**
 * 数字/年份/单位发音归一（A6）：端侧中文模型对阿拉伯数字的读法不一致
 * （"2026" 读成"二零二六"还是"二千零二十六"随模型而定，小数点/百分号更常读错）。
 * 这里在**送引擎前**把高风险写法转成稳定中文写法，正文显示不受影响。
 *
 * 安全边界（都有单测）：
 * - 只处理「独立数字」：前后紧邻 ASCII 字母/数字/小数点（版本号、型号、编号）一律不动；
 * - 年份只认「四位整数 + 年/年代」（避免把普通计数当年份读成逐位）；
 * - 前导零、8 位以上长数字逐位读（订单号/电话/编号不会变成"一千零二亿"）；
 * - 未知单位/未知后缀不动原文（宁可原样交给模型，也不猜错）。
 */
object BuiltinNumberNormalizer {

    /** 常见单位/符号（只在数字后紧邻时生效；键按长度倒序匹配，避免 km 被 m 抢先） */
    private val UNITS: List<Pair<String, String>> = listOf(
        "km/h" to "公里每小时",
        "km²" to "平方公里",
        "km2" to "平方公里",
        "m²" to "平方米",
        "m2" to "平方米",
        "m³" to "立方米",
        "m3" to "立方米",
        "cm" to "厘米",
        "mm" to "毫米",
        "km" to "公里",
        "kg" to "千克",
        "ml" to "毫升",
        "GB" to "吉字节",
        "MB" to "兆字节",
        "KB" to "千字节",
        "℃" to "摄氏度",
        "°C" to "摄氏度",
        "°" to "度"
    ).sortedByDescending { it.first.length }

    private const val ZERO = '零'
    private val DIGITS = charArrayOf('零', '一', '二', '三', '四', '五', '六', '七', '八', '九')
    private val SMALL_UNITS = charArrayOf('十', '百', '千')

    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length + 16)
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (c !in '0'..'9' || isAsciiAlnumBefore(text, index)) {
                out.append(c)
                index++
                continue
            }
            val numberEnd = scanNumber(text, index)
            if (numberEnd <= index) {
                out.append(c)
                index++
                continue
            }
            val raw = text.substring(index, numberEnd)
            val consumed = numberEnd - index
            val next = text.getOrNull(numberEnd)
            // 版本号/多级编号（3.26.110）：第二段点号会破坏读法，整段不动
            if (next == '.' && text.getOrNull(numberEnd + 1)?.isDigit() == true) {
                out.append(raw)
                index = numberEnd
                continue
            }
            val unit = UNITS.firstOrNull { (key, _) ->
                text.regionMatches(numberEnd, key, 0, key.length, ignoreCase = true)
            }
            val converted = when {
                unit != null -> readNumber(raw) + unit.second
                next == '%' -> "百分之" + readNumber(raw)
                raw.length == 4 && !raw.contains('.') &&
                    (text.startsWith("年代", numberEnd) || next == '年') ->
                    readDigitByDigit(raw)
                else -> readNumber(raw)
            }
            out.append(converted)
            index = when {
                unit != null -> numberEnd + unit.first.length
                // "百分之"已经把百分号读掉，必须一并吃掉，否则残留 "%" 会被引擎再读一遍
                next == '%' -> numberEnd + 1
                else -> numberEnd
            }
        }
        return out.toString()
    }

    /** 前一位是 ASCII 字母/数字/小数点/负号 ⇒ 该数字属于版本号/型号/负数，不处理（交给模型读） */
    private fun isAsciiAlnumBefore(text: String, index: Int): Boolean {
        val prev = text.getOrNull(index - 1) ?: return false
        return prev.isLetter() && prev.code < 128 || prev.isDigit() || prev == '.' || prev == '-'
    }

    /** 扫描连续数字（含至多一个小数点）；返回结束下标（不含） */
    private fun scanNumber(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isDigit()) i++
        if (i < text.length && text[i] == '.' && text.getOrNull(i + 1)?.isDigit() == true) {
            i++
            while (i < text.length && text[i].isDigit()) i++
        }
        return i
    }

    /** 整数/小数读法：前导零或超长（≥8 位）逐位，其余按数位读 */
    private fun readNumber(raw: String): String {
        val dot = raw.indexOf('.')
        if (dot >= 0) {
            val intPart = raw.substring(0, dot)
            val fracPart = raw.substring(dot + 1)
            return readInteger(intPart) + "点" + fracPart.map { DIGITS[it - '0'] }.joinToString("")
        }
        return readInteger(raw)
    }

    private fun readInteger(raw: String): String {
        if (raw.isEmpty()) return ""
        if (raw.length > 1 && raw[0] == '0') return readDigitByDigit(raw)
        // 9 位以上（电话/长编号）：逐位读，避免"十二亿三千..."这种失真长数字
        if (raw.length >= 9) return readDigitByDigit(raw)
        val value = raw.toLongOrNull() ?: return readDigitByDigit(raw)
        if (value == 0L) return ZERO.toString()
        return readGrouped(value)
    }

    /** 逐位读：2026 → 二零二六、007 → 零零七 */
    private fun readDigitByDigit(raw: String): String =
        raw.map { DIGITS[it - '0'] }.joinToString("")

    /** 分组读数（万/亿），正确处理"零"与"十"的省略（10 → 十、10001 → 一万零一） */
    private fun readGrouped(value: Long): String {
        if (value < 0) return "负" + readGrouped(-value)
        if (value < 10000) return readBelow10k(value.toInt())
        if (value < 100_000_000) {
            val high = value / 10000
            val low = (value % 10000).toInt()
            return readBelow10k(high.toInt()) + "万" + readBelow10kPadded(low)
        }
        return readDigitByDigit(value.toString())
    }

    private fun readBelow10k(value: Int): String {
        if (value == 0) return ""
        if (value < 10) return DIGITS[value].toString()
        val result = StringBuilder()
        var rest = value
        var zeroPending = false
        for (position in 3 downTo 0) {
            val divisor = pow10(position)
            val digit = rest / divisor
            rest %= divisor
            if (digit == 0) {
                if (result.isNotEmpty() && rest > 0) zeroPending = true
                continue
            }
            if (zeroPending) {
                result.append(ZERO)
                zeroPending = false
            }
            // "十"开头不读"一十"（10~19）
            if (!(position == 1 && digit == 1 && result.isEmpty())) {
                result.append(DIGITS[digit])
            }
            if (position > 0) result.append(SMALL_UNITS[position - 1])
        }
        return result.toString()
    }

    /** 万/亿的低位补零：10001 → 一万零一（1001 → 零一千零一 需要补零） */
    private fun readBelow10kPadded(value: Int): String {
        if (value == 0) return ""
        val text = readBelow10k(value)
        return if (value < 1000) ZERO + text else text
    }

    private fun pow10(exp: Int): Int {
        var result = 1
        repeat(exp) { result *= 10 }
        return result
    }
}
