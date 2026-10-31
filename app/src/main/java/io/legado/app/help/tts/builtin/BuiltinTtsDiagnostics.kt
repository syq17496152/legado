package io.legado.app.help.tts.builtin

/**
 * 失败降级矩阵（T3.5）：把"为什么现在读不出声/会很慢"变成可读结论 + 可操作动作。
 *
 * 设计口径（design.md §8）：每条都是**检测点 → 文案 → 动作**三件套，页面直接渲染；
 * 纯函数便于单测（不依赖 Android），调用方只负责采集输入。
 */
object BuiltinTtsDiagnostics {

    enum class Level { ERROR, WARN, INFO }

    data class Finding(
        val level: Level,
        val code: String,
        val message: String,
        val action: String = ""
    )

    data class Input(
        val state: BuiltinEngineState,
        val abiSupported: Boolean = true,
        val memoryMb: Long = Long.MAX_VALUE,
        val lastRtf: Float = 0f,
        val lastError: String = "",
        val requiresReference: Boolean = false,
        /** 用户显式选的档位（id/标题）；与当前生效档不同即视为"已回落" */
        val preferredId: String = "",
        val preferredTitle: String = "",
        val activeId: String = "",
        val activeTitle: String = ""
    )

    const val LOW_MEMORY_MB = 400L
    const val RTF_WARN = 1.2f

    fun evaluate(input: Input): List<Finding> {
        val findings = mutableListOf<Finding>()
        if (!input.abiSupported) {
            findings.add(
                Finding(
                    Level.ERROR,
                    "abi",
                    "当前设备 ABI 不在支持列表（需要 arm64-v8a / armeabi-v7a / x86_64）",
                    "换用支持的设备，或改用系统 TTS 引擎"
                )
            )
        }
        when (input.state) {
            BuiltinEngineState.NONE -> findings.add(
                Finding(Level.ERROR, "model_none", "未下载离线语音包", "在引擎页下载或导入语音包")
            )
            BuiltinEngineState.CORRUPT -> findings.add(
                Finding(Level.ERROR, "model_corrupt", "语音包损坏或文件不完整", "删除后重新下载/导入")
            )
            BuiltinEngineState.LOW_MEM -> findings.add(
                Finding(Level.ERROR, "low_mem", "内存不足，无法加载当前档位", "改用极速单声档，或关闭其他应用")
            )
            else -> Unit
        }
        if (input.requiresReference && input.memoryMb < LOW_MEMORY_MB) {
            findings.add(
                Finding(
                    Level.WARN,
                    "memory_tight",
                    "可用内存 ${input.memoryMb}MB，克隆档加载约需 400MB 以上",
                    "改用极速单声档，或清理后台应用"
                )
            )
        }
        if (input.lastRtf > RTF_WARN) {
            findings.add(
                Finding(
                    Level.WARN,
                    "rtf_high",
                    "合成速度偏慢（RTF ${"%.2f".format(input.lastRtf)}，>1.0 表示慢于播放）",
                    "改用极速档，或开启预合成后连播"
                )
            )
        }
        if (input.lastError.isNotBlank()) {
            findings.add(Finding(Level.WARN, "last_error", "最近错误：${input.lastError}"))
        }
        // 首选档不可用但仍有可用档 ⇒ 已静默回落，必须让用户看见（否则"音质突然变差"无法解释）
        if (input.preferredId.isNotBlank() && input.preferredId != input.activeId) {
            val preferred = input.preferredTitle.ifBlank { input.preferredId }
            val fallback = input.activeTitle.ifBlank { input.activeId }.ifBlank { "无" }
            findings.add(
                Finding(
                    Level.WARN,
                    "fallback",
                    "首选档「$preferred」不可用，已回落到「$fallback」",
                    "修复或重新导入首选档后点「设为当前档」"
                )
            )
        }
        if (findings.isEmpty()) {
            findings.add(Finding(Level.INFO, "ok", "运行正常"))
        }
        return findings
    }

    /** 页面展示文案（纯函数可单测）：`[错误] 文案 → 动作` */
    fun render(findings: List<Finding>): String = findings.joinToString("\n") { finding ->
        val prefix = when (finding.level) {
            Level.ERROR -> "[错误] "
            Level.WARN -> "[警告] "
            Level.INFO -> "[正常] "
        }
        prefix + finding.message + if (finding.action.isNotBlank()) " → ${finding.action}" else ""
    }
}
