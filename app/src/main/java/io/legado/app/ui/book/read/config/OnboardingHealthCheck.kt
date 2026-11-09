package io.legado.app.ui.book.read.config

/**
 * 引导完成前体检（design §1.17 第 4 维 / R21 / S18）：纯函数，JVM 可单测。
 *
 * 只做三件"会影响能不能稳定听下去"的检查，全部可跳过 —— 体检是提示，不是门槛（AD-04 不阻塞）。
 */
object OnboardingHealthCheck {

    const val ID_NOTIFICATION = "notification"
    const val ID_BATTERY = "battery"
    const val ID_ENGINE = "engine"

    data class Item(
        val id: String,
        val label: String,
        val ok: Boolean,
        /** 未完成时的动作文案；已完成时为空串 */
        val action: String
    )

    /**
     * @param notificationsOk 通知权限已开（关掉后后台听书没有任何状态提示）
     * @param batteryOk 已忽略电池优化（国产 ROM 会杀后台，长章节必断）
     * @param engineOk 已把本应用设为系统语音引擎（系统朗读类入口才会走离线音色）
     */
    fun items(notificationsOk: Boolean, batteryOk: Boolean, engineOk: Boolean): List<Item> = listOf(
        Item(ID_NOTIFICATION, "通知权限", notificationsOk, if (notificationsOk) "" else "去开启"),
        Item(ID_BATTERY, "后台不被省电限制", batteryOk, if (batteryOk) "" else "去设置"),
        Item(ID_ENGINE, "设为系统语音引擎", engineOk, if (engineOk) "" else "去选择")
    )

    /** 未完成项数（0 = 全部就绪，卡片可只显示一行"都准备好了"） */
    fun pendingCount(items: List<Item>): Int = items.count { !it.ok }

    /**
     * 移动网络下载确认（S18）：蜂窝网络下下载大语音包必须先确认，避免用户流量被吃。
     * @param isCellular 当前默认网络是蜂窝
     * @param wifiOnlyConfirmed 用户本次已勾选"仍要下载"
     */
    fun needsMobileConfirm(isCellular: Boolean, wifiOnlyConfirmed: Boolean = false): Boolean =
        isCellular && !wifiOnlyConfirmed
}
