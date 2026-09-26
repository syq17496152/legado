package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * Provider 余额查询结果弹框（Q-N3）。
 *
 * 只呈现「供应商名 + 余额数值」；取不到数值时显示失败说明（由调用方传入文案），
 * 避免把技术错误信息直接暴露成用户可读文案。
 */
class AiBalanceResultDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        val providerName = args.getString(ARG_PROVIDER).orEmpty()
        val errorText = args.getString(ARG_ERROR)
        val amount = args.getDouble(ARG_AMOUNT, Double.NaN)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                AppDialogFrame(
                    title = stringResource(R.string.ai_balance_result_title),
                    content = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = providerName,
                                color = style.secondaryText,
                                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                fontFamily = style.bodyFontFamily
                            )
                            if (errorText != null) {
                                Text(
                                    text = errorText,
                                    color = style.primaryText,
                                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                    fontFamily = style.bodyFontFamily
                                )
                            } else {
                                Text(
                                    text = stringResource(
                                        R.string.ai_balance_amount,
                                        formatAmount(amount)
                                    ),
                                    color = style.primaryText,
                                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = style.titleFontFamily
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(
                                        if (amount > 0.0) R.string.ai_balance_available
                                        else R.string.ai_balance_exhausted
                                    ),
                                    color = if (amount > 0.0) style.accent else style.secondaryText,
                                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                    fontFamily = style.bodyFontFamily
                                )
                            }
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.close),
                            palette = palette,
                            primary = true,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    companion object {
        private const val ARG_PROVIDER = "providerName"
        private const val ARG_AMOUNT = "amount"
        private const val ARG_ERROR = "error"

        fun create(providerName: String, amount: Double): AiBalanceResultDialog =
            create(providerName, amount, null)

        fun create(providerName: String, errorText: String): AiBalanceResultDialog =
            create(providerName, Double.NaN, errorText)

        private fun create(
            providerName: String,
            amount: Double,
            errorText: String?
        ): AiBalanceResultDialog {
            return AiBalanceResultDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PROVIDER, providerName)
                    putDouble(ARG_AMOUNT, amount)
                    putString(ARG_ERROR, errorText)
                }
            }
        }

        /** 最多保留 4 位小数且去掉尾随 0（余额多为小数，整数时不显示小数点；固定 US 小数点，避免区域化逗号） */
        internal fun formatAmount(amount: Double): String {
            if (amount.isNaN()) return "-"
            val rounded = kotlin.math.round(amount * 10_000) / 10_000
            return if (rounded == kotlin.math.floor(rounded)) {
                rounded.toLong().toString()
            } else {
                String.format(java.util.Locale.US, "%.4f", rounded)
                    .trimEnd('0')
                    .trimEnd('.')
            }
        }
    }
}