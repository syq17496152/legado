package io.legado.app.help.crash

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * crash-hardening AD-06：ExitInfo 取证文件格式与熔断计数口径。
 *
 * 这些纯函数决定"崩溃记录能不能被读到、能不能被正确计数"，属行为口径，必须有期望值断言。
 */
class ExitInfoRecorderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun exitFileName_matchesPattern() {
        assertEquals("exit-1794120707146-16851.txt", ExitInfoRecorder.exitFileName(1794120707146L, 16851))
    }

    @Test
    fun reasonLabel_mapsKnownReasons() {
        assertEquals("ANR", ExitInfoRecorder.reasonLabel(ApplicationExitInfo.REASON_ANR))
        assertEquals("CRASH_NATIVE", ExitInfoRecorder.reasonLabel(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("CRASH", ExitInfoRecorder.reasonLabel(ApplicationExitInfo.REASON_CRASH))
        assertEquals("SIGNALED", ExitInfoRecorder.reasonLabel(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals("UNKNOWN", ExitInfoRecorder.reasonLabel(9999))
    }

    @Test
    fun countDeaths_onlyCountsMatchingProcessWithinWindow() {
        val now = 1_800_000_000_000L
        val within = File(temp.root, "exit-${now - 1_000}-1.txt").apply {
            writeText("process=io.legado.miss.app.debug:llm\nreason=CRASH_NATIVE(5)\n")
        }
        val within2 = File(temp.root, "exit-${now - 2_000}-2.txt").apply {
            writeText("process=io.legado.miss.app.debug:llm\nreason=ANR(6)\n")
        }
        val outside = File(temp.root, "exit-${now - TimeUnit.MINUTES.toMillis(11)}-3.txt").apply {
            writeText("process=io.legado.miss.app.debug:llm\n")
        }
        val otherProcess = File(temp.root, "exit-${now - 3_000}-4.txt").apply {
            writeText("process=io.legado.miss.app.debug\n")
        }
        val javaCrash = File(temp.root, "crash-2026-11-08-14-51-47-1.log").apply {
            writeText("java.lang.OutOfMemoryError\n")
        }
        val files = listOf(within, within2, outside, otherProcess, javaCrash)
        assertEquals(
            2,
            ExitInfoRecorder.countDeaths(
                files, now, TimeUnit.MINUTES.toMillis(10), ":llm"
            )
        )
        assertEquals(
            0,
            ExitInfoRecorder.countDeaths(
                files, now, TimeUnit.MINUTES.toMillis(10), ":webview"
            )
        )
    }
}
