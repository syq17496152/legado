package io.legado.app.help.readaloud.casting

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * F8/2.26+2.27：多角色朗读切分诊断（面板可见化）。
 *
 * 背景（真机日志实锤）：多角色激活后 33 段中 32 段 narration 仅 1 dialogue——切分几乎全不命中
 * 且全链降级只写 AppLog，用户无感知无引导。本对象把以下状态上抛到播放面板：
 * - 切分统计：narration/dialogue 段计数
 * - 引擎默认音段计数（voiceId 空/未命中/非系统通道降级）
 * - "本章无对话"（文本无引号字符）与"规则未命中"（有引号但零 dialogue 命中）区分，防误标
 *
 * 服务会话级状态：章开始 reset、逐段 count、停止时置 inactive。面板收集 [state] 渲染提示条。
 */
object TtsMultiRoleDiagnostics {

    data class State(
        val active: Boolean = false,
        val chapterIndex: Int = 0,
        val narrationCount: Int = 0,
        val dialogueCount: Int = 0,
        /** 引擎默认音段数（未绑定音色/未命中/非系统通道降级） */
        val defaultVoiceCount: Int = 0,
        /** 本章文本是否含对话引号字符（区分"无对话"与"规则未命中"） */
        val chapterHasQuotes: Boolean = false,
        val dismissed: Boolean = false
    ) {
        /** 引导文案：null=不展示。优先级：默认音过多 > 疑似规则未命中 > 纯统计（不展示）。 */
        val hintText: String? get() {
            if (!active || dismissed) return null
            if (defaultVoiceCount > 0 && narrationCount + dialogueCount > 0) {
                return "本段 $defaultVoiceCount 处使用引擎默认音（未绑定音色或未命中），可到 朗读设置→选角模板 绑定具体音色"
            }
            if (dialogueCount == 0 && narrationCount >= 3) {
                return if (chapterHasQuotes) {
                    "检测到对话引号但未被对白规则命中，请检查模板对白规则"
                } else {
                    // 纯旁白章：仅统计展示（不引导，防误标）
                    null
                }
            }
            return null
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    /** 章开始：重置计数并标记本章是否含引号字符 */
    fun reset(chapterIndex: Int, chapterHasQuotes: Boolean) {
        _state.value = State(
            active = true,
            chapterIndex = chapterIndex,
            chapterHasQuotes = chapterHasQuotes
        )
    }

    /** 逐段计数（tag=NARRATION 计旁白，其余计对白；voiceMiss=该段使用引擎默认音） */
    fun countSegment(isNarration: Boolean, voiceMiss: Boolean) {
        val cur = _state.value
        _state.value = cur.copy(
            narrationCount = if (isNarration) cur.narrationCount + 1 else cur.narrationCount,
            dialogueCount = if (isNarration) cur.dialogueCount else cur.dialogueCount + 1,
            defaultVoiceCount = if (voiceMiss) cur.defaultVoiceCount + 1 else cur.defaultVoiceCount
        )
    }

    /** 用户关闭提示（本次朗读会话不再弹出） */
    fun dismiss() {
        _state.value = _state.value.copy(dismissed = true)
    }

    /** 朗读停止/退出多人路径 */
    fun stop() {
        _state.value = _state.value.copy(active = false)
    }
}
