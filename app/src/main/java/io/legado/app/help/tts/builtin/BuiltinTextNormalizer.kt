package io.legado.app.help.tts.builtin

/**
 * 合成文本归一（T8.2 简繁归一）：
 * 端侧模型（piper/ZipVoice）的中文词典以**简体**为准，繁体章节直接喂会大量读错。
 * 口径：只统计"繁体专用字"占全部汉字的比例，**超过 20% 才转换**（偶发几个繁体字不动，
 * 避免"后/後""里/裡"这类一对多的误转）；正文显示不受影响（归一仅作用于送引擎的文本）。
 */
object BuiltinTextNormalizer {

    const val TRADITIONAL_THRESHOLD = 0.20

    /**
     * 繁体专用字样本集（覆盖网文高频字；不求穷举，够判定"整章是繁体"即可）。
     * 注意不要把简繁共用字放进来（否则简体正文会被误判成繁体）。
     */
    private val TRADITIONAL_ONLY = (
        "們個這說時國學會議對來後麼樣為與從沒還過開關聽話裡東車長門間問親輕風飛馬魚鳥龍書見語詞讀寫" +
            "讓覺當經發現實點歲處邊遠選認識記應該產業專斷條線納級終結構態勢執務員師傳偉優傷價儀億" +
            "僅戰擊據擺攜敵數藝術節範築簡糧紀約紅純紙細組織絲經濟給統繼續維綱網緊總罰聲職聯聰腦臉" +
            "臨與舉舊艦艱莊華萬葉蓋蘇處虛號蟲補裝觀規視覽觸計訂討訓講許論設訪證評訴試詩詳誇誌認誕" +
            "誰課調談請諸諾謀謂謝鐘鐵銀錯鍋鍵鎮鏡閃閉陣階隨險隱雙雜難靈頂順須預領頻題顏願類顧顯飯"
        ).toSet()

    private fun isCjk(c: Char): Boolean = c in '\u4e00'..'\u9fff'

    /** 繁体专用字占全部汉字比例（无汉字返回 0） */
    fun traditionalRatio(text: String): Double {
        var cjk = 0
        var traditional = 0
        text.forEach { c ->
            if (isCjk(c)) {
                cjk++
                if (c in TRADITIONAL_ONLY) traditional++
            }
        }
        return if (cjk == 0) 0.0 else traditional.toDouble() / cjk
    }

    fun isTraditional(text: String, threshold: Double = TRADITIONAL_THRESHOLD): Boolean =
        traditionalRatio(text) > threshold

    /**
     * 送引擎前的简化归一：繁体占比超阈值才转换；转换失败（字典未就绪等）安全回落原文。
     */
    fun ensureSimplified(text: String, threshold: Double = TRADITIONAL_THRESHOLD): String {
        if (text.isBlank() || !isTraditional(text, threshold)) return text
        return runCatching { io.legado.app.utils.ChineseUtils.t2s(text) }.getOrDefault(text)
    }
}
