package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookParagraphRule
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ParagraphRuleQuickDialog : ReaderBottomSheetComposeDialogFragment() {

    override val maxSheetHeightFraction: Float = 0.66f

    private val bookUrl: String
        get() = requireArguments().getString(ARG_BOOK_URL).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ParagraphRuleQuickContent()
            }
        }
    }

    @Composable
    private fun ParagraphRuleQuickContent() {
        var rules by remember { mutableStateOf<List<ParagraphRule>>(emptyList()) }
        var enabledIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        LaunchedEffect(bookUrl) {
            val (loadedRules, loadedIds) = withContext(Dispatchers.IO) {
                appDb.paragraphRuleDao.all() to
                        appDb.paragraphRuleDao.enabledRuleIdsForBook(bookUrl).toSet()
            }
            rules = loadedRules
            enabledIds = loadedIds
        }
        ReaderBottomSheetFrame(maxHeightFraction = maxSheetHeightFraction) { style ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ReaderSheetHeader(
                    title = stringResource(R.string.paragraph_rule),
                    style = style,
                    trailing = {
                        ReaderTextAction(
                            text = stringResource(R.string.paragraph_rule_manage),
                            style = style,
                            onClick = ::openManage
                        )
                    }
                )
                ReaderSectionCard(
                    style = style,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        itemsIndexed(
                            items = rules,
                            key = { _, rule -> rule.id }
                        ) { index, rule ->
                            val checked = rule.id in enabledIds
                            ParagraphRuleRow(
                                rule = rule,
                                checked = checked,
                                style = style,
                                onCheckedChange = { next ->
                                    enabledIds = if (next) {
                                        enabledIds + rule.id
                                    } else {
                                        enabledIds - rule.id
                                    }
                                    updateRuleEnabled(rule, next, index)
                                },
                                onLogin = { openLogin(rule) }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ParagraphRuleRow(
        rule: ParagraphRule,
        checked: Boolean,
        style: AppDialogStyle,
        onCheckedChange: (Boolean) -> Unit,
        onLogin: () -> Unit
    ) {
        val hasLogin = rule.loginUrl.isNotBlank() || rule.loginUi.isNotBlank()
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) },
            shape = RoundedCornerShape(style.actionRadius),
            color = if (checked) style.accent.copy(alpha = 0.14f) else style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = rule.displayName(),
                    color = if (checked) style.accent else style.primaryText,
                    fontSize = 14.sp,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (hasLogin) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        modifier = Modifier
                            .heightIn(min = 30.dp)
                            .clickable(onClick = onLogin),
                        shape = RoundedCornerShape(style.actionRadius),
                        color = style.fieldSurface,
                        contentColor = style.accent,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.login),
                                color = style.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                LegadoMiuixSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    palette = style.toMiuixPalette()
                )
            }
        }
    }

    private fun updateRuleEnabled(rule: ParagraphRule, checked: Boolean, order: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (checked) {
                    appDb.paragraphRuleDao.insertBookRule(
                        BookParagraphRule(bookUrl, rule.id, true, order)
                    )
                } else {
                    appDb.paragraphRuleDao.deleteBookRule(bookUrl, rule.id)
                }
            }
            (activity as? ReadBookActivity)?.refreshParagraphRuleLayout()
        }
    }

    private fun openLogin(rule: ParagraphRule) {
        if (rule.loginUrl.isBlank() && rule.loginUi.isBlank()) {
            toastOnUi(R.string.source_no_login)
            return
        }
        startActivity<SourceLoginActivity> {
            putExtra("bookType", -1)
            putExtra("type", "paragraphRule")
            putExtra("key", rule.id.toString())
            putExtra("bookUrl", bookUrl)
            putExtra("chapterIndex", ReadBook.durChapterIndex)
        }
    }

    private fun openManage() {
        startActivity<ParagraphRuleManageActivity> {
            putExtra("bookUrl", bookUrl)
        }
    }

    companion object {
        private const val ARG_BOOK_URL = "bookUrl"

        fun create(bookUrl: String): ParagraphRuleQuickDialog {
            return ParagraphRuleQuickDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_BOOK_URL, bookUrl)
                }
            }
        }
    }
}
