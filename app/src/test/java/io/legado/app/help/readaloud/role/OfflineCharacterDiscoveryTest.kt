package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线角色发现（T4.2 规则兜底）：cue 邻接过滤 + 排序 + 代词排除。
 * 纯函数测试，不触库不联网（落库路径由 [OfflineCharacterDiscovery.ensureCharacters] 在设备上验）。
 */
class OfflineCharacterDiscoveryTest {

    @Test
    fun `只收 cue 邻接的人名并按出现次数降序`() {
        val paragraphs = listOf(
            "张三说：“你终于来了，我等了很久。”",
            "李四说：“路上耽搁了，别急，先喝口茶。”",
            "张三笑了笑：“这么多年，你还是老样子。”",
            "夜色渐深，客栈里只剩两盏灯。"
        )
        val candidates = OfflineCharacterDiscovery.candidates(paragraphs)
        assertEquals(listOf("张三", "李四"), candidates.map { it.name })
        assertEquals(2, candidates[0].cueCount)
        assertEquals(1, candidates[1].cueCount)
    }

    @Test
    fun `无 cue 的人名不落候选`() {
        val paragraphs = listOf("张三站在门口，风吹动他的衣角。")
        assertTrue(OfflineCharacterDiscovery.candidates(paragraphs).isEmpty())
    }

    @Test
    fun `代词与冒号剧本写法`() {
        val paragraphs = listOf(
            "他说：“别急。”",
            "李四：路上耽搁了。"
        )
        val candidates = OfflineCharacterDiscovery.candidates(paragraphs)
        assertEquals(listOf("李四"), candidates.map { it.name })
    }

    /** 2026-11-10 用户反馈「角色获取太少」：非姓氏起头的对话人名（西式/自造名）必须能补扫到 */
    @Test
    fun `非姓氏人名按对话 cue 收录`() {
        val paragraphs = listOf(
            "艾伦说道：“这里的风有些冷。”",
            "艾伦：我们得在天黑前赶到。",
            "凯尔道：“别急，先看看地图。”",
            "凯尔：地图在我这里。"
        )
        val names = OfflineCharacterDiscovery.candidates(paragraphs).map { it.name }
        assertTrue("应收录艾伦：$names", "艾伦" in names)
        assertTrue("应收录凯尔：$names", "凯尔" in names)
    }

    /** 只出现一次的「注意：」这类泛指不能被当作角色 */
    @Test
    fun `一次性冒号泛指不进候选`() {
        val paragraphs = listOf(
            "注意：天黑之后不要出门。",
            "所有人都看向了门外，没有人说话。"
        )
        assertTrue(OfflineCharacterDiscovery.candidates(paragraphs).isEmpty())
    }

    @Test
    fun `cue 人名可信度判定`() {
        assertTrue(OfflineCharacterDiscovery.isPlausibleCueName("艾伦"))
        assertTrue(OfflineCharacterDiscovery.isPlausibleCueName("凯尔"))
        assertFalse(OfflineCharacterDiscovery.isPlausibleCueName("我们"))
        assertFalse(OfflineCharacterDiscovery.isPlausibleCueName("注意"))
        assertFalse(OfflineCharacterDiscovery.isPlausibleCueName("忽"))
        assertFalse(OfflineCharacterDiscovery.isPlausibleCueName("张忽"))
    }

    @Test
    fun `候选数量受上限约束`() {
        val names = listOf("张三", "李四", "王五", "赵六", "陈七", "杨八", "周九", "吴十")
        val paragraphs = names.map { name -> "${name}说：“好。”" }
        val candidates = OfflineCharacterDiscovery.candidates(paragraphs, limit = 3)
        assertEquals(3, candidates.size)
        assertEquals(names.take(3), candidates.map { it.name })
    }

    /** 2026-11-02 真机回归：单姓最长只取 2 字，避免把后续动词吞成"郑吒嗤"这类碎片名 */
    @Test
    fun `单姓人名不吞后续动词`() {
        val paragraphs = listOf(
            "郑吒嗤笑道：“你想真正的活着吗？”",
            "郑吒忽然道：“先别急，听听他们怎么说。”"
        )
        val candidates = OfflineCharacterDiscovery.candidates(paragraphs)
        assertEquals(listOf("郑吒"), candidates.map { it.name })
        assertEquals(2, candidates[0].cueCount)
    }

    @Test
    fun `复姓保留三字名`() {
        val paragraphs = listOf("欧阳锋说：“这一招你看清了么？”")
        val candidates = OfflineCharacterDiscovery.candidates(paragraphs)
        assertEquals(listOf("欧阳锋"), candidates.map { it.name })
    }

    /** 2026-11-02 用户反馈：萧宏律/赵樱空是真名，不能被 2 字上限截断成萧宏/赵樱 */
    @Test
    fun `三字单姓名在多次 cue 命中时完整保留`() {
        val paragraphs = listOf(
            "萧宏律说：“这里的规则不是你想的那样。”",
            "萧宏律道：“先活下去再说。”",
            "赵樱空道：“别废话，走。”",
            "赵樱空说：“我断后。”"
        )
        val names = OfflineCharacterDiscovery.candidates(paragraphs).map { it.name }
        assertEquals(listOf("萧宏律", "赵樱空"), names)
        // 2 字前缀属于截断碎片，必须被剪掉
        assertFalse("萧宏" in names)
        assertFalse("赵樱" in names)
    }

    /** 一次出现的"人名+动作字"（郑吒嗤）必须被频次剔除，保住真名郑吒 */
    @Test
    fun `一次性尾巴碎片被频次剔除`() {
        val paragraphs = listOf(
            "郑吒嗤笑道：“你想真正的活着吗？”",
            "郑吒说：“先别急，听听他们怎么说。”",
            "郑吒道：“走吧。”"
        )
        val names = OfflineCharacterDiscovery.candidates(paragraphs).map { it.name }
        assertEquals(listOf("郑吒"), names)
        assertFalse("郑吒嗤" in names)
    }

    /** AD-16：JVM/无模型环境必须自动回落规则路径，不能因缺模型直接空结果 */
    @Test
    fun `无 NER 模型时仍走规则兜底`() {
        assertFalse(NerTextEngine.isModelReady())
        val names = NerTextEngine.findMentions("张三说：“好。”", detectUnknown = true).map { it.name }
        assertTrue("张三" in names)
    }

    /** 模型路径同样要合并前缀碎片，并拒绝感叹词/身体词误报 */
    @Test
    fun `模型路径合并前缀并拒绝感叹词`() {
        assertTrue(NerTextEngine.isImplausibleName("呃恩"))
        assertTrue(NerTextEngine.isImplausibleName("额头"))
        val trusted = OfflineCharacterDiscovery.selectNames(
            linkedMapOf("李帅" to 3, "李帅西" to 3),
            trustModel = true
        )
        assertEquals(listOf("李帅西"), trusted.map { it.first })
    }

    /** 变更数口径：清理旧碎片与新增一样触发分镜重算，不能只看 added */
    @Test
    fun `新增与清理都计入变更数`() {
        assertEquals(0, OfflineCharacterDiscovery.DiscoveryResult(added = 0, pruned = 0).changed)
        assertEquals(2, OfflineCharacterDiscovery.DiscoveryResult(added = 1, pruned = 1).changed)
    }

    /** 2026-11-02 二次真机：姓氏启发式曾把"都知/能转/却冷"落库，必须按名字用字黑名单剔除 */
    @Test
    fun `姓字加高频副动词不落候选`() {
        val paragraphs = listOf(
            "都知道：“这事没完。”",
            "能转身就跑的人不多，他却做到了。",
            "却冷笑道：“你以为呢？”",
            "解释了半天，也没人信。"
        )
        val actual = OfflineCharacterDiscovery.candidates(paragraphs).map { it.name }
        assertTrue("意外候选：$actual", actual.isEmpty())
        assertTrue(NerTextEngine.isImplausibleName("都知"))
        assertTrue(NerTextEngine.isImplausibleName("能转"))
        assertTrue(NerTextEngine.isImplausibleName("解释"))
        // 2026-11-02 第三次真机：万语/边哭/后女/连她/盖中 也是"姓+动词/代词/方位"拼出来的
        assertTrue(NerTextEngine.isImplausibleName("万语"))
        assertTrue(NerTextEngine.isImplausibleName("边哭"))
        assertTrue(NerTextEngine.isImplausibleName("后女"))
        assertTrue(NerTextEngine.isImplausibleName("连她"))
        assertTrue(NerTextEngine.isImplausibleName("盖中"))
    }

    /** 黑名单不得误伤真实角色（郑吒/赵樱空/张杰/詹岚） */
    @Test
    fun `名字用字黑名单不误伤真实姓名`() {
        assertFalse(NerTextEngine.isImplausibleName("郑吒"))
        assertFalse(NerTextEngine.isImplausibleName("赵樱空"))
        assertFalse(NerTextEngine.isImplausibleName("张杰"))
        assertFalse(NerTextEngine.isImplausibleName("詹岚"))
        assertFalse(NerTextEngine.isImplausibleName("萧宏律"))
    }

    /** 姓字起头的高频动词（解释/回答）不是人名，cue 邻接也不得入选 */
    @Test
    fun `姓字起头的非人名词不落候选`() {
        val paragraphs = listOf(
            "他解释道：“这只是个意外。”",
            "她回答：“我明白了。”"
        )
        assertTrue(OfflineCharacterDiscovery.candidates(paragraphs).isEmpty())
    }

    @Test
    fun `有模型时来源标 NER 无模型标规则兜底`() {
        assertEquals("offline-ner-lac-v1", OfflineCharacterDiscovery.autoSource(trustModel = true))
        assertEquals("offline-rule-v1", OfflineCharacterDiscovery.autoSource(trustModel = false))
        // 两个来源都必须纳入自动清理口径（否则旧碎片清不掉 / 新旧混用）
        assertTrue(OfflineCharacterDiscovery.SOURCE_OFFLINE != OfflineCharacterDiscovery.SOURCE_OFFLINE_NER)
    }

    /**
     * 2026-11-05 模拟器 E2E：昵称式角色名（阿明/小美/老张）不在姓氏表里，无 NER 模型时
     * 曾整章判"未识别角色" ⇒ 剧本生成被静默跳过。cue 正则必须补上这条路径。
     */
    @Test
    fun `无模型时昵称式角色名可被发现`() {
        val paragraphs = listOf(
            "阿明推开门，屋里没有开灯。他说：“你回来了？”",
            "小美靠在窗边，低声说：“别开灯，外面有人。”",
            "阿明皱起眉头：“什么人？”",
            "小美摇了摇头：“别问了，先坐下。”",
            "邻居老张敲门进来，笑呵呵地说：“这么早就起来了？”",
            "老张压低声音：“昨晚我听见你屋里有两个人说话。”"
        )
        val names = OfflineCharacterDiscovery.candidates(paragraphs).map { it.name }
        assertTrue("应发现阿明，实际：$names", "阿明" in names)
        assertTrue("应发现小美，实际：$names", "小美" in names)
        assertTrue("应发现老张，实际：$names", "老张" in names)
    }

    /** 只出现一次的昵称证据不足（可能是"小了/老说"这类片段），不得落候选 */
    @Test
    fun `一次性昵称不落候选`() {
        val paragraphs = listOf("小美笑了笑：“今天天气不错。”")
        assertTrue(OfflineCharacterDiscovery.candidates(paragraphs).isEmpty())
    }
}
