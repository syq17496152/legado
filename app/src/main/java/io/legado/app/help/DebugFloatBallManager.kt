package io.legado.app.help

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.postDelayed
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.utils.showDialogFragment

/**
 * 调试日志悬浮球管理器（借鉴蛋蛋Max DebugFloatingBallManager，简化为传统 View 实现）。
 * 简化说明：蛋蛋Max 用 ComposeView + FlowLogRecorder + DebugEventCenter（13 个文件）；
 * 本实现用 TextView + GradientDrawable（单文件），复用现有 AppLog + AppLogDialog。
 * 已知上限：无日志流量录制、无事件中心、无面板内过滤（依赖 AppLogDialog 增强）。
 * 升级路径：如需流量录制可引入 FlowLogRecorder；如需独立面板可新增 DebugLogPanelDialog。
 */
object DebugFloatBallManager {
    private const val TAG = "DebugFloatBall"

    private var isShowing = false
    private var isAttaching = false
    private var currentActivity: AppCompatActivity? = null
    private var floatingBallView: TextView? = null
    private var showToken: Int = 0

    fun updateState(enabled: Boolean) {
        if (enabled) {
            currentActivity?.let { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    show(activity)
                }
            }
        } else {
            hide()
        }
    }

    fun onActivityResumed(activity: Activity) {
        if (activity !is AppCompatActivity) return
        if (AppConfig.debugLogFloatingBall && !isShowing && !isAttaching) {
            show(activity)
        }
    }

    fun onActivityPaused(activity: Activity) {
        if (currentActivity == activity) {
            hide()
            currentActivity = null
        }
    }

    fun onActivityDestroyed(activity: Activity) {
        if (currentActivity == activity) {
            showToken++
            hide()
            currentActivity = null
        }
    }

    private fun show(activity: AppCompatActivity) {
        if (!AppConfig.debugLogFloatingBall) return
        if (isShowing || isAttaching) return
        if (activity.isFinishing || activity.isDestroyed) return

        currentActivity = activity
        isAttaching = true
        val currentToken = ++showToken

        val rootView = activity.window.decorView as? ViewGroup
        if (rootView == null) {
            AppLog.putWarn("$TAG: show() failed - rootView is null")
            isAttaching = false
            return
        }

        try {
            val ballView = createBallView(activity)
            floatingBallView = ballView

            rootView.post {
                if (!validateShowToken(currentToken, activity)) {
                    isAttaching = false
                    floatingBallView = null
                    return@post
                }

                if (ballView.parent != null) {
                    isAttaching = false
                    floatingBallView = null
                    return@post
                }

                val density = activity.resources.displayMetrics.density
                val layoutParams = FrameLayout.LayoutParams(
                    (48 * density).toInt(),
                    (48 * density).toInt()
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    marginEnd = (16 * density).toInt()
                    bottomMargin = (96 * density).toInt()
                }

                try {
                    rootView.addView(ballView, layoutParams)
                    isShowing = true
                    isAttaching = false
                } catch (e: Exception) {
                    AppLog.putWarn("$TAG: show() failed to add view", e)
                    isAttaching = false
                    floatingBallView = null
                }
            }
        } catch (e: Exception) {
            AppLog.putWarn("$TAG: show() exception", e)
            isAttaching = false
            floatingBallView = null
        }
    }

    private fun hide() {
        if (!isShowing && !isAttaching) return

        showToken++
        isAttaching = false

        floatingBallView?.let { view ->
            view.postDelayed(50) {
                try {
                    val parent = view.parent as? ViewGroup
                    parent?.removeView(view)
                } catch (e: Exception) {
                    AppLog.putWarn("$TAG: hide() exception", e)
                }
            }
        }

        floatingBallView = null
        isShowing = false
    }

    private fun validateShowToken(token: Int, activity: AppCompatActivity): Boolean {
        return token == showToken &&
                currentActivity == activity &&
                AppConfig.debugLogFloatingBall &&
                !activity.isFinishing &&
                !activity.isDestroyed
    }

    private fun createBallView(context: Context): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = "D"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCFF5722.toInt())
                setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
            }
            elevation = 6 * density
            isClickable = true
            isFocusable = true
            setOnClickListener { openLogPanel() }
        }
    }

    private fun openLogPanel() {
        val activity = currentActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        hide()
        activity.window.decorView.postDelayed(200) {
            try {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.showDialogFragment(AppLogDialog())
                }
            } catch (e: Exception) {
                AppLog.putWarn("$TAG: 打开日志面板失败", e)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    show(activity)
                }
            }
        }
    }
}
