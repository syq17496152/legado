package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEvent
import io.legado.app.utils.toastOnUi

/**
 * 朗读设置（底部弹框，View 迁移 ComposeDialogFragment + AppDialogFrame）
 */
class ReadAloudDialog() : ComposeDialogFragment() {

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    private val callBack: CallBack? get() = activity as? CallBack
    private var registeredBottomDialog = false
    private var playStateTick by mutableStateOf(0)
    private var timerProgress by mutableStateOf(if (BaseReadAloudService.timeMinute > 0) BaseReadAloudService.timeMinute else AppConfig.ttsTimer)
    private var timerTextMinute by mutableIntStateOf(BaseReadAloudService.timeMinute)
    private var ttsSpeechRate by mutableIntStateOf(AppConfig.ttsSpeechRate)
    private var ttsFollowSys by mutableStateOf(true)

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (registeredBottomDialog) {
            (activity as? ReadBookActivity)?.let { it.bottomDialog-- }
            registeredBottomDialog = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeEvent<Int>(EventBus.ALOUD_STATE) { playStateTick++ }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) {
            timerProgress = it
            timerTextMinute = it
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val readActivity = activity as? ReadBookActivity
        val bottomDialog = readActivity?.bottomDialog ?: 0
        val alreadyRegistered = registeredBottomDialog
        val shouldDismissForExistingDialog = !alreadyRegistered && bottomDialog > 0
        if (!alreadyRegistered && readActivity != null) {
            readActivity.bottomDialog = bottomDialog + 1
            registeredBottomDialog = true
        }
        ttsFollowSys = requireContext().getPrefBoolean("ttsFollowSys", true)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            if (shouldDismissForExistingDialog) {
                post { dismissAllowingStateLoss() }
            }
            setContent {
                if (!shouldDismissForExistingDialog) {
                    LegadoComposeTheme {
                        key(playStateTick) {
                            ReadAloudContent(
                                isPlaying = !BaseReadAloudService.pause,
                                isRun = BaseReadAloudService.isRun
                            )
                        }
                    }
                }
            }
        }
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    private fun showSetTimeDialog() {
        val times = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
        val timeKeys = times.map { "$it 分钟" }
        showComposeChoiceListDialog("设定时间", timeKeys) { index ->
            times.getOrNull(index)?.let { time ->
                ReadAloud.setTimer(requireContext(), time)
            }
        }
    }

    private fun changeTtsSpeechRate(value: Int) {
        val next = value.coerceIn(0, 45)
        ttsSpeechRate = next
        AppConfig.ttsSpeechRate = next
        upTtsSpeechRate()
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun finish()
    }

    @Composable
    private fun ReadAloudContent(isPlaying: Boolean, isRun: Boolean) {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val rateEnabled = !ttsFollowSys
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
        ) {
            AppDialogFrame(
                title = stringResource(R.string.read_aloud),
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.previous_chapter),
                                color = style.primaryText,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable {
                                        ReadBook.moveToPrevChapter(
                                            upContent = true,
                                            toLast = false,
                                            fromReadAloud = isRun
                                        )
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(
                                painter = painterResource(R.drawable.ic_skip_previous),
                                contentDescription = stringResource(R.string.prev_sentence),
                                tint = style.primaryText,
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(9.dp)
                                    .clickable { ReadAloud.prevParagraph(requireContext()) }
                            )
                            Icon(
                                painter = painterResource(if (isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp),
                                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.audio_play),
                                tint = style.primaryText,
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(9.dp)
                                    .clickable { callBack?.onClickReadAloud() }
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_stop_black_24dp),
                                contentDescription = stringResource(R.string.stop),
                                tint = style.primaryText,
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(9.dp)
                                    .clickable {
                                        ReadAloud.stop(requireContext())
                                        dismissAllowingStateLoss()
                                    }
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_skip_next),
                                contentDescription = stringResource(R.string.next_sentence),
                                tint = style.primaryText,
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(9.dp)
                                    .clickable { ReadAloud.nextParagraph(requireContext()) }
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = stringResource(R.string.next_chapter),
                                color = style.primaryText,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable {
                                        ReadBook.moveToNextChapter(true, fromReadAloud = isRun)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_time_add_24dp),
                                contentDescription = stringResource(R.string.set_timer),
                                tint = style.primaryText,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clickable {
                                        AppConfig.ttsTimer = timerProgress
                                        toastOnUi("保存设定时间成功！")
                                    }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            AppThemedStepperSlider(
                                value = timerProgress.coerceIn(0, 180),
                                range = 0..180,
                                onValueChange = {
                                    timerProgress = it
                                    timerTextMinute = it
                                },
                                palette = palette,
                                onValueChangeFinished = {
                                    ReadAloud.setTimer(requireContext(), timerProgress.coerceIn(0, 180))
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = requireContext().getString(R.string.timer_m, timerTextMinute.coerceAtLeast(0)),
                                color = style.primaryText,
                                modifier = Modifier.clickable { showSetTimeDialog() }
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ttsFollowSys = !ttsFollowSys
                                    AppConfig.ttsFlowSys = ttsFollowSys
                                    upTtsSpeechRate()
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.read_aloud_speed),
                                color = style.secondaryText,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize
                            )
                            if (rateEnabled) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = ((ttsSpeechRate + 5) / 10f).toString(),
                                    color = style.primaryText,
                                    fontSize = MaterialTheme.typography.bodyMedium.fontSize
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = stringResource(R.string.flow_sys),
                                color = style.primaryText,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            LegadoMiuixSwitch(
                                checked = ttsFollowSys,
                                onCheckedChange = { checked ->
                                    ttsFollowSys = checked
                                    AppConfig.ttsFlowSys = checked
                                    upTtsSpeechRate()
                                },
                                palette = palette
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_reduce),
                                contentDescription = stringResource(R.string.tts_speech_reduce),
                                tint = style.primaryText,
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(9.dp)
                                    .clickable(enabled = rateEnabled) {
                                        changeTtsSpeechRate(AppConfig.ttsSpeechRate - 1)
                                    }
                            )
                            AppThemedStepperSlider(
                                value = ttsSpeechRate.coerceIn(0, 45),
                                range = 0..45,
                                onValueChange = { ttsSpeechRate = it },
                                palette = palette,
                                enabled = rateEnabled,
                                onValueChangeFinished = {
                                    val next = ttsSpeechRate.coerceIn(0, 45)
                                    ttsSpeechRate = next
                                    AppConfig.ttsSpeechRate = next
                                    upTtsSpeechRate()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.tts_speech_add),
                                tint = style.primaryText,
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(9.dp)
                                    .clickable(enabled = rateEnabled) {
                                        changeTtsSpeechRate(AppConfig.ttsSpeechRate + 1)
                                    }
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            ReadAloudAction(
                                iconRes = R.drawable.ic_toc,
                                text = stringResource(R.string.chapter_list),
                                onClick = { callBack?.openChapterList() }
                            )
                            ReadAloudAction(
                                iconRes = R.drawable.ic_menu,
                                text = stringResource(R.string.main_menu),
                                onClick = {
                                    callBack?.showMenuBar()
                                    dismissAllowingStateLoss()
                                }
                            )
                            ReadAloudAction(
                                iconRes = R.drawable.ic_visibility_off,
                                text = stringResource(R.string.to_backstage),
                                onClick = { callBack?.finish() }
                            )
                            ReadAloudAction(
                                iconRes = R.drawable.ic_settings,
                                text = stringResource(R.string.setting),
                                onClick = {
                                    ReadAloudConfigDialog()
                                        .show(childFragmentManager, "readAloudConfigDialog")
                                }
                            )
                        }
                    }
                },
                actions = {}
            )
        }
    }

    @Composable
    private fun ReadAloudAction(
        iconRes: Int,
        text: String,
        onClick: () -> Unit
    ) {
        val style = rememberAppDialogStyle()
        Column(
            modifier = Modifier
                .width(60.dp)
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = text,
                tint = style.primaryText,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = text,
                color = style.primaryText,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
