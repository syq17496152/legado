package io.legado.app.help.readaloud.casting

import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.tts.builtin.BuiltinVoice
import kotlin.random.Random

/**
 * 换音色候选（5.3）：角色行「换音色」对话框的数据源（纯函数，JVM 可测）。
 *
 * 为什么单独做候选器：原实现把**全部音色**平铺成一条列表，用户在 500+ 音色里既找不到
 * 「性别/年龄接近」的那几个，也看不出「这个音色已被别的角色占用」——等于没做过滤。
 * 这里沿用 5.2 配声口径（性别硬约束 / 年龄），并把「当前 / 撞音」显式带出来。
 *
 * 两种模式：
 * - [Mode.RECOMMEND]：按画像过滤。同性别音色存在时**剔除异性**（与配声硬约束一致），
 *   性别未知的音色保留（不猜，允许用户自己判断）。
 * - [Mode.ALL]：一个都不隐藏，只做排序与标注——用户主动想给角色配异性音色时仍可指定。
 */
object VoiceSwitchCandidates {

    enum class Mode { RECOMMEND, ALL }

    data class Candidate(
        val voice: BuiltinVoice,
        val profile: CharacterVoiceAssignService.VoiceProfile,
        /** 与角色性别一致（角色性别未知时恒 false，不做判断） */
        val sameGender: Boolean,
        /** 与角色年龄一致（角色年龄未写明时恒 false） */
        val ageMatch: Boolean,
        /** 已被同书其他角色占用（撞音） */
        val occupiedElsewhere: Boolean,
        /** 该角色当前绑定的音色 */
        val current: Boolean,
        val score: Int
    ) {
        val voiceName: String get() = voice.voiceName
        val displayName: String get() = voice.displayName
    }

    /** 一键随机只在最相关的前几个里抽；否则「随机」会随机到完全不搭的音色，反而更烦 */
    internal const val RANDOM_POOL = 8

    /**
     * @param voices 当前档位可用音色
     * @param characterGender 角色性别（男/女/男性/…；空=未知，过滤与加分都不做）
     * @param ageHint 角色年龄线索（名字/身份/传记拼串，交给 [CharacterVoiceAssignService.ageFromText] 归一）
     * @param currentVoiceName 该角色当前绑定（engine 侧绑定键）
     * @param occupiedVoiceNames 同书其他角色已占用的音色（撞音集合）
     */
    fun build(
        voices: List<BuiltinVoice>,
        characterGender: String,
        ageHint: String = "",
        currentVoiceName: String = "",
        occupiedVoiceNames: Set<String> = emptySet(),
        mode: Mode = Mode.RECOMMEND
    ): List<Candidate> {
        val gender = BookCharacter.normalizeGender(characterGender)
        val characterAge = CharacterVoiceAssignService.ageFromText(ageHint)
        // 库里一个同性别音色都没有时不能过滤（否则角色会"无人可选"）；此时退回全量
        val hasSameGender = gender.isNotBlank() && voices.any {
            CharacterVoiceAssignService.profileOf(it).gender == gender
        }
        val visible = voices.filter { voice ->
            if (mode == Mode.ALL || gender.isBlank() || !hasSameGender) return@filter true
            val voiceGender = CharacterVoiceAssignService.profileOf(voice).gender
            // 性别未知的音色保留：宁可让用户自己听，也不替用户排除
            voiceGender.isBlank() || voiceGender == gender
        }
        return visible.map { voice ->
            val profile = CharacterVoiceAssignService.profileOf(voice)
            val sameGender = gender.isNotBlank() && profile.gender == gender
            val ageMatch = characterAge.isNotBlank() && profile.age == characterAge
            val current = voice.voiceName == currentVoiceName || voice.id == currentVoiceName
            val occupied = !current && voice.voiceName in occupiedVoiceNames
            var score = 0
            if (sameGender) score += 4
            if (ageMatch) score += 3
            // 与 5.2「同书未使用 +2」同一口径：换音色时也别把别人正在用的音色排在最前
            if (!occupied) score += 2
            Candidate(voice, profile, sameGender, ageMatch, occupied, current, score)
        }.sortedWith(
            // 当前音色沉底（用户打开对话框时第一眼看到的是"可以换成什么"，而不是"现在是什么"）
            compareBy<Candidate> { it.current }
                .thenByDescending { it.score }
                .thenByDescending { it.sameGender }
                .thenBy { it.displayName }
        )
    }

    /**
     * 一键随机：优先「未占用且非当前」；都占用时退化为「非当前」，再退化为全量。
     * 只在排序后的前 [RANDOM_POOL] 个里抽，保证随机结果仍在画像范围内。
     */
    fun randomPick(
        candidates: List<Candidate>,
        random: Random = Random.Default
    ): Candidate? {
        val pool = candidates.filter { !it.current && !it.occupiedElsewhere }
            .ifEmpty { candidates.filter { !it.current } }
            .ifEmpty { candidates }
        if (pool.isEmpty()) return null
        val top = pool.take(RANDOM_POOL)
        return top[random.nextInt(top.size)]
    }
}
