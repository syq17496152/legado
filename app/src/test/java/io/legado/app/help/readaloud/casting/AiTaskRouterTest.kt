package io.legado.app.help.readaloud.casting

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalLlmPrefs
import io.legado.app.help.readaloud.casting.AiTaskRouter.Executor
import io.legado.app.help.readaloud.casting.AiTaskRouter.Facts
import io.legado.app.help.readaloud.casting.AiTaskRouter.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * AI 任务路由（6.1/6.4）：本地优先、本地慢/关闭时才按"用户允许"回落云端，关闭则明确不可用；
 * 6.4 起 `routeFor` 从真实偏好取事实，服务不各自拼布尔组合。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AiTaskRouterTest {

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Context>().injectAsAppCtx()
    }

    private fun facts(
        localEnabled: Boolean = true,
        localReady: Boolean = true,
        localSlow: Boolean = false,
        onlineConfigured: Boolean = true,
        onlineAllowed: Boolean = true
    ) = Facts(localEnabled, localReady, localSlow, onlineConfigured, onlineAllowed)

    @Test
    fun `本地就绪且不慢时用本地`() {
        val route = AiTaskRouter.route(Policy.LOCAL, facts())
        assertEquals(Executor.LOCAL, route.executor)
        assertEquals("local", route.source)
        assertTrue(route.available)
    }

    @Test
    fun `本地慢时按允许回落云端`() {
        val route = AiTaskRouter.route(Policy.LOCAL, facts(localSlow = true))
        assertEquals(Executor.ONLINE, route.executor)
        assertTrue(route.reason.contains("本地偏慢"))
    }

    @Test
    fun `本地慢但未允许云端时继续本地`() {
        val route = AiTaskRouter.route(Policy.LOCAL, facts(localSlow = true, onlineAllowed = false))
        assertEquals(Executor.LOCAL, route.executor)
        assertTrue(route.reason.contains("未允许云端"))
    }

    @Test
    fun `本地关闭且未配置云端时不可用并给原因`() {
        val route = AiTaskRouter.route(
            Policy.LOCAL,
            facts(localEnabled = false, onlineConfigured = false, onlineAllowed = true)
        )
        assertEquals(Executor.NONE, route.executor)
        assertFalse(route.available)
        assertEquals("none", route.source)
        assertTrue(route.reason.contains("本地 AI 已关闭"))
    }

    @Test
    fun `本地未就绪且未允许云端时不可用`() {
        val route = AiTaskRouter.route(Policy.LOCAL, facts(localReady = false, onlineAllowed = false))
        assertEquals(Executor.NONE, route.executor)
        assertTrue(route.reason.contains("未就绪"))
    }

    @Test
    fun `仅云端策略在未配置时不可用`() {
        val route = AiTaskRouter.route(Policy.ONLINE, facts(onlineConfigured = false))
        assertEquals(Executor.NONE, route.executor)
        assertTrue(route.reason.contains("云端不可用"))
    }

    @Test
    fun `策略关闭一律不可用`() {
        val route = AiTaskRouter.route(Policy.OFF, facts())
        assertEquals(Executor.NONE, route.executor)
        assertEquals("该任务已关闭", route.reason)
    }

    @Test
    fun `云端策略在允许且已配置时走云端并带来源`() {
        val route = AiTaskRouter.route(Policy.ONLINE, facts())
        assertEquals(Executor.ONLINE, route.executor)
        assertEquals("online", route.source)
    }

    @Test
    fun `策略关闭时真实偏好下任务不可用`() {
        AppConfig.aiTaskPolicy = LocalLlmPrefs.POLICY_OFF
        val route = AiTaskRouter.routeFor(onlineConfigured = true)
        assertEquals(Executor.NONE, route.executor)
        assertEquals("该任务已关闭", route.reason)
    }

    @Test
    fun `本地未启用时按已配置云端回落`() {
        AppConfig.aiTaskPolicy = LocalLlmPrefs.POLICY_LOCAL
        AppConfig.localLlmEnabled = false
        val route = AiTaskRouter.routeFor(onlineConfigured = true)
        assertEquals(Executor.ONLINE, route.executor)
        assertEquals("online", route.source)
        assertTrue(route.reason.contains("本地 AI 已关闭"))

        // 云端也没配置 ⇒ 明确不可用（不能假装成功）
        assertEquals(Executor.NONE, AiTaskRouter.routeFor(onlineConfigured = false).executor)
    }

    @Test
    fun `本地就绪且速度达标时走本地`() {
        AppConfig.aiTaskPolicy = LocalLlmPrefs.POLICY_LOCAL
        AppConfig.localLlmProfileId = "qwen3-0.6b-q4"
        AppConfig.localLlmEnabled = true
        AppConfig.localLlmUnsupportedReason = ""
        AppConfig.localLlmSpeedTokPerSec = 8f
        val route = AiTaskRouter.routeFor(onlineConfigured = true)
        assertEquals(Executor.LOCAL, route.executor)
        assertEquals("local", route.source)
    }

    @Test
    fun `本地偏慢时按允许的云端兜底`() {
        AppConfig.aiTaskPolicy = LocalLlmPrefs.POLICY_LOCAL
        AppConfig.localLlmProfileId = "qwen3-0.6b-q4"
        AppConfig.localLlmEnabled = true
        AppConfig.localLlmUnsupportedReason = ""
        AppConfig.localLlmSpeedTokPerSec = 1.2f
        val route = AiTaskRouter.routeFor(onlineConfigured = true)
        assertEquals(Executor.ONLINE, route.executor)
        assertTrue(route.reason.contains("本地偏慢"))
    }
}
