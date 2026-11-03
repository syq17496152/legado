package io.legado.app.help.readaloud.casting

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.readaloud.role.BookAliasStore
import io.legado.app.help.readaloud.role.FixedRoles
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.help.tts.builtin.BuiltinVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 角色-音色分配门面（T5.4）：UI 只调本门面，不依赖 AI tool 层。
 * 算法要点：性别优先 > 年龄一致 > 旁白标签；同书稳定（按角色 id 定序）；撞音避让；
 * 落库口径（review-final P0-5）：`toneID = voice.name`（逐字符）+ `speakerName = 显示名`，缺一即回落默认音。
 */
object CharacterVoiceAssignService {

    data class Assignment(
        val characterId: Long,
        val characterName: String,
        val voiceName: String,
        val voiceDisplayName: String,
        val reason: String,
        val conflict: Boolean = false
    )

    data class AssignResult(
        val assignments: List<Assignment>,
        val narratorVoice: String = "",
        val conflicts: List<Assignment> = emptyList()
    )

    /**
     * 音色元数据（归一化后）：
     * 导入包（506 音色）里 `gender`/`age` 可能是英文（male/female/teen）、中文（男声/中年）或只写在 tags 里，
     * 打分前必须先归一化，否则「性别优先」会全部失效 ⇒ 分配看起来"不分性别"（2026-11-03 用户反馈）。
     */
    internal data class VoiceTraits(
        val gender: String,
        val age: String,
        /** 音色气质（温柔/清冷/沉稳/活泼…），用于与角色性格做契合度加分 */
        val moods: Set<String> = emptySet()
    )

    /** 性别归一化：中文（男/男声/男性）、英文（male/m/man）、tags、名字都能识别 */
    internal fun voiceTraitsOf(voice: BuiltinVoice): VoiceTraits = VoiceTraits(
        gender = normalizeVoiceGender(voice),
        age = normalizeVoiceAge(voice),
        moods = moodsFromText(listOf(voice.tags.joinToString(" "), voice.name).joinToString(" "))
    )

    /**
     * 气质关键词归一化（性格匹配用）：角色 `personality` 与音色 tags/名字共用一套口径。
     * 只做"同气质"加分，不做冲突扣分——音色库标注风格不统一，扣分容易把最合适的音色排掉。
     */
    internal fun moodsFromText(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val value = text.lowercase()
        return MOOD_GROUPS.mapNotNull { (mood, keywords) ->
            mood.takeIf { keywords.any { keyword -> value.contains(keyword) } }
        }.toSet()
    }

    /** 气质分组（key=归一化气质，value=中英文关键词） */
    private val MOOD_GROUPS = listOf(
        "gentle" to listOf("温柔", "柔和", "甜美", "软糯", "亲和"),
        "cool" to listOf("清冷", "冷漠", "淡漠", "冰冷", "高冷"),
        "calm" to listOf("沉稳", "稳重", "低沉", "成熟", "厚重", "冷静"),
        "lively" to listOf("活泼", "元气", "俏皮", "灵动", "欢快", "开朗"),
        "majestic" to listOf("威严", "浑厚", "庄重", "磁性"),
        "husky" to listOf("沙哑", "烟嗓", "沧桑"),
        "childish" to listOf("稚嫩", "童声", "奶声", "幼态")
    )

    /**
     * 性格 → 年龄倾向兜底（纯函数可单测）：
     * 506 音色导入包只有性别/年龄标签、几乎无气质标注（真机统计 512 音色中仅 6 条含气质词），
     * 仅靠 tags 契合会让「性格」对配声完全失效 ⇒ 角色年龄未知时按性格倾向做一档弱加分（+1）。
     */
    internal fun moodAgeAffinity(moods: Set<String>): String = when {
        moods.any { it in setOf("childish", "lively", "cool", "gentle") } -> "youth"
        moods.any { it in setOf("majestic", "husky") } -> "aged"
        moods.any { it == "calm" } -> "middle"
        else -> ""
    }

    private fun normalizeVoiceGender(voice: BuiltinVoice): String {
        val own = voice.gender.trim().lowercase()
        if (own.isNotBlank()) {
            genderFromText(own)?.let { return it }
        }
        return genderFromText(
            listOf(voice.tags.joinToString(" "), voice.name).joinToString(" ").lowercase()
        ).orEmpty()
    }

    private fun genderFromText(text: String): String? = when {
        text.contains("男") || text.contains("男性") || text.contains("男声") -> "male"
        text.contains("女") || text.contains("女性") || text.contains("女声") -> "female"
        Regex("(^|[^a-z])male([^a-z]|$)").containsMatchIn(text) -> "male"
        Regex("(^|[^a-z])female([^a-z]|$)").containsMatchIn(text) -> "female"
        Regex("(^|[^a-z])m([^a-z]|$)").containsMatchIn(text) -> "male"
        Regex("(^|[^a-z])f([^a-z]|$)").containsMatchIn(text) -> "female"
        else -> null
    }

    /** 年龄归一化：把英文 teen/youth/aged 与中文 童/少年/青年/中年/老年 统一到同一套取值 */
    private fun normalizeVoiceAge(voice: BuiltinVoice): String {
        val text = listOf(voice.age, voice.tags.joinToString(" "), voice.name)
            .joinToString(" ")
            .lowercase()
        return ageFromText(text)
    }

    internal fun ageFromText(text: String): String = when {
        text.contains("儿童") || text.contains("童") || text.contains("少年") || text.contains("少女") ||
            text.contains("child") || text.contains("teen") -> "teen"
        text.contains("青年") || text.contains("年轻") || text.contains("youth") -> "youth"
        text.contains("中年") || text.contains("middle") -> "middle"
        text.contains("老年") || text.contains("年迈") || text.contains("长辈") ||
            text.contains("老") || text.contains("父") || text.contains("母") ||
            text.contains("aged") || text.contains("elder") || text.contains("old") -> "aged"
        else -> ""
    }

    /**
     * 手动「重新配声」是否覆盖既有绑定（纯函数，便于单测锁定语义）。
     *
     * 返回 true：用户主动点"重新配声"就必须重算所有角色；若沿用 overwrite=false，
     * 算法升级（性别/年龄归一化）对已配过声的用户永远不生效（2026-11-03 真机实证：57 条旧绑定被跳过）。
     */
    fun manualAssignOverwrites(): Boolean = true

    /**
     * 纯函数配声（可单测）：不读写数据库。
     * 优先级（T8.17）：固定绑定（fixed_roles：角色名→音色）> 性别/年龄打分 > 撞音避让 > 兜底。
     */
    fun plan(
        characters: List<BookCharacter>,
        voices: List<BuiltinVoice>,
        narratorHint: String = "",
        fixedVoiceMap: Map<String, String> = emptyMap()
    ): AssignResult {
        if (voices.isEmpty()) return AssignResult(emptyList())
        val used = HashSet<String>()
        val assignments = mutableListOf<Assignment>()
        // 预计算音色元数据（归一化一次，避免每个角色 × 每个音色重复做字符串解析）
        val traits = voices.associate { it.voiceName to voiceTraitsOf(it) }
        val narrator = pickNarrator(voices, traits, narratorHint)?.also { used.add(it.voiceName) }
        // 主角优先（T8.13）：名字/身份含"主角/男主/女主"的角色先挑音色，避免被配角先占走最佳匹配
        val ordered = characters.sortedWith(
            compareByDescending<BookCharacter> { if (isLeadLike(it)) 1 else 0 }
                .thenByDescending { it.roleLevel }
                .thenBy { it.id }
        )
        ordered.forEach { character ->
            // ① 固定绑定优先（与 CloneTTS fixed_roles.json 同 schema，用户手改过就必须尊重）
            val fixed = fixedVoiceMap[character.name]?.takeIf { it.isNotBlank() }
                ?.let { bound -> voices.firstOrNull { it.matches(bound) } }
            if (fixed != null) {
                val conflict = fixed.voiceName in used
                used.add(fixed.voiceName)
                assignments.add(
                    Assignment(
                        characterId = character.id,
                        characterName = character.name,
                        voiceName = fixed.voiceName,
                        voiceDisplayName = fixed.displayName,
                        reason = "固定绑定",
                        conflict = conflict
                    )
                )
                return@forEach
            }
            val gender = BookCharacter.normalizeGender(character.gender).ifBlank {
                BookCharacter.inferGender(listOf(character.name, character.identity, character.biography).joinToString(" "))
            }
            val ageText = listOf(character.name, character.identity, character.biography).joinToString(" ")
            // 性格/气质文本（T8.17 扩展）：人物性格与传记描述用于和音色气质做契合度加分
            val characterMoods = moodsFromText(
                listOf(character.personality, character.biography).joinToString(" ")
            )
            val ranked = voices.sortedByDescending {
                score(it, traits.getValue(it.voiceName), gender, ageText, characterMoods)
            }
            val preferred = ranked.firstOrNull { it.voiceName !in used }
                ?: ranked.firstOrNull()
                ?: return@forEach
            val conflict = preferred.voiceName in used
            used.add(preferred.voiceName)
            assignments.add(
                Assignment(
                    characterId = character.id,
                    characterName = character.name,
                    voiceName = preferred.voiceName,
                    voiceDisplayName = preferred.displayName,
                    reason = reasonOf(
                        preferred, traits.getValue(preferred.voiceName), gender, ageText, characterMoods
                    ),
                    conflict = conflict
                )
            )
        }
        return AssignResult(
            assignments = assignments,
            narratorVoice = narrator?.voiceName.orEmpty(),
            conflicts = assignments.filter { it.conflict }
        )
    }

    /** 一键分配并落库（写 BookCharacter.speechRouteJson + 书级 fixed_roles） */
    suspend fun assignAll(
        context: Context,
        book: Book,
        overwrite: Boolean = false
    ): AssignResult = withContext(Dispatchers.IO) {
        val bookKey = book.characterBookKey()
        val characters = runCatching { appDb.bookCharacterDao.characters(bookKey) }.getOrDefault(emptyList())
        if (characters.isEmpty()) return@withContext AssignResult(emptyList())
        BuiltinTtsRuntime.init(context)
        BuiltinTtsRuntime.ensureSeeded()
        val voices = BuiltinTtsRuntime.voices()
        if (voices.isEmpty()) return@withContext AssignResult(emptyList())
        val aliasStore = BookAliasStore(BuiltinTtsRuntime.paths().rulesRoot)
        // 手动入口（UI「重新配声」）传 overwrite=false，但算法升级必须能重算旧绑定，
        // 否则性别/年龄归一化对已配过声的用户永远不生效（2026-11-03 真机实证：57 条旧绑定被跳过）。
        val effectiveOverwrite = overwrite || manualAssignOverwrites()
        val planned = plan(
            characters = characters.filter { effectiveOverwrite || it.speechRouteJson.isBlank() },
            voices = voices,
            narratorHint = aliasStore.narratorVoice(bookKey).orEmpty(),
            fixedVoiceMap = aliasStore.merged(bookKey).roles
        )
        val packageName = context.packageName
        var changed = 0
        planned.assignments.forEach { assignment ->
            val character = characters.firstOrNull { it.id == assignment.characterId } ?: return@forEach
            val route = SpeechRoute(
                engineType = SpeechRoute.ENGINE_SYSTEM,
                engineValue = packageName,
                speakerName = assignment.voiceDisplayName,
                toneID = assignment.voiceName,
                source = SpeechRoute.SOURCE_AUTO
            )
            runCatching {
                appDb.bookCharacterDao.updateCharacter(
                    character.copy(
                        speechRouteJson = route.toJson(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                changed++
            }.onFailure { AppLog.put("角色配声写库失败（${character.name}）：${it.message}") }
        }
        // 书级固定绑定（与 CloneTTS 同 schema，便于互通与纠正学习）
        val roles = planned.assignments.associate {
            it.characterName to it.voiceDisplayName
        }
        val narratorVoiceName = voices.firstOrNull { it.voiceName == planned.narratorVoice }?.displayName.orEmpty()
        val maleLead = characters.firstOrNull { it.roleLevel == BookCharacter.ROLE_MAIN && it.gender == BookCharacter.GENDER_MALE }
        val femaleLead = characters.firstOrNull { it.roleLevel == BookCharacter.ROLE_MAIN && it.gender == BookCharacter.GENDER_FEMALE }
        aliasStore.writeBook(
            bookKey,
            FixedRoles(
                narrator = narratorVoiceName,
                maleLead = maleLead?.name.orEmpty(),
                maleLeadVoice = maleLead?.let { c -> planned.assignments.firstOrNull { it.characterId == c.id }?.voiceDisplayName }.orEmpty(),
                femaleLead = femaleLead?.name.orEmpty(),
                femaleLeadVoice = femaleLead?.let { c -> planned.assignments.firstOrNull { it.characterId == c.id }?.voiceDisplayName }.orEmpty(),
                roles = roles
            )
        )
        AppLog.put("角色配声完成：角色 ${planned.assignments.size} / 实际写入 $changed / 撞音 ${planned.conflicts.size}")
        planned.copy(assignments = planned.assignments.map { it.copy(conflict = it.voiceName in planned.conflicts.map { c -> c.voiceName }) })
    }

    /** 分镜/播放期需要的角色索引（离线归因输入） */
    suspend fun roleCharacters(bookKey: String): List<OfflineRoleResolver.RoleCharacter> =
        withContext(Dispatchers.IO) {
            runCatching {
                appDb.bookCharacterDao.characters(bookKey).map { character ->
                    OfflineRoleResolver.RoleCharacter(
                        id = character.id,
                        name = character.name,
                        aliases = listOfNotNull(
                            character.name,
                            character.displayName(),
                            character.identity.takeIf { it.length in 2..8 }
                        ),
                        gender = BookCharacter.normalizeGender(character.gender),
                        roleLevel = character.roleLevel
                    )
                }
            }.getOrDefault(emptyList())
        }

    /** 角色配音页一行（T5.4）：角色 + 当前绑定音色 + 撞音标记 */
    data class RoleVoiceRow(
        val characterId: Long,
        val name: String,
        val gender: String,
        val roleLevel: Int,
        val voiceName: String,
        val voiceDisplayName: String
    ) {
        val configured: Boolean get() = voiceName.isNotBlank() || voiceDisplayName.isNotBlank()
    }

    suspend fun rows(bookKey: String): List<RoleVoiceRow> = withContext(Dispatchers.IO) {
        runCatching {
            appDb.bookCharacterDao.characters(bookKey).map { character ->
                val route = SpeechRoute.fromJson(character.speechRouteJson)
                RoleVoiceRow(
                    characterId = character.id,
                    name = character.name,
                    gender = BookCharacter.normalizeGender(character.gender),
                    roleLevel = character.roleLevel,
                    voiceName = route.toneID,
                    voiceDisplayName = route.speakerName
                )
            }.sortedWith(compareByDescending<RoleVoiceRow> { it.roleLevel }.thenBy { it.characterId })
        }.getOrDefault(emptyList())
    }

    /** 撞音键（同一音色被多个角色绑定）；空音色不算撞音 */
    fun conflictingVoices(rows: List<RoleVoiceRow>): Set<String> =
        rows.map { it.voiceName }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    /** 单角色换音色（T5.4，手动绑定 ⇒ source=manual） */
    suspend fun assignVoice(
        context: Context,
        book: Book,
        characterId: Long,
        voice: BuiltinVoice
    ): Boolean = assignVoice(context, book.characterBookKey(), characterId, voice)

    /** 单角色换音色（按 bookKey；供 MCP/Web 等无 Book 对象场景复用） */
    suspend fun assignVoice(
        context: Context,
        bookKey: String,
        characterId: Long,
        voice: BuiltinVoice
    ): Boolean = withContext(Dispatchers.IO) {
        val character = runCatching {
            appDb.bookCharacterDao.characters(bookKey).firstOrNull { it.id == characterId }
        }.getOrNull() ?: return@withContext false
        val route = SpeechRoute(
            engineType = SpeechRoute.ENGINE_SYSTEM,
            engineValue = context.packageName,
            speakerName = voice.displayName,
            toneID = voice.voiceName,
            source = SpeechRoute.SOURCE_MANUAL
        )
        runCatching {
            appDb.bookCharacterDao.updateCharacter(
                character.copy(speechRouteJson = route.toJson(), updatedAt = System.currentTimeMillis())
            )
            true
        }.getOrDefault(false)
    }

    /** 名字/身份里的主角信号（T8.13）：用于给主角更高挑音优先级，不改变最终打分规则 */
    private fun isLeadLike(character: BookCharacter): Boolean {
        val text = character.name + character.identity
        return LEAD_KEYWORDS.any { text.contains(it) }
    }

    private fun pickNarrator(
        voices: List<BuiltinVoice>,
        traits: Map<String, VoiceTraits>,
        hint: String
    ): BuiltinVoice? {
        voices.firstOrNull { it.voiceName == hint || it.name == hint || it.id == hint }?.let { return it }
        return voices.firstOrNull { it.tags.contains("narration") }
            ?: voices.firstOrNull {
                traits[it.voiceName]?.gender == "female" && traits[it.voiceName]?.age == "youth"
            }
            ?: voices.firstOrNull()
    }

    private fun score(
        voice: BuiltinVoice,
        traits: VoiceTraits,
        gender: String,
        ageText: String,
        characterMoods: Set<String> = emptySet()
    ): Int {
        var score = 0
        val voiceGender = traits.gender
        if (gender.isNotBlank() && voiceGender == gender) score += 4
        if (gender.isNotBlank() && voiceGender.isNotBlank() && voiceGender != gender) score -= 3
        val ageTag = traits.age
        val characterAge = ageFromText(ageText)
        if (characterAge.isNotBlank() && ageTag == characterAge) score += 3
        // 明确年龄冲突（如老年角色配少年音色）要扣分，避免只靠"性别对"就抢到最佳
        if (characterAge.isNotBlank() && ageTag.isNotBlank() && ageTag != characterAge) score -= 1
        // 性格/气质契合：性别与年龄已相同时，用气质区分（2026-11-03 用户要求"性格"也参与）
        if (characterMoods.isNotEmpty() && traits.moods.any { it in characterMoods }) score += 2
        // 音色库缺气质标签时的兜底：角色年龄未知时，按性格倾向偏年轻/偏成熟弱加分（+1）
        val moodAge = moodAgeAffinity(characterMoods)
        if (characterAge.isBlank() && moodAge.isNotBlank() && ageTag == moodAge) score += 1
        if (voice.origin == BuiltinVoice.ORIGIN_PRESET) score += 1
        return score
    }

    private fun reasonOf(
        voice: BuiltinVoice,
        traits: VoiceTraits,
        gender: String,
        ageText: String,
        characterMoods: Set<String> = emptySet()
    ): String {
        val parts = mutableListOf<String>()
        if (gender.isNotBlank() && traits.gender == gender) parts.add("性别一致")
        if (ageText.isNotBlank() && traits.age.isNotBlank() && ageFromText(ageText) == traits.age) {
            parts.add("年龄适配")
        }
        if (characterMoods.isNotEmpty() && traits.moods.any { it in characterMoods }) {
            parts.add("性格契合")
        }
        if (voice.origin == BuiltinVoice.ORIGIN_PRESET) parts.add("内置")
        return parts.joinToString("·").ifBlank { "默认分配" }
    }

    private val LEAD_KEYWORDS = listOf("主角", "男主", "女主")
}
