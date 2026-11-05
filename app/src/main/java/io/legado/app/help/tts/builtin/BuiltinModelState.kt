package io.legado.app.help.tts.builtin

import java.io.File

/**
 * 模型目录状态机（T1.2）：只依赖文件系统，可纯 JVM 单测（不碰 Context / 下载）。
 *
 * 判定口径：
 * - 目录不存在 → `NONE`
 * - 目录存在但为空 → `NONE`（用户刚建目录/删到一半）
 * - 目录非空但必需文件缺失/为空 → `CORRUPT`（明确失败，不静默当可用）
 * - 必需文件齐且非空 → `READY`（sha256 由导入路径单独校验，避免每次启动全量重算）
 * - 存在 [IMPORT_MARKER] → `NONE`（导入进行中/被中断：残片再"非空"也绝不可当就绪，
 *   否则会把 5MB 的半截 onnx 交给 native，实测直接 SIGSEGV，2026-11-05 模拟器铁证）
 */
object BuiltinModelState {

    /** 导入中标记（解压期间存在；成功校验后由导入方删除） */
    const val IMPORT_MARKER = ".importing"

    fun evaluate(dir: File, profile: BuiltinModelProfile): BuiltinEngineState {
        if (!dir.isDirectory) return BuiltinEngineState.NONE
        if (File(dir, IMPORT_MARKER).exists()) return BuiltinEngineState.NONE
        if (dir.listFiles().orEmpty().none { it.name != IMPORT_MARKER }) return BuiltinEngineState.NONE
        return if (missingFiles(dir, profile).isEmpty()) {
            BuiltinEngineState.READY
        } else {
            BuiltinEngineState.CORRUPT
        }
    }

    fun missingFiles(dir: File, profile: BuiltinModelProfile): List<String> {
        return profile.files.filter { name ->
            val file = findRequired(dir, name)
            file == null || !file.isFile || file.length() <= 0
        }
    }

    fun findRequired(dir: File, name: String): File? {
        if (!dir.isDirectory) return null
        val direct = File(dir, name)
        if (direct.exists()) return direct
        return dir.walkTopDown().firstOrNull { it.name.equals(name, true) }
    }
}
