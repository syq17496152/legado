package io.legado.app.ui.book.read.config

import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.tts.builtin.BuiltinEngineState
import io.legado.app.help.tts.builtin.BuiltinVoice
import io.legado.app.help.tts.builtin.VoiceAutoTagger
import java.util.Locale

/** 管理页纯逻辑（可单测）：导入档案推断 + 体积格式化 + 页签数量 */
internal object BuiltinTtsUiSupport {

    /**
     * 从导入文件名/目录名推断目标档案（旁路发现路径用）。
     * 顺序敏感：`aishell3` 包名里也含 `vits`（icefall VITS），必须先判 aishell3，否则会被导成 fast。
     */
    fun guessProfile(name: String): String = when {
        name.contains("kokoro", true) -> "hq"
        name.contains("aishell3", true) -> "multi"
        name.equals("hq", true) || name.equals("multi", true) -> name.lowercase(Locale.US)
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

    /**
     * 一键开书是否需要强制重算分镜（IF-IM-08 自愈）。
     * 本次补录到了新角色（旧缓存可能仍 0 命中）或旧缓存本来就 0 命中 ⇒ 必须重算；
     * 否则沿用缓存，避免每次开书都做一次全章归因。
     */
    fun shouldForceRecomputeStoryboard(discoveredCharacters: Int, cachedRecognized: Int): Boolean =
        discoveredCharacters > 0 || cachedRecognized <= 0

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

    /** 打标结果 → 文案（UI 只做接线，池数口径来自打标自身的池同步结果，避免调用方再算一遍） */
    fun autoTagMessage(result: VoiceAutoTagger.Result): String =
        autoTagMessage(result.processed, result.male, result.female, result.unknown, result.pooled)

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

    /**
     * 档位标题兜底（纯函数可单测）：优先用模型档案里的 title；
     * 档案缺失时才按 id 映射，四档叫法必须各不相同（多音色两档曾被统一显示成「高质量克隆档」）。
     */
    fun profileTitle(profileId: String): String = when (profileId) {
        "fast" -> "极速单声档"
        "multi" -> "多音色快速档"
        "hq" -> "多音色高质量档"
        else -> "高质量克隆档"
    }

    /** 档位状态短标签（纯函数可单测）：给状态胶囊用，不堆 Hz/体积等技术细节 */
    fun profileStateText(state: BuiltinEngineState): String = when (state) {
        BuiltinEngineState.READY -> "已就绪"
        BuiltinEngineState.DOWNLOADING -> "下载中"
        BuiltinEngineState.CORRUPT -> "已损坏"
        BuiltinEngineState.LOW_MEM -> "内存不足"
        BuiltinEngineState.NONE -> "未下载"
    }

    /** 档位副文案（纯函数可单测）：采样率 + 当前是否走这一档 */
    fun profileSubtitle(sampleRate: Int, active: Boolean): String {
        val rate = if (sampleRate > 0) "$sampleRate Hz · " else ""
        return rate + if (active) "朗读走这一档" else "可切换为当前档"
    }

    /** 首页就绪胶囊（纯函数可单测）：语音包齐备与否一眼可见 */
    fun readyPill(ready: Boolean): String = if (ready) "离线就绪" else "语音包未就绪"

    /**
     * 切档结果提示（纯函数可单测）。
     *
     * 切档时若正在朗读，必须显式告知「已停止」：旧档音色名对新档引擎无效（真机铁证 2026-11-09），
     * 静默切换会让用户听到「没变化 / 换了个人」却不知原因。
     */
    fun profileSwitchNotice(stoppedReading: Boolean): String =
        if (stoppedReading) "已切换档位，朗读已停止（请重新开始以使用新档音色）" else "已切换档位"

    /** NER 模型状态胶囊（AD-16，纯函数可单测）：就绪/未安装 */
    fun nerModelStatusText(ready: Boolean): String = if (ready) "已就绪" else "未安装"

    /** 音色头像首字（纯函数可单测）：空白名回落 "?"，避免空圆 */
    fun voiceInitial(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) "?" else trimmed.substring(0, 1)
    }

    /**
     * 音色显示名（纯函数可单测）：去掉"内置·/自录·/导入·"来源前缀。
     * 来源已在副文案徽章里说过一次，标题再重复会让 6 个内置音色看起来一样（视觉重做第三轮）。
     */
    fun voiceTitle(name: String): String {
        val trimmed = name.trim()
        val prefixes = listOf("内置·", "自录·", "导入·")
        val hit = prefixes.firstOrNull { trimmed.startsWith(it) }
        val stripped = if (hit != null) trimmed.removePrefix(hit).trim() else trimmed
        return stripped.ifEmpty { trimmed }
    }

    /** RTF 展示（纯函数可单测）：未测过显示破折号，不显示 0.00 误导 */
    fun rtfText(rtf: Float): String =
        if (rtf <= 0f) "—" else String.format(Locale.US, "%.2f", rtf)

    /** 导出本章音频的可用性（T8.5，纯函数可单测）：没开书或没分镜就没东西可导 */
    fun canExportChapter(hasBook: Boolean, storyboardSegments: Int): Boolean =
        hasBook && storyboardSegments > 0

    /** 缓存水位文案（T3.4，纯函数可单测）：用量/上限/百分比；超限提示会按 LRU 清理 */
    fun cacheWatermarkText(used: Long, limit: Long): String {
        if (limit <= 0) return "缓存 ${formatBytes(used)}"
        val percent = ((used * 100) / limit).coerceIn(0, 999)
        val base = "缓存 ${formatBytes(used)} / ${formatBytes(limit)}（$percent%）"
        return if (used > limit) "$base · 已超限，按最近最少使用清理" else base
    }

    /**
     * 分镜段试听音色（T5.5，纯函数可单测）：角色名命中配音行 ⇒ 用该角色音色；
     * 未命中（旁白/未识别/该角色未配声）⇒ 回落第一个可用音色（保证"点了就有声"）。
     */
    fun segmentVoiceName(
        characterName: String,
        roleRows: List<CharacterVoiceAssignService.RoleVoiceRow>,
        fallbackVoiceName: String
    ): String {
        if (characterName.isBlank()) return fallbackVoiceName
        val matched = roleRows.firstOrNull { it.name == characterName }?.voiceName.orEmpty()
        return matched.ifBlank { fallbackVoiceName }
    }

    /** 音色行副文案（T6.4，纯函数可单测）：来源徽章 + 池 + 性别/年龄（中文，名称已含则不重复）+ 逐字稿长度 */
    fun voiceMetaText(voice: BuiltinVoice): String {
        val badge = when (voice.origin) {
            BuiltinVoice.ORIGIN_PRESET -> "内置"
            BuiltinVoice.ORIGIN_SELF -> "自录"
            else -> "导入"
        }
        val genderLabel = when (voice.gender) {
            "male" -> "男"
            "female" -> "女"
            else -> ""
        }
        val ageLabel = when (voice.age) {
            "teen" -> "少年"
            "youth" -> "青年"
            "aged" -> "老年"
            else -> ""
        }
        return buildList {
            add(badge)
            if (voice.groupId.isNotBlank()) add("池:${voice.groupId}")
            // 名称已写"男/女"或"少年/青年/老年"时不再重复（避免"男 · 男"这类冗余）
            if (genderLabel.isNotBlank() && !voice.displayName.contains(genderLabel)) add(genderLabel)
            if (ageLabel.isNotBlank() && !voice.displayName.contains(ageLabel)) add(ageLabel)
            // 可读性优先：「稿57字」这种压缩写法在列表里容易被读成乱码，改成完整词
            if (voice.refText.isNotEmpty()) add("逐字稿 ${voice.refText.length} 字")
        }.joinToString(" · ")
    }
}
