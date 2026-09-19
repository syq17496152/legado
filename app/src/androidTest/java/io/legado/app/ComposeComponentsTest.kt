package io.legado.app

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.ui.widget.components.AppConfirmDialog
import io.legado.app.ui.widget.components.BadgeDot
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.components.EmptyStateAction
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.InlineTaskBar
import io.legado.app.ui.widget.components.InlineTaskState
import io.legado.app.ui.widget.components.SettingsCard
import io.legado.app.ui.widget.compose.AppSemanticColors
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 四族核心组件 Compose UI 测试（ui-subpage-optimization A2.1.3）。
 *
 * 背景（design C4）：项目此前 `@Preview` 与 UI 测试双双为零，组件收敛「改坏时无自动化信号」。
 * 本类建立 `createComposeRule` 基建并覆盖**回调 / 禁用态 / 危险色**三类断言。
 *
 * ⚠️ 需真机或模拟器（instrumented test）；CI 无设备，仅本地运行。
 * ⚠️ 组件内部经 `AppUiTokens` 取色会读 `AppConfig`（依赖 `appCtx`），故**必须在
 *    instrumented 环境运行**——宿主 App 已初始化 ContentProvider，`appCtx` 可用。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ComposeComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun str(resId: Int): String = context.getString(resId)

    /** 断言位图中存在与 [target] 颜色在 [tolerance] 容差内的像素（文字抗锯齿故用容差）。 */
    private fun ImageBitmap.containsColorNear(target: Color, tolerance: Int = 40): Boolean {
        val bmp = asAndroidBitmap()
        val tr = (target.red * 255f).toInt()
        val tg = (target.green * 255f).toInt()
        val tb = (target.blue * 255f).toInt()
        for (x in 0 until bmp.width) {
            for (y in 0 until bmp.height) {
                val p = bmp.getPixel(x, y)
                if (abs(AndroidColor.red(p) - tr) <= tolerance &&
                    abs(AndroidColor.green(p) - tg) <= tolerance &&
                    abs(AndroidColor.blue(p) - tb) <= tolerance
                ) {
                    return true
                }
            }
        }
        return false
    }

    // ---------- 列表/反馈族：BadgeDot（count 变体 / 隐藏态） ----------

    @Test
    fun badgeDot_countVariants() {
        var count by mutableStateOf(0)
        rule.setContent { BadgeDot(count = count) }

        // count = 0 → 隐藏（零占位）
        rule.onNodeWithText("0").assertDoesNotExist()

        count = 5
        rule.waitForIdle()
        rule.onNodeWithText("5").assertIsDisplayed()

        count = 150
        rule.waitForIdle()
        rule.onNodeWithText("99+").assertIsDisplayed()

        // count = -1 → 纯圆点，无数字
        count = -1
        rule.waitForIdle()
        rule.onNodeWithText("-1").assertDoesNotExist()
    }

    // ---------- 反馈族：InlineTaskBar（Idle/Running/Done + 取消回调） ----------

    @Test
    fun inlineTaskBar_statesAndCancelCallback() {
        var state by mutableStateOf(InlineTaskState.Idle)
        var cancelled = 0
        val progress = "进度 1/3"
        rule.setContent {
            InlineTaskBar(state = state, text = progress, onCancel = { cancelled++ })
        }

        // Idle → 不渲染（零占位）
        rule.onNodeWithText(progress).assertDoesNotExist()

        // Running → 文案 + 取消可点，回调触发
        state = InlineTaskState.Running
        rule.waitForIdle()
        rule.onNodeWithText(progress).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.cancel)).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, cancelled) }

        // Done → 文案仍在（结果文案）
        state = InlineTaskState.Done
        rule.waitForIdle()
        rule.onNodeWithText(progress).assertIsDisplayed()
    }

    @Test
    fun inlineTaskBar_blankText_rendersNothing() {
        rule.setContent {
            InlineTaskBar(state = InlineTaskState.Running, text = "", onCancel = {})
        }
        rule.onNodeWithText(str(R.string.cancel)).assertDoesNotExist()
    }

    // ---------- 空态族：EmptyStatePlaceholder（主/次动作回调 + 无动作态） ----------

    @Test
    fun emptyState_actionsInvokeCallbacks() {
        var primary = 0
        var secondary = 0
        rule.setContent {
            EmptyStatePlaceholder(
                icon = Icons.Filled.BugReport,
                title = "无数据",
                subtitle = "副标题",
                primaryAction = EmptyStateAction("重试") { primary++ },
                secondaryActions = listOf(EmptyStateAction("去设置") { secondary++ }),
            )
        }
        rule.onNodeWithText("无数据").assertIsDisplayed()
        rule.onNodeWithText("副标题").assertIsDisplayed()
        rule.onNodeWithText("重试").assertIsDisplayed().performClick()
        rule.onNodeWithText("去设置").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(1, primary)
            assertEquals(1, secondary)
        }
    }

    @Test
    fun emptyState_withoutActions_rendersNoButtons() {
        rule.setContent {
            EmptyStatePlaceholder(icon = Icons.Filled.BugReport, title = "空态")
        }
        rule.onNodeWithText("空态").assertIsDisplayed()
        rule.onNodeWithText("重试").assertDoesNotExist()
        rule.onNodeWithText("去设置").assertDoesNotExist()
    }

    // ---------- 弹框族：ConfirmDialog / AppConfirmDialog（回调 + 危险色单源） ----------

    @Test
    fun confirmDialog_destructive_usesDangerColorAndInvokes() {
        var confirmed = 0
        rule.setContent {
            ConfirmDialog(
                title = "删除",
                text = "确认删除？",
                confirmText = "删除确认",
                cancelText = "取消",
                destructive = true,
                onConfirm = { confirmed++ },
                onDismiss = {},
            )
        }
        val confirm = rule.onNodeWithText("删除确认")
        confirm.assertIsDisplayed()
        // 危险色单源（AD-14）：destructive 确认钮文字应命中 AppSemanticColors.Danger
        assertTrue(
            "destructive 确认钮未命中危险色单源",
            confirm.captureToImage().containsColorNear(AppSemanticColors.Danger)
        )
        confirm.performClick()
        rule.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test
    fun appConfirmDialog_nonDestructive_invokesAndDismisses() {
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            AppConfirmDialog(
                title = "标题",
                body = "内容",
                confirmText = "确定",
                dismissText = "取消",
                onConfirm = { confirmed++ },
                onDismiss = { dismissed++ },
            )
        }
        rule.onNodeWithText("标题").assertIsDisplayed()
        rule.onNodeWithText("内容").assertIsDisplayed()
        rule.onNodeWithText("确定").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, confirmed) }
    }

    // ---------- 设置族：SettingsCard（标题 + 内容渲染） ----------

    @Test
    fun settingsCard_rendersTitleAndContent() {
        rule.setContent {
            SettingsCard(title = "分组标题") {
                androidx.compose.material3.Text("卡片内容")
            }
        }
        rule.onNodeWithText("分组标题").assertIsDisplayed()
        rule.onNodeWithText("卡片内容").assertIsDisplayed()
    }
}
