package io.legado.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.legado.app.constant.AppLog
import io.legado.app.model.AutoTask

/**
 * R7（B2，2026-09-23）：自动任务「唤醒重排」接收器。
 *
 * 背景（真缺失）：自动任务用 `AlarmManager` 定时，而以下四类事件都会让**既有闹钟失效**：
 * - 重启：系统清空全部闹钟 ⇒ 重启后自动任务**再也不触发**；
 * - 换时区 / 手改系统时间：「每天 08:00」这类规则落到错误的绝对时刻；
 * - 覆盖安装：部分 ROM 会清理闹钟。
 * 修复前全仓无 `AutoTaskBootReceiver`（仅订阅的 `RelayBootReceiver`），故该链路完全缺失。
 *
 * 幂等：`AutoTask.refreshSchedule()` 内部先取消既有闹钟再按当前规则重建，
 * 因此本接收器**重复收到广播也不会产生重复排程**；接收器自身不保存任何状态。
 *
 * 降级：重排抛异常（后台限制 / 规则数据异常）**不得让广播崩溃**——记录后可等待下一次
 * 「服务启动」或「用户保存规则」时的 `refreshSchedule()` 自动补齐。
 */
class AutoTaskBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in HANDLED_ACTIONS) return
        runCatching {
            AutoTask.refreshSchedule(context)
        }.onFailure {
            AppLog.put("自动任务唤醒重排失败（$action）", it)
        }
    }

    private companion object {
        /** 四类会使既有闹钟失效的事件（与 Manifest intent-filter 保持一致）。 */
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}