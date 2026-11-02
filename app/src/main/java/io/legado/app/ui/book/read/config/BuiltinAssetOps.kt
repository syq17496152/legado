package io.legado.app.ui.book.read.config

import io.legado.app.constant.AppLog
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.help.tts.builtin.ClonettsVoiceImporter
import java.io.File

/**
 * 资产导入动作（T2.6/T6.1）：模型目录扫描导入 + 外置目录音色包直导。
 * 从管理页 Activity 抽出，返回"可直接展示"的结果文案；UI 状态仍归 Activity。
 */
internal object BuiltinAssetOps {

    /** 扫描外置导入目录（模型档案/目录），逐项导入后返回结果文案 */
    suspend fun discoverModels(): String {
        val found = BuiltinTtsRuntime.modelManager().discoverImports()
        var imported = 0
        found.forEach { file ->
            val profileId = BuiltinTtsUiSupport.guessProfile(file.name)
            val ok = if (file.isDirectory) {
                BuiltinTtsRuntime.modelManager().importFromDirectory(profileId, file)
            } else {
                runCatching {
                    file.inputStream().use { input ->
                        BuiltinTtsRuntime.modelManager().importArchive(profileId, input, file.name)
                    }
                }.getOrDefault(false)
            }
            if (ok) imported++
        }
        return "发现 ${found.size} 项，导入成功 $imported"
    }

    /** 联调/直达：从外置导入目录按文件名导入音色包（不经过 SAF，便于 adb push 后自动验证） */
    suspend fun importVoiceZip(fileName: String): String {
        val file = File(BuiltinTtsRuntime.paths().importDir, fileName)
        if (!file.isFile) return "导入目录内没有 $fileName"
        val report = runCatching {
            ClonettsVoiceImporter(BuiltinTtsRuntime.voiceStore()).importZip(file)
        }.onFailure {
            AppLog.put("音色包导入失败：${it.message}", it)
        }.getOrNull()
        return report?.message ?: "音色包导入失败"
    }
}
