package io.legado.app.help.config

import android.content.Context
import io.legado.app.R
import splitties.init.appCtx

@Suppress("ConstPropertyName")
object ReadTipConfig {

    const val none = 0
    const val chapterTitle = 1
    const val time = 2
    const val battery = 3
    const val batteryPercentage = 10
    const val page = 4
    const val totalProgress = 5
    const val pageAndTotal = 6
    const val bookName = 7
    const val timeBattery = 8
    const val timeBatteryPercentage = 9
    const val totalProgress1 = 11

    /**
     * R12（B3）：槽位「自定义模板」哨兵值。
     *
     * ⚠ 取值必须落在既有枚举之外（既有为 0..11），且**不得**与它们混用；
     * 槽位值 == 本常量时由 `PageView` 走按槽位的模板渲染分支（详见 `ReadTipTemplate`）。
     * 与枚举值的另一处关键差别：**多个槽位可同时为 `custom`**（各自持有独立模板），
     * 故「去重清除」（`clearRepeat`）与「查重」逻辑都必须跳过本值。
     */
    const val custom = 100

    /** 槽位总数（页眉左/中/右 + 页脚左/中/右），顺序与 `PageView` 的槽位视图表一致 */
    const val slotCount = 6

    const val SLOT_HEADER_LEFT = 0
    const val SLOT_HEADER_MIDDLE = 1
    const val SLOT_HEADER_RIGHT = 2
    const val SLOT_FOOTER_LEFT = 3
    const val SLOT_FOOTER_MIDDLE = 4
    const val SLOT_FOOTER_RIGHT = 5

    val tipValues = arrayOf(
        none, bookName, chapterTitle, time, battery, batteryPercentage, page,
        totalProgress, totalProgress1, pageAndTotal, timeBattery, timeBatteryPercentage, custom
    )
    val tipNames get() = appCtx.resources.getStringArray(R.array.read_tip).toList()

    val tipColorNames get() = appCtx.resources.getStringArray(R.array.tip_color).toList()
    val tipDividerColorNames
        get() = appCtx.resources.getStringArray(R.array.tip_divider_color).toList()

    var tipHeaderLeft: Int
        get() = ReadBookConfig.config.tipHeaderLeft
        set(value) {
            ReadBookConfig.config.tipHeaderLeft = value
        }

    var tipHeaderMiddle: Int
        get() = ReadBookConfig.config.tipHeaderMiddle
        set(value) {
            ReadBookConfig.config.tipHeaderMiddle = value
        }

    var tipHeaderRight: Int
        get() = ReadBookConfig.config.tipHeaderRight
        set(value) {
            ReadBookConfig.config.tipHeaderRight = value
        }

    var tipFooterLeft: Int
        get() = ReadBookConfig.config.tipFooterLeft
        set(value) {
            ReadBookConfig.config.tipFooterLeft = value
        }

    var tipFooterMiddle: Int
        get() = ReadBookConfig.config.tipFooterMiddle
        set(value) {
            ReadBookConfig.config.tipFooterMiddle = value
        }

    var tipFooterRight: Int
        get() = ReadBookConfig.config.tipFooterRight
        set(value) {
            ReadBookConfig.config.tipFooterRight = value
        }

    var headerMode: Int
        get() = ReadBookConfig.config.headerMode
        set(value) {
            ReadBookConfig.config.headerMode = value
        }

    /** R 批 §3.3.2：页眉返回按钮开关（默认关；开启后页眉最左侧渲染返回图标） */
    var showHeaderBackButton: Boolean
        get() = ReadBookConfig.config.showHeaderBackButton
        set(value) {
            ReadBookConfig.config.showHeaderBackButton = value
        }

    /**
     * 按槽位下标（0..5 = 页眉左/中/右、页脚左/中/右）读写槽位值与自定义模板。
     *
     * R12（B3）：槽位值 == [custom] 时由 `PageView` 走模板渲染分支；模板按槽位独立存储
     * （6 个 String 字段，而非 Map —— 规避 R8 × Gson 泛型签名门禁对集合字段签名的剥离风险）。
     */
    fun slotValue(slot: Int): Int = when (slot) {
        SLOT_HEADER_LEFT -> tipHeaderLeft
        SLOT_HEADER_MIDDLE -> tipHeaderMiddle
        SLOT_HEADER_RIGHT -> tipHeaderRight
        SLOT_FOOTER_LEFT -> tipFooterLeft
        SLOT_FOOTER_MIDDLE -> tipFooterMiddle
        SLOT_FOOTER_RIGHT -> tipFooterRight
        else -> none
    }

    fun setSlotValue(slot: Int, value: Int) {
        when (slot) {
            SLOT_HEADER_LEFT -> tipHeaderLeft = value
            SLOT_HEADER_MIDDLE -> tipHeaderMiddle = value
            SLOT_HEADER_RIGHT -> tipHeaderRight = value
            SLOT_FOOTER_LEFT -> tipFooterLeft = value
            SLOT_FOOTER_MIDDLE -> tipFooterMiddle = value
            SLOT_FOOTER_RIGHT -> tipFooterRight = value
        }
    }

    fun slotTemplate(slot: Int): String = when (slot) {
        SLOT_HEADER_LEFT -> tipHeaderLeftTemplate
        SLOT_HEADER_MIDDLE -> tipHeaderMiddleTemplate
        SLOT_HEADER_RIGHT -> tipHeaderRightTemplate
        SLOT_FOOTER_LEFT -> tipFooterLeftTemplate
        SLOT_FOOTER_MIDDLE -> tipFooterMiddleTemplate
        SLOT_FOOTER_RIGHT -> tipFooterRightTemplate
        else -> ""
    }

    fun setSlotTemplate(slot: Int, template: String) {
        when (slot) {
            SLOT_HEADER_LEFT -> tipHeaderLeftTemplate = template
            SLOT_HEADER_MIDDLE -> tipHeaderMiddleTemplate = template
            SLOT_HEADER_RIGHT -> tipHeaderRightTemplate = template
            SLOT_FOOTER_LEFT -> tipFooterLeftTemplate = template
            SLOT_FOOTER_MIDDLE -> tipFooterMiddleTemplate = template
            SLOT_FOOTER_RIGHT -> tipFooterRightTemplate = template
        }
    }

    /** 当前处于「自定义模板」状态的槽位下标（配置页据此渲染模板编辑入口） */
    fun customSlots(): List<Int> = (0 until slotCount).filter { slotValue(it) == custom }

    var tipHeaderLeftTemplate: String
        get() = ReadBookConfig.config.tipHeaderLeftTemplate
        set(value) {
            ReadBookConfig.config.tipHeaderLeftTemplate = value
        }

    var tipHeaderMiddleTemplate: String
        get() = ReadBookConfig.config.tipHeaderMiddleTemplate
        set(value) {
            ReadBookConfig.config.tipHeaderMiddleTemplate = value
        }

    var tipHeaderRightTemplate: String
        get() = ReadBookConfig.config.tipHeaderRightTemplate
        set(value) {
            ReadBookConfig.config.tipHeaderRightTemplate = value
        }

    var tipFooterLeftTemplate: String
        get() = ReadBookConfig.config.tipFooterLeftTemplate
        set(value) {
            ReadBookConfig.config.tipFooterLeftTemplate = value
        }

    var tipFooterMiddleTemplate: String
        get() = ReadBookConfig.config.tipFooterMiddleTemplate
        set(value) {
            ReadBookConfig.config.tipFooterMiddleTemplate = value
        }

    var tipFooterRightTemplate: String
        get() = ReadBookConfig.config.tipFooterRightTemplate
        set(value) {
            ReadBookConfig.config.tipFooterRightTemplate = value
        }

    var footerMode: Int
        get() = ReadBookConfig.config.footerMode
        set(value) {
            ReadBookConfig.config.footerMode = value
        }

    var tipColor: Int
        get() = ReadBookConfig.config.tipColor
        set(value) {
            ReadBookConfig.config.tipColor = value
        }

    var tipDividerColor: Int
        get() = ReadBookConfig.config.tipDividerColor
        set(value) {
            ReadBookConfig.config.tipDividerColor = value
        }

    fun getHeaderModes(context: Context): LinkedHashMap<Int, String> {
        return linkedMapOf(
            Pair(0, context.getString(R.string.hide_when_status_bar_show)),
            Pair(1, context.getString(R.string.show)),
            Pair(2, context.getString(R.string.hide))
        )
    }

    fun getFooterModes(context: Context): LinkedHashMap<Int, String> {
        return linkedMapOf(
            Pair(0, context.getString(R.string.show)),
            Pair(1, context.getString(R.string.hide))
        )
    }
}