package io.legado.app.help.readaloud.role.ner

import io.legado.app.help.readaloud.role.OfflineCharacterDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LAC-ONNX 解码/后处理纯逻辑（AD-16）：
 * Viterbi → BIO 词元 → PER 剥尾 + cue 候选。全部可 JVM 直测，不需要模型文件。
 */
class LacNerPipelineTest {

    @Test
    fun `viterbi 选择分数最高的标签路径`() {
        val emissions = arrayOf(
            floatArrayOf(0f, -10f),
            floatArrayOf(0f, -10f),
            floatArrayOf(-10f, 0f)
        )
        val transitions = arrayOf(
            floatArrayOf(0f, 0f),
            floatArrayOf(0f, 0f)
        )
        val path = LacDecoder.viterbi(emissions, transitions, 3)
        assertEquals(listOf(0, 0, 1), path.toList())
    }

    @Test
    fun `BIO 合并出人名与动词词元`() {
        val tags = mapOf(
            16 to "PER-B",
            17 to "PER-I",
            38 to "v-B",
            39 to "v-I",
            44 to "w-B",
            45 to "w-I"
        )
        // 郑( PER-B ) 吒( PER-I ) 嗤( v-B ) 笑( v-I ) 道( v-B )
        val ids = intArrayOf(16, 17, 38, 39, 38, 44)
        val tokens = LacDecoder.decodeTokens("郑吒嗤笑道：", ids, tags)
        assertEquals("郑吒", tokens[0].word)
        assertEquals("PER", tokens[0].label)
        assertEquals(0, tokens[0].start)
        assertEquals(2, tokens[0].end)
        assertEquals("嗤笑", tokens[1].word)
        assertEquals("v", tokens[1].label)
        assertEquals("道", tokens[2].word)
    }

    @Test
    fun `PER 尾部误并动作字要剥掉`() {
        val tokens = listOf(
            LacDecoder.Token("赵樱空道", "PER", 0, 4),
            LacDecoder.Token("詹岚仰", "PER", 10, 13),
            LacDecoder.Token("郑吒", "PER", 20, 22)
        )
        val names = LacNerPostProcessor.personMentions(tokens).map { it.name }
        assertEquals(listOf("赵樱空", "詹岚", "郑吒"), names)
    }

    @Test
    fun `无 PER 标签的角色名可由 cue 词元兜底`() {
        val tokens = listOf(
            LacDecoder.Token("零点", "TIME", 0, 2),
            LacDecoder.Token("道", "v", 2, 3),
            LacDecoder.Token("：", "w", 3, 4)
        )
        val names = LacNerPostProcessor.cueCandidates(tokens).map { it.name }
        assertEquals(listOf("零点"), names)
    }

    @Test
    fun `PER 词元吞并说话动词时本身即 cue`() {
        // 真机 2026-11-03 实证：LAC 常把"人名+说话动词"并成一个词元（郑吒说 / 赵樱空道），
        // 若仍要求后面有独立动词，模型 cue 恒为空 ⇒ 归因退化成正则字典匹配
        val merged = listOf(LacDecoder.Token("郑吒说", "PER", 0, 3))
        assertEquals(listOf("郑吒"), LacNerPostProcessor.cueCandidates(merged).map { it.name })

        val mergedWithAction = listOf(LacDecoder.Token("赵樱空道", "PER", 10, 14))
        assertEquals(
            listOf("赵樱空"),
            LacNerPostProcessor.cueCandidates(mergedWithAction).map { it.name }
        )
        // 模型也可能把名字标成 nr（而非 PER）；合并词元规则必须同样覆盖
        val nrMerged = listOf(LacDecoder.Token("詹岚道", "nr", 20, 23))
        assertEquals(listOf("詹岚"), LacNerPostProcessor.cueCandidates(nrMerged).map { it.name })

        // OOV 人名被降级标成普通名词 n：仅当剥离动词后是"姓氏起头"的人名才接受
        val nLabeledSurname = listOf(LacDecoder.Token("郑吒说", "n", 30, 33))
        assertEquals(listOf("郑吒"), LacNerPostProcessor.cueCandidates(nLabeledSurname).map { it.name })
    }

    @Test
    fun `说话动词后隔两个词元的冒号仍算 cue`() {
        val tokens = listOf(
            LacDecoder.Token("零点", "TIME", 0, 2),
            LacDecoder.Token("说", "v", 2, 3),
            LacDecoder.Token("了", "u", 3, 4),
            LacDecoder.Token("一句", "m", 4, 6),
            LacDecoder.Token("：", "w", 6, 7)
        )
        assertEquals(listOf("零点"), LacNerPostProcessor.cueCandidates(tokens).map { it.name })
    }

    @Test
    fun `人名与说话动词之间夹动作助词时回看命中`() {
        // "张三笑着说："：笑(v)/着(u) 夹在中间，只取紧邻前一词会漏掉（真机网文高频句式）
        val withSmile = listOf(
            LacDecoder.Token("张三", "nr", 0, 2),
            LacDecoder.Token("笑", "v", 2, 3),
            LacDecoder.Token("着", "u", 3, 4),
            LacDecoder.Token("说", "v", 4, 5),
            LacDecoder.Token("：", "w", 5, 6)
        )
        assertEquals(listOf("张三"), LacNerPostProcessor.cueCandidates(withSmile).map { it.name })

        val withColdLaugh = listOf(
            LacDecoder.Token("李四", "nr", 0, 2),
            LacDecoder.Token("冷笑", "v", 2, 4),
            LacDecoder.Token("着", "u", 4, 5),
            LacDecoder.Token("道", "v", 5, 6),
            LacDecoder.Token("：", "w", 6, 7)
        )
        assertEquals(listOf("李四"), LacNerPostProcessor.cueCandidates(withColdLaugh).map { it.name })
    }

    @Test
    fun `代词说话时不回看前文名词`() {
        // "他说道："必须立刻停：不能把前文的"张三"当成说话人（AD-15 宁不猜）
        val tokens = listOf(
            LacDecoder.Token("张三", "nr", 0, 2),
            LacDecoder.Token("他", "r", 2, 3),
            LacDecoder.Token("说", "v", 3, 4),
            LacDecoder.Token("道", "v", 4, 5),
            LacDecoder.Token("：", "w", 5, 6)
        )
        assertTrue(LacNerPostProcessor.cueCandidates(tokens).isEmpty())
    }

    /** 人名被 4 个动作/修饰词元隔开时仍要命中（回看窗口 3 → 5，真机 ch651 有 4 段此类漏召回） */
    @Test
    fun `人名被动作短语隔开五个词元仍命中`() {
        val tokens = listOf(
            LacDecoder.Token("张三", "nr", 0, 2),
            LacDecoder.Token("抬眼", "v", 2, 4),
            LacDecoder.Token("看", "v", 4, 5),
            LacDecoder.Token("了", "u", 5, 6),
            LacDecoder.Token("一眼", "m", 6, 8),
            LacDecoder.Token("说道", "v", 8, 10),
            LacDecoder.Token("：", "w", 10, 11)
        )
        assertEquals(listOf("张三"), LacNerPostProcessor.cueCandidates(tokens).map { it.name })
    }

    /** 窗口扩大后代词仍然阻断（不得跨代词把前文名字当说话人） */
    @Test
    fun `扩大回看窗口后代词仍然阻断`() {
        val tokens = listOf(
            LacDecoder.Token("张三", "nr", 0, 2),
            LacDecoder.Token("他", "r", 2, 3),
            LacDecoder.Token("冷冷", "d", 3, 5),
            LacDecoder.Token("地", "u", 5, 6),
            LacDecoder.Token("看", "v", 6, 7),
            LacDecoder.Token("了一眼", "m", 7, 10),
            LacDecoder.Token("说道", "v", 10, 12),
            LacDecoder.Token("：", "w", 12, 13)
        )
        assertTrue(LacNerPostProcessor.cueCandidates(tokens).isEmpty())
    }

    /** 复杂词性（vn/vd/xc 等）不得阻断回看：它们是网文动作短语的常见标注 */
    @Test
    fun `复合词性不阻断回看`() {
        val tokens = listOf(
            LacDecoder.Token("李四", "nr", 0, 2),
            LacDecoder.Token("转身", "vn", 2, 4),
            LacDecoder.Token("站定", "vd", 4, 6),
            LacDecoder.Token("说", "v", 6, 7),
            LacDecoder.Token("：", "w", 7, 8)
        )
        assertEquals(listOf("李四"), LacNerPostProcessor.cueCandidates(tokens).map { it.name })
    }

    /** 人名子类标签（nh/nhd 系列）同样可作为 cue 人名 */
    @Test
    fun `人名子类标签可作为 cue`() {
        val tokens = listOf(
            LacDecoder.Token("零点", "nh", 0, 2),
            LacDecoder.Token("道", "v", 2, 3),
            LacDecoder.Token("：", "w", 3, 4)
        )
        assertEquals(listOf("零点"), LacNerPostProcessor.cueCandidates(tokens).map { it.name })
    }

    @Test
    fun `形容词与高频组合不得被 cue 兜底误收`() {
        val adjectives = listOf(
            LacDecoder.Token("软弱", "a", 0, 2),
            LacDecoder.Token("说道", "v", 2, 4),
            LacDecoder.Token("：", "w", 4, 5)
        )
        assertTrue(LacNerPostProcessor.cueCandidates(adjectives).isEmpty())

        val common = listOf(
            LacDecoder.Token("万语", "n", 0, 2),
            LacDecoder.Token("千言道", "n", 2, 5),
            LacDecoder.Token("：", "w", 5, 6)
        )
        assertTrue(LacNerPostProcessor.cueCandidates(common).isEmpty())
    }

    @Test
    fun `空输入与未知标签返回空或单字词元`() {
        assertTrue(LacDecoder.decodeTokens("", intArrayOf(), emptyMap()).isEmpty())
        assertNull(LacNerPostProcessor.personMentions(emptyList()).firstOrNull())
    }

    @Test
    fun `信任模型时三字名即使只出现一次也保留`() {
        val counts = linkedMapOf("萧宏律" to 1, "赵樱空" to 1)
        val trusted = OfflineCharacterDiscovery.selectNames(counts, trustModel = true)
        assertEquals(listOf("萧宏律", "赵樱空"), trusted.map { it.first })
        // 无模型（规则路径）时仍按频次门槛剔除一次性三字名
        assertTrue(OfflineCharacterDiscovery.selectNames(counts, trustModel = false).isEmpty())
    }

    @Test
    fun `模型 PER 尾部叠字笑声要剥掉`() {
        val tokens = listOf(
            LacDecoder.Token("张杰哈哈", "PER", 0, 4),
            LacDecoder.Token("詹岚呵呵", "PER", 10, 14)
        )
        val names = LacNerPostProcessor.personMentions(tokens).map { it.name }
        assertEquals(listOf("张杰", "詹岚"), names)
    }

    @Test
    fun `非姓氏实体需说话 cue 邻接才计入`() {
        assertTrue(OfflineCharacterDiscovery.shouldCountModelMention("张杰", cueAdjacent = false))
        assertTrue(OfflineCharacterDiscovery.shouldCountModelMention("零点", cueAdjacent = true))
        assertTrue(!OfflineCharacterDiscovery.shouldCountModelMention("法西斯", cueAdjacent = false))
    }

    @Test
    fun `模型路径拦截一次性的非姓氏误报`() {
        val counts = linkedMapOf("法西斯" to 1, "张杰" to 1, "零点" to 2)
        val trusted = OfflineCharacterDiscovery.selectNames(counts, trustModel = true)
        // 法西斯（非姓氏 + 仅 1 次）被剔除；张杰（姓氏起头）与零点（重复出现）保留（按次数降序）
        assertEquals(listOf("零点", "张杰"), trusted.map { it.first })
    }
}
