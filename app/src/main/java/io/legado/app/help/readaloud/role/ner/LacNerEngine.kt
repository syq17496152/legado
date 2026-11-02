package io.legado.app.help.readaloud.role.ner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock

/**
 * LAC-ONNX 端侧 NER 引擎（builtin-offline-tts AD-16）。
 *
 * 输入中文段落 → 模型输出逐字发射分数 → CRF Viterbi → BIO 词元（分词/词性/NER）。
 * 只提供"人名 + 区间"信号，说话人归属由 OfflineRoleResolver 融合判定。
 *
 * 模型文件（下载/导入，不进 APK；目录见 [NerModelManager]）：
 * `lac_encoder.onnx`、`lac_crf_transitions.txt`、`word.dic`、`tag.dic`、`q2b.dic`
 */
class LacNerEngine(private val modelDir: File) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val lock = ReentrantLock()

    private val vocab = HashMap<Char, Long>()
    private val q2b = HashMap<Char, Char>()
    private val idToTag = HashMap<Int, String>()
    private val transitions: Array<FloatArray>
    private var oovId = 0L

    init {
        require(modelDir.isDirectory) { "NER 模型目录不存在：$modelDir" }
        loadWordDic(File(modelDir, "word.dic"))
        loadQ2b(File(modelDir, "q2b.dic"))
        loadTagDic(File(modelDir, "tag.dic"))
        transitions = loadTransitions(File(modelDir, "lac_crf_transitions.txt"))
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(
            File(modelDir, "lac_encoder.onnx").absolutePath,
            options
        )
    }

    /** 分析整段文本，返回词元列表（字符区间为原文绝对下标） */
    fun analyze(text: String): List<LacDecoder.Token> {
        if (text.isEmpty()) return emptyList()
        val tokens = ArrayList<LacDecoder.Token>()
        var offset = 0
        splitChunks(text).forEach { chunk ->
            tokens.addAll(runChunk(chunk, offset))
            offset += chunk.length
        }
        return tokens
    }

    /** 人名实体（PER）——模型可能把紧邻动作字并进名字，由 [LacNerPostProcessor] 剥尾 */
    fun personMentions(text: String): List<LacNerPostProcessor.NameSpan> =
        LacNerPostProcessor.personMentions(analyze(text))

    /** cue 候选（名词性词元 + 说话动词 + 冒号/引号） */
    fun cueCandidates(text: String): List<LacNerPostProcessor.NameSpan> =
        LacNerPostProcessor.cueCandidates(analyze(text))

    private fun runChunk(text: String, baseOffset: Int): List<LacDecoder.Token> {
        if (text.isEmpty()) return emptyList()
        lock.lock()
        try {
            val chars = text.toCharArray()
            val ids = LongArray(chars.size) { index ->
                val c = q2b[chars[index]] ?: chars[index]
                vocab[c] ?: oovId
            }
            val idsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(ids),
                longArrayOf(1, ids.size.toLong())
            )
            val lengthTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(ids.size.toLong())),
                longArrayOf(1)
            )
            idsTensor.use { idsValue ->
                lengthTensor.use { lengthValue ->
                    session.run(
                        mapOf("token_ids" to idsValue, "length" to lengthValue)
                    ).use { result ->
                        val output = result.get(0) as OnnxTensor
                        @Suppress("UNCHECKED_CAST")
                        val value = output.value as Array<Array<FloatArray>>
                        val emissions = value[0]
                        val tagIds = LacDecoder.viterbi(emissions, transitions, chars.size)
                        return LacDecoder.decodeTokens(text, tagIds, idToTag)
                            .map { token ->
                                token.copy(
                                    start = token.start + baseOffset,
                                    end = token.end + baseOffset
                                )
                            }
                    }
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** 长段落按标点切成 ≤512 字的块，避免单次推理序列过长 */
    private fun splitChunks(text: String): List<String> {
        if (text.length <= MAX_CHUNK) return listOf(text)
        val chunks = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(text.length, start + MAX_CHUNK)
            if (end == text.length) {
                chunks.add(text.substring(start))
                break
            }
            var cut = -1
            for (i in end - 1 downTo start) {
                if (text[i] in SENTENCE_END) {
                    cut = i + 1
                    break
                }
            }
            if (cut <= start) cut = end
            chunks.add(text.substring(start, cut))
            start = cut
        }
        return chunks
    }

    private fun loadWordDic(file: File) {
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEach
                val id = line.substring(0, tab).trim().toLongOrNull() ?: return@forEach
                val value = line.substring(tab + 1)
                when {
                    value == "OOV" -> oovId = id
                    value.length == 1 -> vocab[value[0]] = id
                }
            }
        }
    }

    private fun loadQ2b(file: File) {
        if (!file.isFile) return
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab >= line.length - 1) return@forEach
                q2b[line[0]] = line[tab + 1]
            }
        }
    }

    private fun loadTagDic(file: File) {
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEach
                val id = line.substring(0, tab).trim().toIntOrNull() ?: return@forEach
                // 文件后段会把部分词性 id 覆盖为 PER/LOC/ORG/TIME/O，必须"后出现的为准"
                idToTag[id] = line.substring(tab + 1).trim()
            }
        }
    }

    private fun loadTransitions(file: File): Array<FloatArray> {
        val rows = ArrayList<FloatArray>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                rows.add(line.trim().split(Regex("\\s+")).map { it.toFloat() }.toFloatArray())
            }
        }
        require(rows.isNotEmpty()) { "CRF 转移矩阵为空" }
        val cols = rows[0].size
        require(rows.all { it.size == cols }) { "CRF 转移矩阵不是矩形" }
        return rows.toTypedArray()
    }

    override fun close() {
        runCatching { session.close() }
    }

    private companion object {
        const val MAX_CHUNK = 512
        val SENTENCE_END = setOf('。', '！', '？', '；', '!', '?', ';', '\n')
    }
}
