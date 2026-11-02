package io.legado.app.help.readaloud.role.ner

import io.legado.app.help.readaloud.role.NerTextEngine

/**
 * LAC 输出的人名后处理（纯逻辑，JVM 可测）。
 *
 * 模型偶尔会把紧邻的动作字并进人名（`赵樱空道` / `詹岚仰`），因此：
 * ① 人名实体：从尾部剥掉动作/说话用字，保留 2~4 字；
 * ② 无姓名 cue：模型分词后，"名词性词元 + 说话动词 + 冒号/引号"也作为候选，
 *    用于捕捉模型未标 PER 的角色名（如 `零点道：` 会被标成 TIME）。
 *
 * 这两类都只是**信号**，最终说话人归属仍由 OfflineRoleResolver 融合判定（AD-16）。
 */
object LacNerPostProcessor {

    data class NameSpan(val name: String, val start: Int, val end: Int)

    /** 可直接作为 cue 人名的前一词元标签（名词类 + 模型 NER 标签） */
    private val cueLabelAllowed = setOf(
        "n", "nr", "ns", "nz", "nw", "PER", "LOC", "ORG", "TIME", "s", "t"
    )

    /**
     * "人名+说话动词"合并词元可接受的标签：只认人名类（PER/nr/nz）。
     * 不能放宽到 "n"：`千言道` 这类普通名词会以"道"结尾被误当 cue（回归集已证）。
     */
    private val mergedNameLabels = setOf("PER", "nr", "nz")

    /**
     * 回看找人名时可跳过的非名词类标签（动词/形容词/副词/量词/介词/连词/助词/标点等）。
     * **代词 r 不在其中**：遇到"他说/她道"必须立刻停，不能把前文名词当成说话人。
     */
    private val skipLabels = setOf("v", "a", "d", "q", "m", "p", "c", "u", "w", "x", "f")

    private val speechVerbSuffixes = listOf(
        "说道", "问道", "答道", "笑道", "喊道", "叫道", "喝道", "叹道", "骂道",
        "说", "道", "问", "答", "喊", "叫", "喝", "叹", "骂", "哼", "嚷", "吼", "嘀咕", "低语"
    )

    /** 人名尾部误并的动作/说话用字（逐字剥掉，但至少保留 2 字） */
    private val trailingActionChars = setOf(
        '说', '道', '问', '答', '喊', '叫', '喝', '叹', '骂', '哼', '嚷', '吼', '嗤',
        '仰', '躺', '站', '坐', '看', '想', '走', '跑', '转', '摇', '点', '挥', '伸', '缩',
        '握', '抓', '放', '拿', '推', '拉', '踢', '跳', '冲', '退', '闪', '躲', '皱', '愣', '呆'
    )

    private val pronouns = setOf("他", "她", "它", "我", "你", "您", "谁", "大家", "有人", "对方", "此人", "那人")

    /** 模型标注的人名实体（PER），剥尾后输出 */
    fun personMentions(tokens: List<LacDecoder.Token>): List<NameSpan> =
        tokens.filter { it.label == "PER" }
            .mapNotNull { spanOf(it.word, it.start) }
            .distinctBy { it.start }

    /** cue 候选：名词性词元 + 说话动词 + 冒号/引号（模型没标 PER 的角色名兜底） */
    fun cueCandidates(tokens: List<LacDecoder.Token>): List<NameSpan> {
        val spans = ArrayList<NameSpan>()
        tokens.forEachIndexed { index, token ->
            // 模型常把"人名 + 说话动词"并成一个词元（郑吒说 / 赵樱空道 / 詹岚道）⇒ 该词元本身就是 cue，
            // 不能再要求后面还有独立动词（否则模型 cue 几乎恒为空，归因退化成正则字典匹配：真机 2026-11-03 实证）
            if (isSpeechVerb(token.word)) {
                val merged = spanOf(token.word, token.start)
                if (merged != null) {
                    val personLabel = token.label in mergedNameLabels
                    // OOV 人名常被降级标成普通名词 n：只在"姓氏起头"时接受，避免 `千言道` 这类误收
                    val surnameFallback = token.label == "n" &&
                        NerTextEngine.startsWithKnownSurname(merged.name)
                    if (personLabel || surnameFallback) {
                        spans.add(merged)
                        return@forEachIndexed
                    }
                }
            }
            if (index == 0 || !isSpeechVerb(token.word)) return@forEachIndexed
            if (!hasDialogueMarker(tokens, index)) return@forEachIndexed
            // 网文常见"张三笑着说 / 李四冷笑着道"：人名与说话动词之间夹动作/助词，
            // 只取紧邻前一词会漏掉 ⇒ 回看至多 3 个词元找最近的名词性词元（模型标签驱动，不是正则）
            val nameToken = nameBack(tokens, index) ?: return@forEachIndexed
            spanOf(nameToken.word, nameToken.start)?.let { spans.add(it) }
        }
        return spans.distinctBy { it.start }
    }

    /** 从说话动词往前找最近的名词性命名词元（跳过动词/助词/副词/介词等，遇代词即停） */
    private fun nameBack(tokens: List<LacDecoder.Token>, verbIndex: Int): LacDecoder.Token? {
        val floor = maxOf(0, verbIndex - 3)
        for (k in verbIndex - 1 downTo floor) {
            val token = tokens[k]
            if (token.label in cueLabelAllowed) return token
            // 未知标签按兜底处理（与旧行为一致）；已知的非名词类继续回看
            if (token.label in skipLabels || token.label == "") continue
            return null
        }
        return null
    }

    private fun isSpeechVerb(word: String): Boolean =
        speechVerbSuffixes.any { word.endsWith(it) }

    private fun hasDialogueMarker(tokens: List<LacDecoder.Token>, verbIndex: Int): Boolean {
        // 允许中间夹顿号/逗号等 1~2 个词元（"郑吒说，道："式复述 cue）；窗口过宽会误收旁白
        val last = minOf(tokens.lastIndex, verbIndex + 3)
        for (k in (verbIndex + 1)..last) {
            val w = tokens[k].word.trimStart()
            if (w.startsWith("：") || w.startsWith(":") || w.startsWith("“") || w.startsWith("\"")) {
                return true
            }
        }
        return false
    }

    private fun spanOf(rawWord: String, start: Int): NameSpan? {
        var name = rawWord
        // 尾部叠字（哈哈/呵呵/嘿嘿）是笑声/语气，不是名字（模型偶发把"张杰哈哈"标成 PER）
        while (name.length > 2 && name.last() == name[name.length - 2]) {
            name = name.dropLast(2)
        }
        while (name.length > 2 && name.last() in trailingActionChars) {
            name = name.dropLast(1)
        }
        if (name.length !in 2..4) return null
        if (name in pronouns || NerTextEngine.isImplausibleName(name)) return null
        if (name.any { it !in '\u4E00'..'\u9FFF' }) return null
        return NameSpan(name, start, start + name.length)
    }
}
