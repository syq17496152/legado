package io.legado.app.help.llm

/**
 * **仅 DEBUG 构建**生效的本地 AI 调试开关（通过 Intent extra 注入）。
 *
 * 为什么需要它：`LocalLlmFeature` 按设计在 RAM < 8GB 的设备上隐藏本地 AI 入口并禁用开关
 * （正确的产品行为），但这让 6GB 模拟器 / 低内存真机**无法**完成端侧链路验证
 * （tasks 7.2 模型链路、7.6 崩溃隔离）。外部直接改 `shared_prefs` 里的 XML 会被平台回写
 * （issues-found E15），因此提供**进程内**的唯一入口。
 *
 * 安全边界：调用方必须传 `BuildConfig.BUILD_DEBUG`；为 false 时本函数恒返回 null
 * ⇒ release 包不存在线上后门。
 */
object DebugLlmOverride {

    /** `--ez debugEnableLocalAi true` */
    const val EXTRA_ENABLE = "debugEnableLocalAi"

    /** `--es debugModelId <模型目录名>`（与 `LlmModelManager.scan().id` 同源） */
    const val EXTRA_MODEL_ID = "debugModelId"

    data class Request(val modelId: String)

    /**
     * 解析调试请求。
     *
     * @param debugBuild 生产必须传 `BuildConfig.BUILD_DEBUG`
     * @param wantEnable Intent 的 [EXTRA_ENABLE]
     * @param rawModelId Intent 的 [EXTRA_MODEL_ID]
     * @return 需要执行的动作；`null` = 不做任何事（非 debug / 未请求 / 模型 id 为空）
     */
    fun request(debugBuild: Boolean, wantEnable: Boolean, rawModelId: String?): Request? {
        if (!debugBuild || !wantEnable) return null
        val id = rawModelId?.trim().orEmpty()
        if (id.isEmpty()) return null
        return Request(id)
    }
}
