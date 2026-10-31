package io.legado.app.ui.book.read.config

import io.legado.app.help.tts.builtin.BuiltinVoice
import java.util.Locale

/** 管理页纯逻辑（可单测）：导入档案推断 + 体积格式化 + 页签数量 */
internal object BuiltinTtsUiSupport {

    fun guessProfile(name: String): String = when {
        name.equals("fast", true) || name.contains("piper", true) || name.contains("vits", true) -> "fast"
        else -> "clone"
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
    }

    /** 一键开书结果文案（纯函数可单测）：角色/配声/模板/分镜 四段齐全，便于用户与日志定位卡点 */
    fun multiRoleSummary(
        characters: Int,
        discovered: Int,
        assigned: Int,
        activated: Boolean,
        storyboardSegments: Int = -1
    ): String {
        val template = if (activated) "已启用多角色" else "多角色模板启用失败"
        val storyboard = when {
            storyboardSegments < 0 -> ""
            storyboardSegments == 0 -> " · 分镜未生成（可点“识别角色”重试）"
            else -> " · 分镜 $storyboardSegments 段"
        }
        return "角色 $characters（新增 $discovered）· 配声 $assigned · $template$storyboard"
    }

    /** 存储总览文案（T4.9，纯函数可单测）：模型 / 音色 / 缓存 / 合计 */
    fun storageSummary(modelBytes: Long, voiceBytes: Long, cacheBytes: Long): String {
        val total = modelBytes + voiceBytes + cacheBytes
        return "存储：模型 ${formatBytes(modelBytes)} · 音色 ${formatBytes(voiceBytes)}" +
            " · 缓存 ${formatBytes(cacheBytes)} · 合计 ${formatBytes(total)}"
    }

    /** 一键启用结果文案（T4.1，纯函数可单测）：引擎 / 多角色 / 自检三段缺一不可 */
    fun quickEnableSummary(engineMessage: String, castingActivated: Boolean, checkMessage: String): String {
        val casting = if (castingActivated) "多角色已启用" else "多角色未启用（可稍后在开书向导重试）"
        return "$engineMessage · $casting · $checkMessage"
    }

    /** 批量试听取样（T8.8，纯函数可单测）：优先非内置音色，最多 limit 个；全是内置时回落全量 */
    fun previewBatch(voices: List<BuiltinVoice>, limit: Int = 5): List<BuiltinVoice> =
        voices.filter { it.origin != BuiltinVoice.ORIGIN_PRESET }.ifEmpty { voices }.take(limit)

    /** 打标结果文案（T6.2/T6.3）：处理数 + 三分类 + 本次新建池数 */
    fun autoTagMessage(processed: Int, male: Int, female: Int, unknown: Int, pooled: Int): String =
        "打标完成：处理 $processed · 男 $male / 女 $female / 未定 $unknown · 分组 $pooled"

    /** 池筛选 chips 显示哪些池（T8.16）：去重 + 上限，避免 chips 行溢出 */
    fun visiblePoolNames(voices: List<BuiltinVoice>, limit: Int = 4): List<String> =
        voices.map { it.groupId }.filter { it.isNotBlank() }.distinct().take(limit)

    /** 分镜改绑结果文案（纯函数可单测） */
    fun segmentAssignMessage(ok: Boolean, narrator: Boolean, characterName: String): String = when {
        !ok -> "改绑失败（本章还没有识别缓存，请先点“识别角色”）"
        narrator -> "已改为旁白（返回阅读页重开朗读生效）"
        else -> "已改为${characterName}（可在分镜页继续校正）"
    }

    /**
     * 分镜角色标签（T5.5，纯函数可单测）：旁白 / 角色名 / 未识别。
     * 未识别必须显式标出（AD-15：不静默归旁白）。
     */
    fun storyboardLabel(roleType: String, recognized: Boolean, characterName: String): String = when {
        roleType == "narrator" -> "旁白"
        recognized && characterName.isNotBlank() -> characterName
        else -> "未识别"
    }

    /** 分镜文本片段：按字符区间安全裁剪（越界夹紧），换行变空格，最长 60 字 */
    fun storyboardSnippet(paragraph: String, start: Int, end: Int, maxChars: Int = 60): String {
        val safeStart = start.coerceIn(0, paragraph.length)
        val safeEnd = end.coerceIn(safeStart, paragraph.length)
        return paragraph.substring(safeStart, safeEnd).trim().replace('\n', ' ').take(maxChars)
    }

    /** 音色包导出边界说明（T6.5，纯函数可单测）：内置只读、不随包分发 */
    fun voiceExportHint(): String = "音色包仅含你自建/导入的音色；内置 6 个只读，不随包分发"

    /** 管理页副标题（纯函数可单测）：一眼说明这套引擎的定位 */
    fun engineSubtitle(): String = "离线 · 断网可用 · 多角色逐段换声"
}
