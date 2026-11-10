package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音色标签值域归一（2026-11-10 用户裁定「282 个音色乱七八糟」）。
 *
 * 锁定的失守现场：导入包写中文（女/青年/未定）、内置播种写英文（female/teen），
 * 历史索引两种混存 ⇒ 列表只能平铺、筛选落不到桶里、池名把内部英文值暴露给用户。
 */
class VoiceTagNormalizerTest {

    @Test
    fun `性别中英混写归一`() {
        assertEquals("female", VoiceTagNormalizer.gender("女"))
        assertEquals("female", VoiceTagNormalizer.gender("女性"))
        assertEquals("female", VoiceTagNormalizer.gender("FEMALE"))
        assertEquals("male", VoiceTagNormalizer.gender("男"))
        assertEquals("male", VoiceTagNormalizer.gender("male"))
        assertEquals("male", VoiceTagNormalizer.gender("男声"))
    }

    @Test
    fun `未定必须是空值而不是一个值`() {
        assertEquals("", VoiceTagNormalizer.gender("未定"))
        assertEquals("", VoiceTagNormalizer.gender("未知"))
        assertEquals("", VoiceTagNormalizer.gender("unknown"))
        assertEquals("", VoiceTagNormalizer.gender(""))
        assertEquals("", VoiceTagNormalizer.age("未定"))
        assertEquals("", VoiceTagNormalizer.age("不详"))
    }

    @Test
    fun `年龄四档归一（中文与英文同桶）`() {
        assertEquals("teen", VoiceTagNormalizer.age("少年"))
        assertEquals("teen", VoiceTagNormalizer.age("童"))
        assertEquals("teen", VoiceTagNormalizer.age("teen"))
        assertEquals("youth", VoiceTagNormalizer.age("青年"))
        assertEquals("youth", VoiceTagNormalizer.age("young"))
        assertEquals("middle", VoiceTagNormalizer.age("中年"))
        assertEquals("middle", VoiceTagNormalizer.age("middle"))
        assertEquals("aged", VoiceTagNormalizer.age("老年"))
        assertEquals("aged", VoiceTagNormalizer.age("爷爷"))
        assertEquals("aged", VoiceTagNormalizer.age("senior"))
    }

    @Test
    fun `标签归一剔除未知值并把中英写法替换成规范值`() {
        val tags = VoiceTagNormalizer.tags(
            listOf("女", "青年", "narration", "未定", "温柔"),
            gender = "女",
            age = "青年"
        )
        assertEquals(listOf("female", "youth", "narration", "gentle"), tags)
    }

    @Test
    fun `池名英文内部值转中文展示`() {
        assertEquals("旁白", VoiceTagNormalizer.poolLabel("narration"))
        assertEquals("青年", VoiceTagNormalizer.poolLabel("youth"))
        assertEquals("老年", VoiceTagNormalizer.poolLabel("aged"))
        assertEquals("少年", VoiceTagNormalizer.poolLabel("teen"))
        assertEquals("女声", VoiceTagNormalizer.poolLabel("female"))
        // 已经是中文的原样返回
        assertEquals("青年", VoiceTagNormalizer.poolLabel("青年"))
    }

    @Test
    fun `性格关键词归一并与配声同口径`() {
        assertEquals("gentle", VoiceTagNormalizer.mood("温柔"))
        assertEquals("cool", VoiceTagNormalizer.mood("清冷"))
        assertEquals("calm", VoiceTagNormalizer.mood("沉稳"))
        assertEquals("majestic", VoiceTagNormalizer.mood("威严"))
        // 已归一 key 直传仍命中
        assertEquals("gentle", VoiceTagNormalizer.mood("gentle"))
        // 自定义标签不误判
        assertEquals("", VoiceTagNormalizer.mood("豫剧"))
        assertEquals("温柔", VoiceTagNormalizer.moodLabel("gentle"))
    }

    @Test
    fun `年龄展示标签覆盖四档`() {
        assertEquals("少年", VoiceTagNormalizer.ageLabel("teen"))
        assertEquals("青年", VoiceTagNormalizer.ageLabel("youth"))
        assertEquals("中年", VoiceTagNormalizer.ageLabel("middle"))
        assertEquals("老年", VoiceTagNormalizer.ageLabel("aged"))
        assertEquals("", VoiceTagNormalizer.ageLabel(""))
    }

    /**
     * 性格只能手动标注（音色包几乎不带气质词）：保存口径必须"替换而不是并集"，
     * 否则用户取消勾选后性格筛选永远清不掉。
     */
    @Test
    fun `性格标注保存为替换语义并保留其它标签`() {
        val tags = VoiceTagNormalizer.tagsWithMoods(
            existing = listOf("旁白", "清冷", "女"),
            gender = "女",
            age = "青年",
            moods = listOf("温柔", "沉稳")
        )
        assertEquals(listOf("旁白", "female", "youth", "gentle", "calm"), tags)
        // 清空性格 ⇒ 旧性格标签必须消失，其它标签保留
        val cleared = VoiceTagNormalizer.tagsWithMoods(tags, "女", "青年", emptyList())
        assertEquals(listOf("旁白", "female", "youth"), cleared)
    }
}
