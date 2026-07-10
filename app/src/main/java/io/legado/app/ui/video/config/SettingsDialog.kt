package io.legado.app.ui.video.config

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
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
            // D5: 缓存容量 Spinner 下拉选择（50/100/200/500 MB，修改后需重启 App 生效）
            val cacheSizes = intArrayOf(50, 100, 200, 500)
            val cacheLabels = cacheSizes.map { getString(R.string.video_cache_size_summary, it) }
            spVideoCacheSize.adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_item, cacheLabels
            )
            spVideoCacheSize.setSelection(
                cacheSizes.indexOfFirst { it == VideoPlay.videoCacheSize }.coerceAtLeast(0)
            )
            spVideoCacheSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    VideoPlay.videoCacheSize = cacheSizes[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
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