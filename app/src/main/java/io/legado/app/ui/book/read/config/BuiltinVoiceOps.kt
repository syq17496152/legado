package io.legado.app.ui.book.read.config

import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.help.tts.builtin.VoiceTagNormalizer

/**
 * 音色域动作（T6.3/T6.4）：从管理页 Activity 抽出的"删音色/改标签"逻辑。
 * 只负责索引写回与用户提示文案；UI 状态仍由 Activity 管。
 */
internal object BuiltinVoiceOps {

    /**
     * 删除音色并回告"是否还有角色绑着它"（T6.4：删除连带绑定提示）。
     * @param bookKey 当前书的角色键（空 = 未开书，跳过绑定检查）
     */
    suspend fun deleteVoice(voiceId: String, bookKey: String?): String {
        val target = runCatching { BuiltinTtsRuntime.voiceStore().findById(voiceId) }.getOrNull()
        BuiltinTtsRuntime.deleteVoice(voiceId)
        val bound = if (bookKey != null && target != null) {
            runCatching { CharacterVoiceAssignService.rows(bookKey) }
                .getOrDefault(emptyList())
                .count { it.voiceName == target.voiceName }
        } else {
            0
        }
        return if (bound > 0) {
            "音色已删除；本书仍有 $bound 个角色绑定它，请到「角色配音」重新配声"
        } else {
            "音色已删除"
        }
    }

    /**
     * 改音色标签（名称/性别/年龄/性格）：只写索引，不动音频。
     * 绑定键 `voiceName=id|名称` 含名称 ⇒ 改名后旧绑定会回落，提示用户重新绑一次。
     *
     * 性格（2026-11-10）：情绪/气质是唯一不能自动推断的维度（音色包几乎不带标签），
     * 所以必须允许手动多选；写入时**替换**旧性格标签（用户取消勾选要能真的去掉），
     * 非性格类标签（池名/来源等）原样保留。
     */
    fun saveMeta(
        voiceId: String,
        name: String,
        gender: String,
        age: String,
        moods: List<String> = emptyList()
    ): String {
        val store = BuiltinTtsRuntime.voiceStore()
        val voice = store.findById(voiceId) ?: return "音色不存在（可能已被删除）"
        // 先剔除旧的性格标签（归一后能识别的），再追加本次勾选的规范 key
        val keptTags = voice.tags.filter { VoiceTagNormalizer.mood(it).isBlank() }
        val genderKey = VoiceTagNormalizer.gender(gender)
        val ageKey = VoiceTagNormalizer.age(age)
        val tags = VoiceTagNormalizer.tagsWithMoods(keptTags, genderKey, ageKey, moods)
        store.upsert(
            voice.copy(
                name = name.ifBlank { voice.name },
                gender = genderKey,
                age = ageKey,
                tags = tags.distinct()
            )
        )
        return "已更新音色标签（若改名，请到角色配音页重新绑一次该音色）"
    }
}
