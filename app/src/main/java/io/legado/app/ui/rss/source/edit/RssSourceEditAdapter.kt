package io.legado.app.ui.rss.source.edit

import android.annotation.SuppressLint
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.toArgb
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.ItemSourceEditBinding
import io.legado.app.databinding.ItemSourceEditCheckBoxBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.ui.widget.compose.AppSemanticColors
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.isTrue

class RssSourceEditAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val editEntityMaxLine = AppConfig.sourceEditMaxLine

    var editEntities: ArrayList<EditEntity> = ArrayList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // 当前源类型：type=2 为视频源，仅视频源显示 textVideoOnly 类型的项
    var currentSourceType: Int = 0

    // 过滤后的可见列表：当 currentSourceType != 2 时，隐藏 textVideoOnly 类型项
    private val visibleEntities: List<EditEntity>
        get() = if (currentSourceType == 2) {
            editEntities
        } else {
            editEntities.filter { it.viewType != EditEntity.ViewType.textVideoOnly }
        }

    override fun getItemViewType(position: Int): Int {
        return visibleEntities[position].viewType
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            EditEntity.ViewType.checkBox -> {
                val binding = ItemSourceEditCheckBoxBinding
                    .inflate(LayoutInflater.from(parent.context), parent, false)
                CheckBoxViewHolder(binding)
            }

            else -> {
                val binding = ItemSourceEditBinding
                    .inflate(LayoutInflater.from(parent.context), parent, false)
                binding.editText.addLegadoPattern()
                binding.editText.addJsonPattern()
                binding.editText.addJsPattern()
                EditTextViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CheckBoxViewHolder -> holder.bind(visibleEntities[position])
            is EditTextViewHolder -> holder.bind(visibleEntities[position])
        }
    }

    override fun getItemCount(): Int {
        return visibleEntities.size
    }

    /**
     * F155：按字段 key 查可见列表下标（保存校验失败时用于滚动定位）。
     *
     * @return 可见列表下标；-1 表示该 key 当前不可见（如视频源专属项被过滤）
     */
    fun indexOfKey(key: String): Int = visibleEntities.indexOfFirst { it.key == key }

    /**
     * F155：必填标记（label 前置 `*`，danger 语义色）。
     *
     * 取色走 [AppSemanticColors] 单源（AD-14），禁止页内写死色值。
     */
    private fun hintWithRequired(hint: String): CharSequence {
        return SpannableString("* $hint").apply {
            setSpan(
                ForegroundColorSpan(AppSemanticColors.Danger.toArgb()),
                0,
                1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    inner class EditTextViewHolder(val binding: ItemSourceEditBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(editEntity: EditEntity) = binding.run {
            editText.maxLines = editEntityMaxLine
            if (editText.getTag(R.id.tag1) == null) {
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        editText.isCursorVisible = false
                        editText.isCursorVisible = true
                        editText.isFocusable = true
                        editText.isFocusableInTouchMode = true
                    }

                    override fun onViewDetachedFromWindow(v: View) {

                    }
                }
                editText.addOnAttachStateChangeListener(listener)
                editText.setTag(R.id.tag1, listener)
            }
            editText.getTag(R.id.tag2)?.let {
                if (it is TextWatcher) {
                    editText.removeTextChangedListener(it)
                }
            }
            editText.setText(editEntity.value)
            // F155：必填项 label 前置 danger 色 `*`
            textInputLayout.hint =
                if (editEntity.required) hintWithRequired(editEntity.hint) else editEntity.hint
            // F155：字段级错误态（保存校验失败后由页面写入；用户开始修正即撤下）
            if (editEntity.error != null) {
                textInputLayout.error = editEntity.error
            } else if (textInputLayout.isErrorEnabled) {
                textInputLayout.error = null
                textInputLayout.isErrorEnabled = false
            }
            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int
                ) {

                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {

                }

                override fun afterTextChanged(s: Editable?) {
                    editEntity.value = (s?.toString())
                    // F155：用户开始修正即撤下错误态（避免红框一直挂着）
                    if (editEntity.error != null) {
                        editEntity.error = null
                        textInputLayout.error = null
                        textInputLayout.isErrorEnabled = false
                    }
                }
            }
            editText.addTextChangedListener(textWatcher)
            editText.setTag(R.id.tag2, textWatcher)
            editText.clearFocus()
        }
    }

    class CheckBoxViewHolder(val binding: ItemSourceEditCheckBoxBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(editEntity: EditEntity) = binding.run {
            checkBox.text = editEntity.hint
            checkBox.isChecked = editEntity.value.isTrue()
            checkBox.setOnUserCheckedChangeListener { isChecked ->
                editEntity.value = isChecked.toString()
            }
        }

    }


}