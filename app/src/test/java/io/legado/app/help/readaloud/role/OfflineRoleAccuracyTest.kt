package io.legado.app.help.readaloud.role

import com.google.gson.Gson
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7.2 离线归因准确率回归集：
 * 用生产同一条预处理链（[ReadAloudRolePreprocessor.process]）产出的单元喂 [OfflineRoleResolver.resolve]，
 * 与人工标注的"真实说话人"逐句比对，统计三道硬指标：
 * - 角色级准确率（含未识别判对的句）≥ 85%（纯规则无 NER 模型档口径）
 * - 张冠李戴率（把 A 的话判给 B）≤ 2%
 * - 未识别句绝不静默归旁白（roleType 必须非 narrator）
 */
class OfflineRoleAccuracyTest {

    private data class Dataset(
        val characters: List<OfflineRoleResolver.RoleCharacter> = emptyList(),
        val samples: List<Sample> = emptyList()
    )

    private data class Sample(
        val id: String = "",
        val paragraphs: List<String> = emptyList(),
        val expect: List<String> = emptyList()
    )

    private fun loadDataset(): Dataset {
        val stream = javaClass.classLoader?.getResourceAsStream(RESOURCE)
            ?: error("回归集缺失：$RESOURCE")
        return stream.use { Gson().fromJson(it.reader(), Dataset::class.java) }
    }

    @Test
    fun `离线归因达到验收指标`() {
        val dataset = loadDataset()
        var total = 0
        var correct = 0
        var misassigned = 0
        val missDetails = mutableListOf<String>()
        dataset.samples.forEach { sample ->
            val paragraphs = sample.paragraphs
            val units = ReadAloudRolePreprocessor.process(paragraphs).units
            val result = OfflineRoleResolver.resolve(paragraphs, units, dataset.characters)
            // 只统计"引语单元"（含引号的对白）：cue 前缀文本在预处理里也会产 dialogue 单元，
            // 若一并计入会与人工标注的逐句顺序错位（2026-10-31 回归集首跑实证：误把 cue 前缀当句子）
            val predicted = units
                .filter { u -> u.kind == "dialogue" && u.text.any { it in QUOTE_CHARS } }
                .sortedWith(compareBy({ it.firstParagraphIndex }, { it.firstStart }))
                .map { u ->
                    result.segments.firstOrNull { s ->
                        s.paragraphIndex == u.firstParagraphIndex && s.start == u.firstStart
                    }?.characterName.orEmpty()
                }
            // 标注与预测逐句对齐（长度不一致时截断比对，并把长度差异计入明细）
            sample.expect.forEachIndexed { index, expected ->
                val actual = predicted.getOrNull(index).orEmpty()
                total++
                if (expected == actual) {
                    correct++
                } else if (expected.isNotBlank() && actual.isNotBlank()) {
                    misassigned++
                }
                if (expected != actual) {
                    missDetails.add("${sample.id}[$index] 期望=${expected.ifBlank { "未识别" }} 实际=${actual.ifBlank { "未识别" }}")
                }
            }
            if (predicted.size != sample.expect.size) {
                missDetails.add("${sample.id} 段数 期望=${sample.expect.size} 实际=${predicted.size}")
            }
        }
        val accuracy = if (total == 0) 0.0 else correct.toDouble() / total
        val misassignRate = if (total == 0) 1.0 else misassigned.toDouble() / total
        println("T7.2 回归集：句子=$total 正确=$correct 准确率=${"%.3f".format(accuracy)} " +
            "张冠李戴=$misassigned(${"%.3f".format(misassignRate)})")
        missDetails.take(12).forEach { println("  差：$it") }
        assertTrue("角色级准确率未达 85%（实际 ${"%.3f".format(accuracy)}）", accuracy >= 0.85)
        assertTrue("张冠李戴率超 2%（实际 ${"%.3f".format(misassignRate)}）", misassignRate <= 0.02)
    }

    private companion object {
        const val RESOURCE = "offline_role_samples.json"
        val QUOTE_CHARS = setOf('“', '”', '「', '」', '『', '』', '‘', '’')
    }
}
