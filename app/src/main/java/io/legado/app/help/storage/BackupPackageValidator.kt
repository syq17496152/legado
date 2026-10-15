package io.legado.app.help.storage

import com.google.gson.JsonParser
import java.io.File

/**
 * 备份包完整性校验（恢复**前置**门禁）。
 *
 * 背景：`Restore` 落库阶段对每个条目是 `fileToListT(...)?.let { ... }` —— 文件损坏时该条目
 * **静默跳过**，而 ZIP 解压本身是成功的 ⇒ 「恢复成功」回执照常弹出，用户设备上却只恢复了
 * 一部分（且无从判断少了什么）。本校验落在**落库之前**：发现问题即整体中止并给出可读回执。
 *
 * 判定口径（**刻意宽容，只拦"确定坏了"的包**）：
 *  ① **缺项不算错**：备份选择器（`BackupSelectorConfig.allItems`）允许用户只勾部分类别
 *     （例如「只备份书源」）⇒ 包内必然缺其他文件，**不能因此拒绝恢复**；
 *  ② 但包里若**一条已知条目都没有** ⇒ 判为空包/坏包（恢复等于什么都没做）；
 *  ③ 已知条目**存在**时：若内容看起来是明文 JSON（以 `[` / `{` 开头）却**解析失败** ⇒ 判损坏
 *     （截断 / 半写 / 非法字符）；
 *  ④ 内容不以 `[` / `{` 开头者**不参与解析判定** —— 备份里存在**加密**条目（如 `servers.json`）
 *     与二进制/配置 XML 混放，强判会把合法包误杀。
 *
 * ⚠️ 本对象保持**纯 JVM**（只碰 `File` + Gson），便于单测；已知条目清单由调用方传入
 * （`Restore` 从 `BackupSelectorConfig.allItems` 派生，单源防漂移）。
 */
object BackupPackageValidator {

    /**
     * 校验结果。
     *
     * @param present 包内**实际存在**的已知条目（仅文件名）
     * @param malformed 存在的已知条目中，疑似明文 JSON 但解析失败的项（仅文件名）
     */
    data class Report(val present: List<String>, val malformed: List<String>) {

        val ok: Boolean get() = present.isNotEmpty() && malformed.isEmpty()

        /** 面向用户的简短说明（不含绝对路径，避免回执里出现设备目录） */
        fun describe(): String {
            val parts = ArrayList<String>(2)
            if (present.isEmpty()) parts.add("不含任何已知数据文件")
            if (malformed.isNotEmpty()) parts.add("损坏 ${malformed.joinToString("/")}")
            return parts.joinToString("，")
        }
    }

    /**
     * 校验 [root]（已解压的备份工作目录）是否可用于恢复。
     *
     * @param knownFiles 已知条目清单（由调用方派生，见类注释）
     */
    fun validate(root: File, knownFiles: List<String>): Report {
        val present = ArrayList<String>(knownFiles.size)
        val malformed = ArrayList<String>(knownFiles.size)
        knownFiles.forEach { name ->
            val file = File(root, name)
            // ⚠️ 缺项合法：选择性备份（只勾部分类别）本就不会写出其余文件
            if (!file.exists()) return@forEach
            present.add(name)
            val text = runCatching { file.readText() }.getOrNull()
            if (text == null || (looksLikePlainJson(text) && !isParsable(text))) {
                malformed.add(name)
            }
        }
        return Report(present, malformed)
    }

    /** 疑似**明文** JSON（密文/二进制/XML 不会以 `[`/`{` 开头 ⇒ 不参与解析判定） */
    private fun looksLikePlainJson(text: String): Boolean {
        val head = text.trimStart().firstOrNull() ?: return false
        return head == '[' || head == '{'
    }

    private fun isParsable(text: String): Boolean =
        runCatching { JsonParser.parseString(text) }.isSuccess
}
