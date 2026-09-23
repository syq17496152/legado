package io.legado.app.ui.book.read.config

import io.legado.app.R

/**
 * R15（B3）外接手柄翻页预设。
 *
 * 需求（spec R15）：翻页键码设置提供手柄预设、一键套用；产品要求**多手柄家族**、
 * **预设可编辑（不得覆盖用户后续手动修改）**、**键码冲突检测（不静默覆盖）**。
 *
 * 纯数据 + 纯函数，便于 JVM 单测。键码以**字面常量**给出（注释标注对应 ACL 常量名）：
 * 引用 `android.view.KeyEvent` 静态常量在 JVM 单测里依赖编译期内联，字面量更稳且可断言。
 */
internal object GamepadKeyPresets {

    private const val KEY_L1 = 102          // KeyEvent.KEYCODE_BUTTON_L1
    private const val KEY_R1 = 103          // KeyEvent.KEYCODE_BUTTON_R1
    private const val KEY_L2 = 104          // KeyEvent.KEYCODE_BUTTON_L2
    private const val KEY_R2 = 105          // KeyEvent.KEYCODE_BUTTON_R2
    private const val KEY_DPAD_UP = 19      // KeyEvent.KEYCODE_DPAD_UP
    private const val KEY_DPAD_DOWN = 20    // KeyEvent.KEYCODE_DPAD_DOWN

    data class Preset(
        val id: String,
        val labelRes: Int,
        val prevKeys: List<Int>,
        val nextKeys: List<Int>
    )

    /**
     * 多手柄家族预设：肩键（L1/R1）为主流默认；扳机（L2/R2）与十字键适配不同握持习惯。
     */
    val all: List<Preset> = listOf(
        Preset(
            id = "shoulder",
            labelRes = R.string.gamepad_preset_shoulder,
            prevKeys = listOf(KEY_L1),
            nextKeys = listOf(KEY_R1)
        ),
        Preset(
            id = "trigger",
            labelRes = R.string.gamepad_preset_trigger,
            prevKeys = listOf(KEY_L2),
            nextKeys = listOf(KEY_R2)
        ),
        Preset(
            id = "dpad",
            labelRes = R.string.gamepad_preset_dpad,
            prevKeys = listOf(KEY_DPAD_UP),
            nextKeys = listOf(KEY_DPAD_DOWN)
        )
    )

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }

    /** 把键码表拼成设置项使用的逗号串（与 `String.appendPageKey` 同格式：`a,b,c`）。 */
    fun toKeyString(keys: List<Int>): String = keys.joinToString(",")

    /** 解析设置项里的逗号串（忽略空白与非法项）；空串 ⇒ 空列表。 */
    fun parseKeyString(raw: String): List<Int> = raw.split(',')
        .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toIntOrNull() }

    /**
     * 覆盖冲突判定（R15-3）：当前**任一字段已设置自定义键码且与预设值不同** ⇒ 需提示，不得静默覆盖。
     *
     * 说明：一个预设同时填入上/下页两个字段，故「预设内部自冲突」不可能发生；
     * 真正会伤害用户的是「把他手改过的键码悄悄冲掉」——这正是本判据拦的场景。
     */
    fun overwritesCustomKey(preset: Preset, currentPrev: String, currentNext: String): Boolean {
        val presetPrev = toKeyString(preset.prevKeys)
        val presetNext = toKeyString(preset.nextKeys)
        val prevReplaced = currentPrev.isNotBlank() && currentPrev != presetPrev
        val nextReplaced = currentNext.isNotBlank() && currentNext != presetNext
        return prevReplaced || nextReplaced
    }

    /** 预设自身的合法性（上/下页键码不得重叠，否则同一个键会同时翻上/下页）。 */
    fun isSelfConsistent(preset: Preset): Boolean =
        preset.prevKeys.isNotEmpty() &&
            preset.nextKeys.isNotEmpty() &&
            preset.prevKeys.none { it in preset.nextKeys }
}