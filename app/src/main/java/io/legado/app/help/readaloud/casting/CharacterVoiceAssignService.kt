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
        val narrator = pickNarrator(voices, narratorHint)?.also { used.add(it.voiceName) }
        val ordered = characters.sortedWith(
            compareByDescending<BookCharacter> { it.roleLevel }.thenBy { it.id }
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
            val ranked = voices.sortedByDescending { score(it, gender, ageText) }
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
                    reason = reasonOf(preferred, gender, ageText),
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
        val planned = plan(
            characters = characters.filter { overwrite || it.speechRouteJson.isBlank() },
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
    ): Boolean = withContext(Dispatchers.IO) {
        val bookKey = book.characterBookKey()
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

    private fun pickNarrator(voices: List<BuiltinVoice>, hint: String): BuiltinVoice? {
        voices.firstOrNull { it.voiceName == hint || it.name == hint || it.id == hint }?.let { return it }
        return voices.firstOrNull { it.tags.contains("narration") }
            ?: voices.firstOrNull { it.gender == "female" && it.age == "youth" }
            ?: voices.firstOrNull()
    }

    private fun score(voice: BuiltinVoice, gender: String, ageText: String): Int {
        var score = 0
        val voiceGender = when {
            voice.gender.contains("男") || voice.tags.contains("male") -> "male"
            voice.gender.contains("女") || voice.tags.contains("female") -> "female"
            voice.name.contains("男") -> "male"
            voice.name.contains("女") -> "female"
            else -> ""
        }
        if (gender.isNotBlank() && voiceGender == gender) score += 4
        if (gender.isNotBlank() && voiceGender.isNotBlank() && voiceGender != gender) score -= 3
        val ageTag = when {
            voice.age == "teen" || voice.tags.contains("teen") || voice.name.contains("少年") -> "teen"
            voice.age == "aged" || voice.tags.contains("aged") || voice.name.contains("老年") || voice.name.contains("老") -> "aged"
            voice.age == "youth" || voice.tags.contains("youth") || voice.name.contains("青年") -> "youth"
            else -> ""
        }
        val characterAge = when {
            ageText.contains("少年") || ageText.contains("少女") || ageText.contains("童") -> "teen"
            ageText.contains("老") || ageText.contains("年迈") || ageText.contains("长辈") || ageText.contains("父") || ageText.contains("母") -> "aged"
            ageText.contains("青年") || ageText.contains("年轻") -> "youth"
            else -> ""
        }
        if (characterAge.isNotBlank() && ageTag == characterAge) score += 3
        if (voice.origin == BuiltinVoice.ORIGIN_PRESET) score += 1
        return score
    }

    private fun reasonOf(voice: BuiltinVoice, gender: String, ageText: String): String {
        val parts = mutableListOf<String>()
        if (gender.isNotBlank() && (voice.gender.contains("男") || voice.gender.contains("女") ||
                    voice.tags.contains("male") || voice.tags.contains("female"))
        ) parts.add("性别一致")
        if (ageText.isNotBlank()) parts.add("年龄适配")
        if (voice.origin == BuiltinVoice.ORIGIN_PRESET) parts.add("内置")
        return parts.joinToString("·").ifBlank { "默认分配" }
    }
}
