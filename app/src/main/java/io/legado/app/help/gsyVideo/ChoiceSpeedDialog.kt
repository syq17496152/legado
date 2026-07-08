package io.legado.app.help.gsyVideo

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.legado.app.R

class ChoiceSpeedDialog(private val mContext: Context) : Dialog(
    mContext, R.style.dialog_style
) {
    private var listView: ListView? = null
    private var adapter: SpeedAdapter? = null
    private var onItemClickListener: OnListItemClickListener? = null
    private var data: List<Float>? = null

    interface OnListItemClickListener {
        fun onItemClick(value: Float)
        fun finishDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onStop() {
        onItemClickListener!!.finishDialog()
        super.onStop()
    }

    /**
     * 初始化倍速列表
     * @param data 倍速值列表（显示顺序，如 reversed 后的 [15.0, 10.0, ..., 0.5]）
     * @param onItemClickListener 点击回调
     * @param currentSpeed 当前播放倍速，用于高亮选中项（默认 1.0f，向后兼容）
     */
    fun initList(
        data: List<Float>,
        onItemClickListener: OnListItemClickListener,
        currentSpeed: Float = 1.0f
    ) {
        this.onItemClickListener = onItemClickListener
        this.data = data
        val inflater = LayoutInflater.from(mContext)
        val view: View = inflater.inflate(R.layout.switch_speed_video_dialog, null)
        listView = view.findViewById(R.id.switch_dialog_list)
        setContentView(view)
        val displayData = buildDisplayData(data)
        adapter = SpeedAdapter(mContext, displayData, currentSpeed)
        listView!!.adapter = adapter
        listView!!.onItemClickListener = this@ChoiceSpeedDialog.OnItemClickListener()
        val dialogWindow = window
        val lp = dialogWindow!!.attributes
        val d = mContext.resources.displayMetrics
        lp.width = (d.widthPixels * 0.34).toInt()
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        dialogWindow.setAttributes(lp)
    }

    /**
     * 构造显示数据：在极速区(>=5.0)与常用区(<5.0)之间插入 SEPARATOR 标记
     * 数据顺序为显示顺序（已 reversed，顶部最大）。
     */
    private fun buildDisplayData(data: List<Float>): List<Any> {
        val result = ArrayList<Any>(data.size + 1)
        var inserted = false
        for (value in data) {
            if (!inserted && value < 5.0f) {
                result.add(SEPARATOR)
                inserted = true
            }
            result.add(value)
        }
        return result
    }

    private inner class OnItemClickListener : AdapterView.OnItemClickListener {
        override fun onItemClick(
            adapterView: AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            val item = adapter?.getItem(position)
            if (item === SEPARATOR) return
            dismiss()
            onItemClickListener!!.onItemClick(item as? Float ?: 1.0f)
        }
    }

    /**
     * 倍速列表适配器（ChoiceSpeedDialog 专用，不影响 SwitchVideoAdapter/ChoiceEpisodeDialog）
     * - 支持分隔项 [SEPARATOR]
     * - 支持当前倍速高亮（主题色 primary + 加粗 + 浅色背景）
     * - 倍速文本格式化：1.0→"1x"，15.0→"15x"，1.25→"1.25x"
     */
    private inner class SpeedAdapter(
        context: Context,
        private val displayData: List<Any>,
        private val currentSpeed: Float
    ) : ArrayAdapter<Any>(context, 0, displayData) {

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val item = displayData[position]
            return when {
                item === SEPARATOR -> {
                    // 分隔项：复用 convertView 时需确认类型匹配
                    val view = if (convertView?.tag == TAG_SEPARATOR) convertView
                        else LayoutInflater.from(context)
                            .inflate(R.layout.switch_video_dialog_item_separator, parent, false)
                            .also { it.tag = TAG_SEPARATOR }
                    view
                }
                else -> {
                    // 普通倍速项：复用 convertView 时需确认类型匹配
                    val view = if (convertView?.tag == TAG_NORMAL) convertView
                        else LayoutInflater.from(context)
                            .inflate(R.layout.speed_dialog_item, parent, false)
                            .also { it.tag = TAG_NORMAL }
                    val textView = view.findViewById<TextView>(R.id.text1)
                    val value = item as Float
                    textView.text = formatSpeed(value)
                    if (value == currentSpeed) {
                        textView.setTextColor(ContextCompat.getColor(context, R.color.primary))
                        textView.setTypeface(textView.typeface, Typeface.BOLD)
                        textView.setBackgroundColor(0x330277BD)
                    } else {
                        textView.setTextColor(0xFFFFFFFF.toInt())
                        textView.setTypeface(textView.typeface, Typeface.NORMAL)
                        textView.background =
                            ContextCompat.getDrawable(context, R.drawable.card_video_background)
                    }
                    view
                }
            }
        }

        private fun formatSpeed(value: Float): String {
            return if (value == value.toInt().toFloat()) "${value.toInt()}x" else "${value}x"
        }
    }

    companion object {
        private val SEPARATOR = Any()
        private const val TAG_NORMAL = "normal"
        private const val TAG_SEPARATOR = "separator"
    }
}
