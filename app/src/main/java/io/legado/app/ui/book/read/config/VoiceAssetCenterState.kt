package io.legado.app.ui.book.read.config

/**
 * 声音资源中心状态（offline-tts-llm-productization 4.9 的最小可交付切片）。
 *
 * 它只做一件事：**把「哪些资产是公共的、可删可重下；哪些是用户私有、删了就找不回」摆在用户面前**。
 * 这是本设计的核心产品承诺 —— 用户必须能一眼看出自己的音色/模板/配乐属于哪一类，
 * 而不是在一堆开关里猜。因此每个分组都强制带 [AssetSource] 标签，不留默认值。
 */
enum class AssetSource(val label: String) {
    /** 随安装包内置，不占用户空间、不可删 */
    BUILTIN("内置"),

    /** 公共资源：可随时删除并重新下载（不进备份） */
    DOWNLOAD("可重下"),

    /** 用户私有：只能导入/导出，删了无法重建（进备份） */
    IMPORT("需备份")
}

enum class AssetStatus { READY, MISSING, PARTIAL }

enum class AssetAction {
    MANAGE_ENGINE,
    MANAGE_VOICE,
    PREVIEW_VOICES,
    IMPORT_NER,
    MANAGE_TEMPLATE,
    MANAGE_BGM,
    MANAGE_TEXT,
    CLEAR_CACHE,
    CLEAR_AI_DATA
}

data class AssetGroup(
    val id: String,
    val title: String,
    val summary: String,
    val source: AssetSource,
    val status: AssetStatus,
    val action: AssetAction,
    val actionLabel: String,
    /** 次要动作（如音色分组「连播试听」）；null = 不显示第二个按钮 */
    val secondaryAction: AssetAction? = null,
    val secondaryLabel: String = ""
)

data class AssetFacts(
    val engineReady: Boolean = false,
    val profileTitle: String = "",
    val voiceCount: Int = 0,
    val importedVoiceCount: Int = 0,
    val nerReady: Boolean = false,
    val templateCount: Int = 0,
    val bgmCount: Int = 0,
    val dictReady: Boolean = false,
    val splitRulesReady: Boolean = false,
    val modelBytes: Long = 0L,
    val voiceBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    /** 章节剧本（草案）占用：可重建缓存，清除不影响音色与角色绑定 */
    val scriptBytes: Long = 0L,
    /** 本地 AI 模型占用（有则计；无则为 0） */
    val aiModelBytes: Long = 0L,
    /** 音色画像覆盖率（5.1）：性别/年龄有标注的比例，决定配声"性别硬约束"能起多大作用 */
    val voiceGenderPercent: Int = 0,
    val voiceAgePercent: Int = 0
) {
    val totalBytes: Long get() = modelBytes + voiceBytes + cacheBytes
}

data class VoiceAssetCenterUi(
    val headerLine: String,
    val groups: List<AssetGroup>,
    val canClearCache: Boolean,
    val canClearAiData: Boolean = false
)

object VoiceAssetCenterState {

    fun build(facts: AssetFacts): VoiceAssetCenterUi {
        val groups = listOf(
            AssetGroup(
                id = "engine",
                title = "离线语音",
                summary = if (facts.engineReady) {
                    "当前档位：${facts.profileTitle.ifBlank { "未命名" }}"
                } else {
                    "未就绪：可下载或从本地导入语音包"
                },
                source = AssetSource.DOWNLOAD,
                status = if (facts.engineReady) AssetStatus.READY else AssetStatus.MISSING,
                action = AssetAction.MANAGE_ENGINE,
                actionLabel = if (facts.engineReady) "去管理" else "去启用"
            ),
            AssetGroup(
                id = "voice",
                title = "音色库",
                summary = if (facts.voiceCount > 0) {
                    "共 ${facts.voiceCount} 个（导入 ${facts.importedVoiceCount} 个）" +
                            " · 性别标注 ${facts.voiceGenderPercent}% · 年龄标注 ${facts.voiceAgePercent}%"
                } else {
                    "还没有可用音色：可导入音色包或录制"
                },
                source = AssetSource.IMPORT,
                status = if (facts.voiceCount > 0) AssetStatus.READY else AssetStatus.MISSING,
                action = AssetAction.MANAGE_VOICE,
                actionLabel = if (facts.voiceCount > 0) "去管理" else "去导入",
                // 4.9：分组连播试听 —— 不用逐个点进列表，先在资源页快速扫听前几个
                secondaryAction = AssetAction.PREVIEW_VOICES.takeIf { facts.voiceCount > 0 },
                secondaryLabel = "连播试听"
            ),
            AssetGroup(
                id = "ner",
                title = "朗读理解（人名识别）",
                summary = if (facts.nerReady) "NER 模型已就绪" else "未安装：导入 NER 模型可减少人名误判",
                source = AssetSource.DOWNLOAD,
                status = if (facts.nerReady) AssetStatus.READY else AssetStatus.MISSING,
                action = AssetAction.IMPORT_NER,
                actionLabel = if (facts.nerReady) "去管理" else "去导入"
            ),
            AssetGroup(
                id = "template",
                title = "选角模板",
                summary = if (facts.templateCount > 0) {
                    "共 ${facts.templateCount} 套（含内置模板）"
                } else {
                    "还没有模板：进入后会自动补齐内置模板"
                },
                source = AssetSource.IMPORT,
                status = if (facts.templateCount > 0) AssetStatus.READY else AssetStatus.MISSING,
                action = AssetAction.MANAGE_TEMPLATE,
                actionLabel = "去管理"
            ),
            AssetGroup(
                id = "bgm",
                title = "配乐",
                summary = if (facts.bgmCount > 0) "已启用 ${facts.bgmCount} 首" else "还没有已启用的配乐",
                source = AssetSource.IMPORT,
                status = if (facts.bgmCount > 0) AssetStatus.READY else AssetStatus.MISSING,
                action = AssetAction.MANAGE_BGM,
                actionLabel = "去管理"
            ),
            AssetGroup(
                id = "text",
                title = "文本资产（发音词典 / 切分规则）",
                summary = textSummary(facts),
                source = AssetSource.IMPORT,
                // 文本资产是可选项：两项都配齐才算 READY，配一半或全默认都算「部分可用」
                status = if (facts.dictReady && facts.splitRulesReady) {
                    AssetStatus.READY
                } else {
                    AssetStatus.PARTIAL
                },
                action = AssetAction.MANAGE_TEXT,
                actionLabel = "去管理"
            ),
            AssetGroup(
                id = "cache",
                title = "合成缓存",
                summary = if (facts.cacheBytes > 0) {
                    "占用 ${mb(facts.cacheBytes)}MB，清理后不影响已导入的音色与模板"
                } else {
                    "暂无缓存"
                },
                source = AssetSource.DOWNLOAD,
                status = if (facts.cacheBytes > 0) AssetStatus.READY else AssetStatus.MISSING,
                action = AssetAction.CLEAR_CACHE,
                actionLabel = if (facts.cacheBytes > 0) "清理" else "已干净"
            ),
            AssetGroup(
                id = "ai",
                title = "AI 剧本与本地模型",
                summary = if (facts.scriptBytes + facts.aiModelBytes > 0) {
                    "剧本草案 ${mb(facts.scriptBytes)}MB / 本地模型 ${mb(facts.aiModelBytes)}MB；" +
                            "清除后保留音色与角色绑定"
                } else {
                    "暂无剧本草案与本地模型"
                },
                source = AssetSource.DOWNLOAD,
                status = if (facts.scriptBytes + facts.aiModelBytes > 0) {
                    AssetStatus.READY
                } else {
                    AssetStatus.MISSING
                },
                action = AssetAction.CLEAR_AI_DATA,
                actionLabel = if (facts.scriptBytes + facts.aiModelBytes > 0) "清除" else "已干净"
            )
        )
        return VoiceAssetCenterUi(
            headerLine = "离线资产共 ${mb(facts.totalBytes)}MB（模型 ${mb(facts.modelBytes)}MB / " +
                    "音色 ${mb(facts.voiceBytes)}MB / 缓存 ${mb(facts.cacheBytes)}MB）",
            groups = groups,
            canClearCache = facts.cacheBytes > 0,
            canClearAiData = facts.scriptBytes + facts.aiModelBytes > 0
        )
    }

    /** 删除前影响提示（清合成缓存）：必须说明"删什么、不影响什么、下次会怎样" */
    fun clearCacheImpactText(cacheBytes: Long): String =
        "将删除约 ${mb(cacheBytes)}MB 已合成音频。\n" +
                "• 只影响缓存：下次朗读会重新合成，首段可能稍慢\n" +
                "• 音色、选角模板、配乐、章节剧本都不会被删除"

    /** 删除前影响提示（清 AI 数据）：保留音色与角色绑定（用户私有数据） */
    fun clearAiImpactText(scriptBytes: Long, aiModelBytes: Long): String =
        "将删除约 ${mb(scriptBytes + aiModelBytes)}MB 的 AI 数据（章节剧本草案 + 本地模型）。\n" +
                "• 已生成剧本会回到「未生成」，朗读自动回落到规则结果\n" +
                "• 音色、角色与音色的绑定、选角模板、配乐都会保留\n" +
                "• 本地模型删除后需重新导入/下载才能再用本地 AI"

    private fun textSummary(facts: AssetFacts): String = when {
        facts.dictReady && facts.splitRulesReady -> "发音词典与切分规则都已配置"
        facts.dictReady -> "已配置发音词典；切分规则用默认"
        facts.splitRulesReady -> "已配置切分规则；发音词典用默认"
        else -> "均用默认（可选自定义）"
    }

    /** 整数 MB（与听书中心资源行同一口径，避免两个页面显示不同数字） */
    fun mb(bytes: Long): Long = bytes / (1024L * 1024L)
}
