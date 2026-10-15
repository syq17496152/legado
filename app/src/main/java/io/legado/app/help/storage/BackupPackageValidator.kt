package io.legado.app.help.storage

import com.google.gson.JsonParser
import java.io.File

/**
 * 备份包完整性校验（恢复**前置**门禁）。
 *
 * 背景：`Restore` 落库阶段对每个条目是 `fileToListT(...)?.let { ... }` —— 文件缺失或内容畸形时
 * 该条目**静默跳过**，而 ZIP 解压本身是成功的 ⇒ 「恢复成功」回执照常弹出，用户设备上却是
 * **半恢复**（书架等主数据缺失、状态自相矛盾，且无从判断到底少了什么）。
 * 本校验落在**落库之前**：不通过即整体中止并给出可读回执，用户可换一个完整包重试。
 *
 * 判定口径（只校验**必需条目**）：
 *  · 必需文件缺失 ⇒ [Report.missing]
 *  · 必需文件存在但内容**不是合法 JSON 数组** ⇒ [Report.malformed]
 *
 * ⚠️ 为什么只列一项：其余条目在旧版备份里可能本就不存在或为旧格式（如旧 `bookSource.json`
 * 由 `ImportOldData` 兜底解析）⇒ 强判会**误伤旧包**，与「旧备份仍可恢复」的既有语义冲突。
 */
object BackupPackageValidator {

    /** 必需条目：备份侧总会写出（见 `Backup.backup`），缺它即包不完整、恢复必然半残。 */
    private val requiredJsonArrays = listOf("bookshelf.json")

    /**
     * 校验结果。
     *
     * @param missing 缺失的必需文件（仅文件名）
     * @param malformed 存在但内容非合法 JSON 数组的必需文件（仅文件名）
     */
    data class Report(val missing: List<String>, val malformed: List<String>) {

        val ok: Boolean get() = missing.isEmpty() && malformed.isEmpty()

        /** 面向用户的简短说明（不含绝对路径，避免回执里出现设备目录） */
        fun describe(): String {
            val parts = ArrayList<String>(2)
            if (missing.isNotEmpty()) parts.add("缺少 ${missing.joinToString("/")}")
            if (malformed.isNotEmpty()) parts.add("损坏 ${malformed.joinToString("/")}")
            return parts.joinToString("，")
        }
    }

    /** 校验 [root]（已解压的备份工作目录）是否可用于恢复。 */
    fun validate(root: File): Report {
        val missing = ArrayList<String>(requiredJsonArrays.size)
        val malformed = ArrayList<String>(requiredJsonArrays.size)
        requiredJsonArrays.forEach { name ->
            val file = File(root, name)
            when {
                !file.exists() -> missing.add(name)
                !isJsonArray(file) -> malformed.add(name)
            }
        }
        return Report(missing, malformed)
    }

    /**
     * 是否可解析为 JSON 数组。
     *
     * 用 Gson **实解析**判型，而不是 `isJsonArray()`（后者只比首尾字符，畸形内容也能蒙过去）。
     */
    private fun isJsonArray(file: File): Boolean =
        runCatching { JsonParser.parseString(file.readText()).isJsonArray }.getOrDefault(false)
}
