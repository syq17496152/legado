package io.legado.app.help.llm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * 2.5 大文本走文件（AD-25）：正文落盘 + 原子结果 + 过期清理。
 * 这些行为直接决定「1MB 以上文本不触发 TransactionTooLarge」与「多进程读到半截 JSON」两类事故，
 * 必须在 JVM 层锁定，不能靠真机碰运气。
 */
class LlmJobsTest {

    private lateinit var root: File
    private lateinit var jobsDir: File
    private lateinit var outDir: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "llm-jobs-" + UUID.randomUUID())
        jobsDir = File(root, "llm_jobs")
        outDir = File(root, "llm_out")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `大文本写入后可完整读回`() {
        val jobId = LlmJobs.newJobId()
        val text = "段".repeat(120_000) // ≈360KB，超过 Binder 传输的现实阈值
        LlmJobs.writeInput(jobsDir, jobId, text)
        assertEquals(text.length, LlmJobs.readInput(jobsDir, jobId)?.length)
        assertEquals(text, LlmJobs.readInput(jobsDir, jobId))
    }

    @Test
    fun `非法 jobId 既不写也不读`() {
        // 路径穿越： "../" 会跳出 job 目录
        assertFalse(LlmJobs.isSafeJobId("../../etc/passwd"))
        assertFalse(LlmJobs.isSafeJobId("abc"))
        assertTrue(LlmJobs.isSafeJobId(LlmJobs.newJobId()))
        assertNull(LlmJobs.readInput(jobsDir, "../../etc/passwd"))
        assertNull(LlmJobs.readResult(outDir, "../../etc/passwd"))
    }

    @Test
    fun `结果原子落盘且无 part 残留`() {
        val jobId = LlmJobs.newJobId()
        val result = LlmJobResult(ok = true, text = "{\"a\":[{\"i\":0,\"n\":0,\"c\":0.9}]}", tokenCount = 12)
        LlmJobs.writeResult(outDir, jobId, result)
        val readBack = LlmJobs.readResult(outDir, jobId)
        assertEquals(result.text, readBack?.text)
        assertEquals(12, readBack?.tokenCount)
        assertFalse(File(outDir, "$jobId.json.part").exists())
    }

    @Test
    fun `坏 JSON 返回 null 而不是抛异常`() {
        val jobId = LlmJobs.newJobId()
        outDir.mkdirs()
        LlmJobs.outputFile(outDir, jobId).writeText("{不是合法 json", Charsets.UTF_8)
        assertNull(LlmJobs.readResult(outDir, jobId))
    }

    @Test
    fun `prune 只删过期文件且跳过无法识别时间的文件`() {
        val now = 1_760_000_000_000L
        val old = LlmJobs.newJobId(now - LlmJobs.RETENTION_MS - 5_000L)
        val fresh = LlmJobs.newJobId(now)
        jobsDir.mkdirs()
        LlmJobs.writeInput(jobsDir, old, "old")
        LlmJobs.writeInput(jobsDir, fresh, "fresh")
        File(outDir, "unknown-name.txt").apply {
            parentFile?.mkdirs()
            writeText("keep", Charsets.UTF_8)
        }
        val removed = LlmJobs.prune(jobsDir, outDir, now = now)
        assertEquals(1, removed)
        assertFalse(LlmJobs.inputFile(jobsDir, old).exists())
        assertTrue(LlmJobs.inputFile(jobsDir, fresh).exists())
        assertTrue(File(outDir, "unknown-name.txt").exists())
    }

    @Test
    fun `jobTimeOf 只解析本模块命名格式`() {
        val jobId = LlmJobs.newJobId(1_760_000_000_000L)
        assertEquals(1_760_000_000_000L, LlmJobs.jobTimeOf("$jobId.txt"))
        assertNull(LlmJobs.jobTimeOf("unknown.txt"))
        assertNull(LlmJobs.jobTimeOf("x-1.txt"))
    }

    @Test
    fun `失败结果保留错误码供上层降级`() {
        val result = LlmJobResult.failure(LlmIpc.ERROR_CANCELLED, "已取消")
        assertFalse(result.ok)
        assertEquals(LlmIpc.ERROR_CANCELLED, result.errorCode)
        assertEquals("已取消", result.message)
    }
}
