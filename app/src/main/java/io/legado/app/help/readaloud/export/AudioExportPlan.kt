package io.legado.app.help.readaloud.export

import io.legado.app.help.readaloud.role.OfflineRoleSegment

/** 导出计划的一段：文本 + 朗读者音色 + 角色名（旁白固定"旁白"） */
data class ExportPiece(val voiceName: String, val roleName: String, val text: String)

/**
 * 章节音频导出计划（T8.5，纯函数可单测）。
 *
 * 口径：
 * - 直接复用离线分镜（`OfflineRoleSegment`）与角色→音色绑定，**与实时朗读同一套归因**，
 *   导出的音频与"点听书"听到的一致（不另起一套分段逻辑）；
 * - 旁白/未识别段一律回落旁白音色（不静默丢段，也不乱换声）；
 * - 单段过长按标点切成 ≤ maxChars 的小块，避免一次送引擎过长文本。
 */
object AudioExportPlan {

    const val NARRATOR_LABEL = "旁白"

    private val ILLEGAL_FILE_CHARS = Regex("[\\\\/:*?\"<>|\\r\\n\\t]")
    private val PUNCTUATION = charArrayOf('。', '！', '？', '；', '…', '，')

    fun build(
        segments: List<OfflineRoleSegment>,
        paragraphs: List<String>,
        characterVoices: Map<Long, String>,
        narratorVoice: String,
        maxChars: Int = 220
    ): List<ExportPiece> {
        if (segments.isEmpty() || paragraphs.isEmpty()) return emptyList()
        val narrator = narratorVoice.trim()
        val out = ArrayList<ExportPiece>(segments.size)
        segments.forEach { segment ->
            val paragraph = paragraphs.getOrNull(segment.paragraphIndex) ?: return@forEach
            val start = segment.start.coerceIn(0, paragraph.length)
            val end = segment.end.coerceIn(start, paragraph.length)
            val text = paragraph.substring(start, end).trim()
            if (text.isEmpty()) return@forEach
            val isCharacter = segment.roleType != "narrator" && segment.recognized
            val characterVoice = if (isCharacter) characterVoices[segment.characterId] else null
            // 已识别但没配音的角色也回落旁白音色（保证整章连续，不乱消失）
            val voice = characterVoice?.takeIf { it.isNotBlank() } ?: narrator
            val roleName = if (isCharacter && segment.characterName.isNotBlank()) {
                segment.characterName
            } else {
                NARRATOR_LABEL
            }
            var rest = text
            while (rest.length > maxChars) {
                val cut = splitIndex(rest, maxChars)
                out.add(ExportPiece(voice, roleName, rest.substring(0, cut).trim()))
                rest = rest.substring(cut).trimStart()
            }
            if (rest.isNotBlank()) out.add(ExportPiece(voice, roleName, rest))
        }
        return out
    }

    /** 优先在标点后切（找不到就硬切），保证不把句子拦腰截断得太难看 */
    private fun splitIndex(text: String, maxChars: Int): Int {
        val limit = maxChars.coerceIn(1, text.length - 1)
        val index = text.lastIndexOfAny(PUNCTUATION, limit - 1)
        return if (index in 1 until limit) index + 1 else limit
    }

    /** 导出文件名（SAF 默认名）：书名-章节名.wav，非法字符替换为下划线并限长 */
    fun fileName(bookName: String, chapterTitle: String): String {
        val book = sanitize(bookName).ifBlank { "book" }
        val chapter = sanitize(chapterTitle).ifBlank { "chapter" }
        return "$book-$chapter.wav"
    }

    private fun sanitize(value: String): String =
        value.replace(ILLEGAL_FILE_CHARS, "_").trim().take(24)

    /** 导出结果文案（给页面提示用，纯函数可单测） */
    fun summary(pieces: Int, failed: Int, seconds: Long, bytes: Long): String {
        val size = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
        val failText = if (failed > 0) " · 失败 $failed" else ""
        return "已导出 ${pieces - failed}/$pieces 段 · ${seconds}s · $size$failText"
    }
}
