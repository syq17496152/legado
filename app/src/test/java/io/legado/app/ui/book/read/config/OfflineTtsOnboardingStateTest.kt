package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引导判定内核（AD-04）：老用户不打扰、跳过有限次、半就绪不放行。 */
class OfflineTtsOnboardingStateTest {

    @Test
    fun `已引导用户直接进首页`() {
        assertEquals(
            OfflineTtsOnboardingState.Decision.ShowHome,
            OfflineTtsOnboardingState.decide(onboarded = true, engineReady = false, skipCount = 0)
        )
    }

    @Test
    fun `引擎已就绪的老用户即使没写过标记也不弹`() {
        assertEquals(
            OfflineTtsOnboardingState.Decision.ShowHome,
            OfflineTtsOnboardingState.decide(onboarded = false, engineReady = true, skipCount = 0)
        )
    }

    @Test
    fun `未引导且未就绪时进入引导`() {
        assertEquals(
            OfflineTtsOnboardingState.Decision.EnterOnboarding,
            OfflineTtsOnboardingState.decide(onboarded = false, engineReady = false, skipCount = 0)
        )
    }

    @Test
    fun `跳过达到上限后静默`() {
        assertEquals(
            OfflineTtsOnboardingState.Decision.Skipped,
            OfflineTtsOnboardingState.decide(
                onboarded = false,
                engineReady = false,
                skipCount = OfflineTtsOnboardingState.MAX_SKIP
            )
        )
    }

    @Test
    fun `残标模型不能进入第二步`() {
        assertFalse(OfflineTtsOnboardingState.canEnterStep2(modelReady = false, voiceCount = 3))
        assertFalse(OfflineTtsOnboardingState.canEnterStep2(modelReady = true, voiceCount = 0))
        assertTrue(OfflineTtsOnboardingState.canEnterStep2(modelReady = true, voiceCount = 1))
    }

    @Test
    fun `跳过计数递增并在上限时标记静默`() {
        val first = OfflineTtsOnboardingState.skipResult(0)
        assertEquals(1, first.newCount)
        assertFalse(first.silentFromNow)

        val last = OfflineTtsOnboardingState.skipResult(OfflineTtsOnboardingState.MAX_SKIP - 1)
        assertEquals(OfflineTtsOnboardingState.MAX_SKIP, last.newCount)
        assertTrue(last.silentFromNow)
    }

    @Test
    fun `负数跳过计数按零处理`() {
        assertEquals(1, OfflineTtsOnboardingState.skipResult(-5).newCount)
    }

    @Test
    fun `完成引导写入标记并清零跳过计数`() {
        val mark = OfflineTtsOnboardingState.onCompleted()
        assertTrue(mark.onboarded)
        assertEquals(0, mark.skipCount)
    }

    // ===== 已下载档位不再重复下载（真机铁证 2026-11-09）=====

    @Test
    fun `档位已就绪时不走下载链路`() {
        // 真机：选了已下载的「轻量多音色」仍弹「正在使用移动网络」，用户以为要重下 30MB
        assertFalse(OfflineTtsOnboardingState.step1NeedsDownload(profileReady = true))
        assertTrue(OfflineTtsOnboardingState.step1NeedsDownload(profileReady = false))
    }

    @Test
    fun `第1步主按钮文案随就绪与断点变化`() {
        // 已就绪 ⇒ 启用并继续（不得再写"下载并启用"）
        assertEquals(
            "启用并继续",
            OfflineTtsOnboardingState.step1PrimaryLabel(
                profileReady = true, needsVoicePack = false,
                downloading = false, progress = 0, resumePercent = 40
            )
        )
        // 未就绪 + 有断点 ⇒ 继续下载
        assertEquals(
            "继续下载（已下载 40%）",
            OfflineTtsOnboardingState.step1PrimaryLabel(
                profileReady = false, needsVoicePack = false,
                downloading = false, progress = 0, resumePercent = 40
            )
        )
        // 未就绪且无断点 ⇒ 下载并启用
        assertEquals(
            "下载并启用",
            OfflineTtsOnboardingState.step1PrimaryLabel(
                profileReady = false, needsVoicePack = false,
                downloading = false, progress = 0, resumePercent = 0
            )
        )
        // 下载中优先级最高（点此暂停）
        assertEquals(
            "下载中 12%（点此暂停）",
            OfflineTtsOnboardingState.step1PrimaryLabel(
                profileReady = false, needsVoicePack = false,
                downloading = true, progress = 12, resumePercent = 0
            )
        )
        // 克隆档未导入音色包 ⇒ 导入音色包
        assertEquals(
            "导入音色包",
            OfflineTtsOnboardingState.step1PrimaryLabel(
                profileReady = false, needsVoicePack = true,
                downloading = false, progress = 0, resumePercent = 0
            )
        )
    }

    // ===== 第 2 步默认选中态（真机铁证：首进第 2 步三张卡全是未选中）=====

    @Test
    fun `第2步首进默认选中推荐列表首个`() {
        assertEquals(
            "multi-s3",
            OfflineTtsOnboardingState.defaultSelectedVoiceId(
                current = "",
                availableIds = listOf("multi-s3", "multi-s4", "multi-s5")
            )
        )
    }

    @Test
    fun `第2步沿用仍然可用的旧选择`() {
        assertEquals(
            "multi-s4",
            OfflineTtsOnboardingState.defaultSelectedVoiceId(
                current = "multi-s4",
                availableIds = listOf("multi-s3", "multi-s4")
            )
        )
        // 旧选择已被过滤掉（换档/被删）⇒ 回落到首个推荐
        assertEquals(
            "multi-s3",
            OfflineTtsOnboardingState.defaultSelectedVoiceId(
                current = "fast-only",
                availableIds = listOf("multi-s3")
            )
        )
    }

    @Test
    fun `第2步无可用音色时不给选中值`() {
        assertEquals(
            "",
            OfflineTtsOnboardingState.defaultSelectedVoiceId(current = "", availableIds = emptyList())
        )
    }

    // ===== 引导结束落点（真机铁证：从引擎页向导 tab 进来，配完停在只有两个按钮的向导 tab）=====

    @Test
    fun `从引擎页向导入口进来时补开听书设置`() {
        assertTrue(OfflineTtsOnboardingState.shouldOpenCastSceneAfterOnboarding("guide"))
        // 从听书设置自身进入（栈下已有该页）不重复开
        assertFalse(OfflineTtsOnboardingState.shouldOpenCastSceneAfterOnboarding("read_dialog"))
        assertFalse(OfflineTtsOnboardingState.shouldOpenCastSceneAfterOnboarding("notification"))
        assertFalse(OfflineTtsOnboardingState.shouldOpenCastSceneAfterOnboarding("other"))
        assertFalse(OfflineTtsOnboardingState.shouldOpenCastSceneAfterOnboarding(""))
    }
}
