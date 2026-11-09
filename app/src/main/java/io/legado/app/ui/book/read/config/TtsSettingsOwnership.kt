package io.legado.app.ui.book.read.config

/**
 * 设置项唯一归属（AD-11）：6 个分组 = 用户唯一能改听书配置的地方。
 *
 * 只做"入口目录"，不含具体设置项渲染；每个 id 与旧「高级」chip / `PageTab.key` 一一对应，
 * 便于 `TtsSettingsOwnershipTest` 断言"无重复归属、无悬空 id"。
 */
data class TtsSettingsGroup(
    val id: String,
    val label: String,
    val summary: String
)

object TtsSettingsOwnership {

    const val ID_VOICE = "voices"
    const val ID_ROLES = "roles"
    const val ID_SEGMENT = "segment"
    const val ID_BEHAVIOR = "behavior"
    const val ID_AI = "ai"
    const val ID_DIAGNOSTICS = "diagnostics"

    /** 全部合法 id（与 `foldGroupsOf` 输出一致，测试用同一常量防漂移） */
    val allIds: Set<String> = setOf(
        ID_VOICE, ID_ROLES, ID_SEGMENT, ID_BEHAVIOR, ID_AI, ID_DIAGNOSTICS
    )

    /**
     * 按可用性收敛：本地 AI 在低端机或未启用时不出现；
     * 角色/分段依赖书，无书时点进去只能看空态 ⇒ 不显示。
     */
    fun groups(hasBook: Boolean, lowEndDevice: Boolean, localAiVisible: Boolean): List<TtsSettingsGroup> =
        buildList {
            add(TtsSettingsGroup(ID_VOICE, "声音", "选音质档、换音色、看占用与清理"))
            if (hasBook) {
                add(TtsSettingsGroup(ID_ROLES, "角色音色", "谁的声音不对就换谁"))
                add(TtsSettingsGroup(ID_SEGMENT, "分段与提前生成", "看这句归谁，提前生成减少等待"))
            }
            add(TtsSettingsGroup(ID_BEHAVIOR, "朗读行为", "语速、定时、收听方式、配乐"))
            if (localAiVisible) {
                add(TtsSettingsGroup(ID_AI, "本地 AI", "剧本生成模型（可选）"))
            }
            add(TtsSettingsGroup(ID_DIAGNOSTICS, "诊断", "不出声或很慢时先看这里"))
        }

    fun isValid(id: String): Boolean = id in allIds
}
