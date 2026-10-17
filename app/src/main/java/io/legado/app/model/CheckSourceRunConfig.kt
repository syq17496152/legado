package io.legado.app.model

/**
 * 一次「内置批量巡检」的**运行期配置**（程序化启动用：REST `/startCheckSource`）。
 *
 * **为什么需要**：[CheckSource] 的各开关（`checkInfo/checkCategory/checkContent` + `timeout`）是
 * **全局单例持久化值**（UI 配置）。程序化启动若直接改写该单例，会污染用户的 UI 配置，并在并发启动时
 * 产生竞态。本类把「本次任务」的覆盖项经 `IntentData` 一次性下发给
 * [io.legado.app.service.CheckSourceService]；服务侧只落**运行期字段**，任务结束即随进程失效。
 *
 * **语义**：各字段为 `null` ⇒ **沿用 [CheckSource] 持久化现值（行为与旧版完全一致，零变化）**。
 * 非 `null` ⇒ 仅本次任务覆盖；**不写回持久化**（不调用 [CheckSource.putConfig]）。
 *
 * **输出安全**：本类只承载布尔/整型开关，不携带任何源业务数据。
 */
data class CheckSourceRunConfig(
    /** 单源硬超时（毫秒）；≤0 视为未指定 ⇒ 沿用 [CheckSource.timeout]。 */
    val timeoutMs: Long? = null,
    /** 是否校验「详情」维度；null ⇒ 沿用 [CheckSource.checkInfo]。 */
    val checkInfo: Boolean? = null,
    /** 是否校验「目录」维度；null ⇒ 沿用 [CheckSource.checkCategory]。 */
    val checkCategory: Boolean? = null,
    /** 是否校验「正文」维度；null ⇒ 沿用 [CheckSource.checkContent]。 */
    val checkContent: Boolean? = null,
) {

    /** 判断本配置是否为空（全 `null` ⇒ 完全沿用单例现值）。 */
    val isEmpty: Boolean
        get() = timeoutMs == null && checkInfo == null && checkCategory == null && checkContent == null

    companion object {
        /** `IntentData` 键（服务侧 `IntentData.get` 后即移除 ⇒ 一次性下发）。 */
        const val INTENT_KEY = "checkSourceRunConfig"

        /** 支持的下游维度档位（大小写不敏感）。 */
        val DEPTHS: List<String> = listOf("L1", "L2", "L3")

        /**
         * 由 REST 入参解析运行期配置（纯函数，可 JVM 单测）。
         *
         * `depth` **只治理下游三维**（详情/目录/正文），不影响域名/搜索/发现（那三项由既有开关管）：
         * - `L1` ⇒ 详情/目录/正文全关（只跑 域名/搜索/发现，最快）
         * - `L2` ⇒ 仅详情
         * - `L3` ⇒ 详情+目录+正文（全量，最慢）
         *
         * **显式布尔优先于 `depth`**（`checkInfo ?: trio.first`）；各样缺省 `null` ⇒ 沿用 [CheckSource] 现值。
         *
         * @throws IllegalArgumentException `depth` 非空且非法（调用方转结构化 `fail("config", …)`，不启动）
         */
        fun resolve(
            depth: String?,
            timeoutMs: Long?,
            checkInfo: Boolean?,
            checkCategory: Boolean?,
            checkContent: Boolean?,
        ): CheckSourceRunConfig {
            val trio: Triple<Boolean, Boolean, Boolean>? = depth
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { d ->
                    when (d.uppercase()) {
                        "L1" -> Triple(false, false, false)
                        "L2" -> Triple(true, false, false)
                        "L3" -> Triple(true, true, true)
                        else -> throw IllegalArgumentException(
                            "非法depth「$depth」：仅支持 ${DEPTHS.joinToString("/")}"
                        )
                    }
                }
            return CheckSourceRunConfig(
                timeoutMs = timeoutMs?.takeIf { it > 0 },
                checkInfo = checkInfo ?: trio?.first,
                checkCategory = checkCategory ?: trio?.second,
                checkContent = checkContent ?: trio?.third,
            )
        }
    }
}
