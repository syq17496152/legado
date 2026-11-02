package io.legado.app.help.readaloud.role

/**
 * 端侧（零依赖）人名信号：已知角色名精确命中 + 中文姓氏启发式。
 * 只产出"人名 + 区间"作为 **信号**，不直接判定说话人（AD-16）。
 */
object NerTextEngine {

    data class Mention(val name: String, val start: Int, val end: Int)

    /** 常见汉语姓氏（覆盖网文绝大多数人名首字） */
    private val surnames = setOf(
        "赵", "钱", "孙", "李", "周", "吴", "郑", "王", "冯", "陈", "褚", "卫", "蒋", "沈", "韩", "杨",
        "朱", "秦", "尤", "许", "何", "吕", "施", "张", "孔", "曹", "严", "华", "金", "魏", "陶", "姜",
        "戚", "谢", "邹", "喻", "柏", "水", "窦", "章", "云", "苏", "潘", "葛", "奚", "范", "彭", "郎",
        "鲁", "韦", "昌", "马", "苗", "凤", "花", "方", "俞", "任", "袁", "柳", "酆", "鲍", "史", "唐",
        "费", "廉", "岑", "薛", "雷", "贺", "倪", "汤", "滕", "殷", "罗", "毕", "郝", "邬", "安", "常",
        "乐", "于", "时", "傅", "皮", "卞", "齐", "康", "伍", "余", "元", "卜", "顾", "孟", "平", "黄",
        "和", "穆", "萧", "尹", "姚", "邵", "湛", "汪", "祁", "毛", "禹", "狄", "米", "贝", "明", "臧",
        "计", "伏", "成", "戴", "谈", "宋", "茅", "庞", "熊", "纪", "舒", "屈", "项", "祝", "董", "梁",
        "杜", "阮", "蓝", "闵", "席", "季", "麻", "强", "贾", "路", "娄", "危", "江", "童", "颜", "郭",
        "梅", "盛", "林", "刁", "钟", "徐", "邱", "骆", "高", "夏", "蔡", "田", "樊", "胡", "凌", "霍",
        "虞", "万", "支", "柯", "昝", "管", "卢", "莫", "经", "房", "裘", "缪", "干", "解", "应", "宗",
        "丁", "宣", "贲", "邓", "郁", "单", "杭", "洪", "包", "诸", "左", "石", "崔", "吉", "钮", "龚",
        "程", "嵇", "邢", "裴", "陆", "荣", "翁", "荀", "羊", "於", "惠", "甄", "曲", "家", "封", "芮",
        "储", "靳", "汲", "邴", "糜", "松", "井", "段", "富", "巫", "乌", "焦", "巴", "弓", "牧", "隗",
        "山", "谷", "车", "侯", "宓", "蓬", "全", "郗", "班", "仰", "秋", "仲", "伊", "宫", "宁", "仇",
        "栾", "暴", "甘", "钭", "厉", "戎", "祖", "武", "符", "刘", "景", "詹", "束", "龙", "叶", "幸",
        "司", "韶", "郜", "黎", "蓟", "薄", "印", "宿", "白", "怀", "蒲", "邰", "从", "鄂", "索", "咸",
        "籍", "赖", "卓", "蔺", "屠", "蒙", "池", "乔", "阴", "胥", "能", "苍", "双", "闻", "莘", "党",
        "翟", "谭", "贡", "劳", "逄", "姬", "申", "扶", "堵", "冉", "宰", "郦", "雍", "却", "璩", "桑",
        "桂", "濮", "牛", "寿", "通", "边", "扈", "燕", "冀", "郏", "浦", "尚", "农", "温", "别", "庄",
        "晏", "柴", "瞿", "阎", "充", "慕", "连", "茹", "习", "宦", "艾", "鱼", "容", "向", "古", "易",
        "慎", "戈", "廖", "庾", "终", "暨", "居", "衡", "步", "都", "耿", "满", "弘", "匡", "国", "文",
        "寇", "广", "禄", "阙", "东", "欧", "殳", "沃", "利", "蔚", "越", "夔", "隆", "师", "巩", "厍",
        "聂", "晁", "勾", "敖", "融", "冷", "訾", "辛", "阚", "那", "简", "饶", "空", "曾", "沙", "乜",
        "养", "鞠", "须", "丰", "巢", "关", "蒯", "相", "查", "后", "荆", "红", "游", "竺", "权", "逯",
        "盖", "益", "桓", "公"
    )

    private val stopWords = setOf(
        "他", "她", "它", "我", "你", "您", "谁", "众", "人", "大家", "有人", "对方", "此人", "那人"
    )

    /** 复姓（允许 3 字名；单姓只取 2 字，避免把后续动词吞进人名，见 2026-11-02 真机碎片名问题） */
    private val compoundSurnames = setOf(
        "欧阳", "司马", "上官", "夏侯", "诸葛", "东方", "皇甫", "尉迟", "公孙", "慕容",
        "长孙", "宇文", "司徒", "鲜于", "南宫", "独孤", "西门", "轩辕", "令狐", "端木"
    )

    /** 高频非人名词（姓字起头但整体是动词/副词，cue 邻接也会误命中） */
    private val nonNameWords = setOf(
        "解释", "回答", "继续", "突然", "然后", "于是", "终于", "只是", "果然", "接着",
        "随着", "过了", "到了", "看着", "想着", "听着", "笑着", "说着", "边看", "边听"
    )

    /**
     * @param knownNames 本书已知角色名（优先精确命中，精度最高）
     * @param detectUnknown 是否补充姓氏启发式（用于无名角色发现）
     */
    fun findMentions(
        text: String,
        knownNames: List<String> = emptyList(),
        detectUnknown: Boolean = true
    ): List<Mention> {
        if (text.isEmpty()) return emptyList()
        val result = LinkedHashSet<Mention>()
        knownNames.filter { it.length >= 2 }.sortedByDescending { it.length }.forEach { name ->
            var index = text.indexOf(name)
            while (index >= 0) {
                result.add(Mention(name, index, index + name.length))
                index = text.indexOf(name, index + 1)
            }
        }
        if (detectUnknown) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c.toString() in surnames) {
                    val end = nameEnd(text, i)
                    if (end > i + 1) {
                        val name = text.substring(i, end)
                        if (name !in stopWords && name !in nonNameWords &&
                            !isImplausibleName(name) && !result.any { it.start == i }
                        ) {
                            result.add(Mention(name, i, end))
                        }
                        i = end
                        continue
                    }
                }
                i++
            }
        }
        return result.sortedBy { it.start }
    }

    /** 姓后连续 1 个汉字视为名字（复姓允许 2 个；遇到虚词/标点即止） */
    private fun nameEnd(text: String, start: Int): Int {
        val compound = compoundSurnames.firstOrNull { text.startsWith(it, start) }
        return scanNameEnd(text, start, if (compound != null) 3 else 2)
    }

    /** 从 [start] 起扫描最多 [maxLen] 字（含姓）：遇到非汉字或虚词即停，返回实际结束下标 */
    private fun scanNameEnd(text: String, start: Int, maxLen: Int): Int {
        val maxEnd = start + maxLen
        var end = start + 1
        while (end < text.length && end < maxEnd) {
            val c = text[end]
            if (c.code !in 0x4E00..0x9FFF) break
            if (c in nameStopChars) break
            end++
        }
        return end
    }

    /**
     * 姓氏候选族（供 `OfflineCharacterDiscovery.selectNames` 做全章频次消歧）：
     * 同一姓氏位置同时给出 2 字与 3 字（单姓）/ 3 字与 4 字（复姓）形态。
     *
     * 不在这里裁决长度：三字名（萧宏律/赵樱空）必须保留，而"郑吒嗤"这类
     * "人名+动作字"碎片只能靠出现频次剔除——单看一次出现无法区分。
     */
    fun surnameCandidates(text: String): List<Mention> {
        if (text.isEmpty()) return emptyList()
        val result = LinkedHashSet<Mention>()
        var i = 0
        while (i < text.length) {
            val compound = compoundSurnames.firstOrNull { text.startsWith(it, i) }
            if (compound == null && text[i].toString() !in surnames) {
                i++
                continue
            }
            val surnameLen = if (compound != null) 2 else 1
            for (nameLen in (surnameLen + 1)..(surnameLen + 2)) {
                val end = scanNameEnd(text, i, nameLen)
                if (end != i + nameLen) continue
                val name = text.substring(i, end)
                if (name !in stopWords && name !in nonNameWords && !isImplausibleName(name)) {
                    result.add(Mention(name, i, end))
                }
            }
            i += surnameLen + 1
        }
        return result.toList()
    }

    /** 名称是否以复姓开头（复姓三/四字名不参与"必须重复出现"的频次门槛） */
    fun startsWithCompoundSurname(name: String): Boolean =
        compoundSurnames.any { name.startsWith(it) }

    /**
     * 名字用字黑名单（单姓看第 2 字 / 复姓看第 3 字）：
     * 这些高频动词/副词几乎不会出现在名字里，却能拼出"都知/能转/却冷"这类伪人名。
     * 例：`都知道：“…”`（都知道）、`能转身就跑`（能转身）都会被姓氏启发式误当人名。
     */
    private val implausibleGivenChars = setOf(
        '知', '转', '冷', '摸', '调', '数', '有', '他', '深', '忽', '嗤', '释', '觉', '到',
        '来', '去', '走', '站', '坐', '起', '开', '关', '得', '做', '用', '把', '被', '让',
        '给', '能', '会', '可', '要'
    )

    /** 是否为"姓 + 高频动词/副词"拼出的伪人名（发现期过滤 + 旧数据清理共用同一口径） */
    fun isImplausibleName(name: String): Boolean {
        if (name.length < 2) return false
        if (name in nonNameWords || name in stopWords) return true
        val givenIndex = if (startsWithCompoundSurname(name)) 2 else 1
        return name.length > givenIndex && name[givenIndex] in implausibleGivenChars
    }

    private val nameStopChars = setOf(
        '说', '道', '问', '答', '笑', '喊', '叫', '喝', '叹', '想', '看', '又', '便', '才', '就', '也',
        '都', '正', '在', '的', '是', '了', '着', '把', '被', '和', '与', '跟', '对', '向', '从', '到',
        '要', '会', '能', '可', '却', '还', '只', '很', '太', '更', '已', '将', '让', '给', '替', '为'
    )

    /** 引号簇附近的强信号：人名出现在引号前 25 字内或后 15 字内 */
    fun mentionsNear(text: String, rangeStart: Int, rangeEnd: Int, mentions: List<Mention>): List<Mention> {
        return mentions.filter { mention ->
            val beforeGap = rangeStart - mention.end
            val afterGap = mention.start - rangeEnd
            (beforeGap in 0..25) || (afterGap in 0..15)
        }
    }
}
