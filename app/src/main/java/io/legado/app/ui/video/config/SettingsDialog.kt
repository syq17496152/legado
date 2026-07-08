package io.legado.app.ui.video.config

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogVideoSettingsBinding
import io.legado.app.model.VideoPlay
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.viewbindingdelegate.viewBinding

class SettingsDialog(private val context: Context, private val callBack: CallBack? = null) :
    BaseDialogFragment(R.layout.dialog_video_settings) {
    private val binding by viewBinding(DialogVideoSettingsBinding::bind)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
        initView()
    }

    @SuppressLint("SetTextI18n")
    private fun initData() {
        binding.run {
            tvPressSpeed.text = (VideoPlay.longPressSpeed / 10.0f).toPressSpeedStr()
            cbAutoPlay.isChecked = VideoPlay.autoPlay
            cbStartFull.isChecked = VideoPlay.startFull
            cbFullBottomProgress.isChecked = VideoPlay.fullBottomProgressBar
            tvVideoCacheSize.text = getString(
                R.string.video_cache_size_summary, VideoPlay.videoCacheSize
            )
            cbMuteOnStart.isChecked = VideoPlay.muteOnStart
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.run {
            cbAutoPlay.setOnCheckedChangeListener { _, isChecked ->
                VideoPlay.autoPlay = isChecked
                ctStartFull.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
            cbStartFull.setOnCheckedChangeListener { _, isChecked ->
                VideoPlay.startFull = isChecked
            }
            cbFullBottomProgress.setOnCheckedChangeListener { _, isChecked ->
                VideoPlay.fullBottomProgressBar = isChecked
            }
            cbMuteOnStart.setOnCheckedChangeListener { _, isChecked ->
                VideoPlay.muteOnStart = isChecked
            }
            tvPressSpeed.setOnClickListener { _ ->
                NumberPickerDialog(requireContext(), true)
                    .setTitle(getString(R.string.press_speed))
                    .setMaxValue(60)
                    .setMinValue(5)
                    .setValue(VideoPlay.longPressSpeed)
                    .setCustomButton((R.string.btn_default_s)) {
                        VideoPlay.longPressSpeed = 30
                        tvPressSpeed.text = 3.0f.toPressSpeedStr()
                    }
                    .show {
                        VideoPlay.longPressSpeed = it
                        tvPressSpeed.text = (it / 10.0f).toPressSpeedStr()
                    }
            }
            // P0-3 缓存容量选择：50/100/200/500 MB，修改后需重启 App 生效
            tvVideoCacheSize.setOnClickListener { _ ->
                val sizes = intArrayOf(50, 100, 200, 500)
                val current = VideoPlay.videoCacheSize
                val checkedIndex = sizes.indexOfFirst { it == current }.coerceAtLeast(0)
                val labels = sizes.map { getString(R.string.video_cache_size_summary, it) }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(R.string.video_cache_size)
                    .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                        VideoPlay.videoCacheSize = sizes[which]
                        tvVideoCacheSize.text = labels[which]
                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    private fun Float.toPressSpeedStr(): String {
        return context.getString(R.string.press_speed_summary, this)
    }
    interface CallBack {
//        fun upUi()
    }

}