package io.legado.app.ui.book.read.config

import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime

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
     * 改音色标签（名称/性别/年龄）：只写索引，不动音频。
     * 绑定键 `voiceName=id|名称` 含名称 ⇒ 改名后旧绑定会回落，提示用户重新绑一次。
     */
    fun saveMeta(voiceId: String, name: String, gender: String, age: String): String {
        val store = BuiltinTtsRuntime.voiceStore()
        val voice = store.findById(voiceId) ?: return "音色不存在（可能已被删除）"
        val tags = (voice.tags + listOfNotNull(
            gender.takeIf { it.isNotBlank() },
            age.takeIf { it.isNotBlank() }
        )).distinct()
        store.upsert(
            voice.copy(
                name = name.ifBlank { voice.name },
                gender = gender,
                age = age,
                tags = tags
            )
        )
        return "已更新音色标签（若改名，请到角色配音页重新绑一次该音色）"
    }
}
