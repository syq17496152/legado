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
 *  ② 包里若**一条已知条目都没有** ⇒ 判为空包/坏包（恢复等于什么都没做）。
 *     ⚠️「已知条目」含**全部类别** —— `.json` 数据、目录（`bg` / `book_cache` / 封面图集）、
 *     XML 配置（`config.xml` / `videoConfig.xml`）**都计入存在性**；只统计 `.json` 会让
 *     「仅勾非 JSON 类目」的合法包被误判成空包（本轮已实测该边界）。
 *  ③ **仅对 `.json` 文件**做内容判定：内容看起来是明文 JSON（以 `[` / `{` 开头）却**解析失败**
 *     ⇒ 判损坏（截断 / 半写 / 非法字符）；
 *  ④ 内容不以 `[` / `{` 开头者（备份里有**加密**条目如 `servers.json`）**不参与解析判定**；
 *     目录条目亦不解析（`readText` 对目录会抛，若强判会误杀）。
 *
 * ⚠️ 本对象保持**纯 JVM**（只碰 `File` + Gson），便于单测；已知条目清单由调用方传入
 * （`Restore` 从 `BackupSelectorConfig.allItems` 派生，单源防漂移）。
 */
object BackupPackageValidator {

    /** 参与"内容解析判定"的扩展名；其余类型（目录 / XML）只计入存在性 */
    private const val JSON_SUFFIX = ".json"

    /**
     * 校验结果。
     *
     * @param present 包内**实际存在**的已知条目（仅文件名，含目录/XML）
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
     * @param knownEntries 已知条目清单（含目录与 XML；由调用方从备份选择器配置派生）
     */
    fun validate(root: File, knownEntries: List<String>): Report {
        val present = ArrayList<String>(knownEntries.size)
        val malformed = ArrayList<String>(knownEntries.size)
        knownEntries.forEach { name ->
            val file = File(root, name)
            // ⚠️ 缺项合法：选择性备份本就不会写出其余条目
            if (!file.exists()) return@forEach
            present.add(name)
            // 目录 / XML 配置只计存在性，不做 JSON 内容判定
            if (!name.endsWith(JSON_SUFFIX) || !file.isFile) return@forEach
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
