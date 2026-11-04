package io.legado.app.help.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalLlmPrefs
import io.legado.app.help.readaloud.casting.AiTaskRouter
import io.legado.app.help.readaloud.casting.AiTaskRouter.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * 6.4 接线：现有 AI 服务必须经 `AiTaskRouter` 判定执行方。
 * 策略关闭时不得静默改用云端（那会让「关掉 AI」形同虚设），必须给出可执行原因。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AiServiceRoutingGateTest {

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Context>().injectAsAppCtx()
        AppConfig.aiTaskPolicy = LocalLlmPrefs.POLICY_OFF
    }

    @Test
    fun `策略关闭时章节总结明确不可用`() {
        val input = AiChapterSummaryService.SummaryInput(
            book = Book(name = "测试书", bookUrl = "test://book"),
            chapter = BookChapter(index = 1, title = "第一章"),
            content = "这一章有正文内容。"
        )
        val error = runCatching {
            runBlocking {
                // forceRefresh=true：跳过缓存读取（单测无 Room），直接走路由闸
                AiChapterSummaryService.summarize(
                    input = input,
                    forceRefresh = true,
                    onPartial = {},
                    onStatus = {}
                )
            }
        }.exceptionOrNull()

        assertTrue("必须是业务异常而不是崩溃", error is NoStackTraceException)
        assertTrue(
            "关闭策略要说明原因，不能只说失败",
            error?.message.orEmpty().contains("该任务已关闭")
        )
    }

    @Test
    fun `策略关闭与开启时路由结论可区分`() {
        assertEquals(
            Executor.NONE,
            AiTaskRouter.routeFor(onlineConfigured = true).executor
        )
        AppConfig.aiTaskPolicy = LocalLlmPrefs.POLICY_ONLINE
        assertEquals(
            Executor.ONLINE,
            AiTaskRouter.routeFor(onlineConfigured = true).executor
        )
    }
}
