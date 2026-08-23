package io.legado.app.ui.about

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.CoverCollectionManager.isRealCoverPath
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiInputStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.applyUiSubtleButtonStyle
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.BookCover
import io.legado.app.help.glide.ImageLoader
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi

fun Context.openReadRecordBook(
    book: io.legado.app.data.entities.Book?,
    fallbackName: String? = null
) {
    if (book == null) {
        fallbackName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            SearchActivity.start(this, it)
            return
        }
        toastOnUi(getString(R.string.read_record_goal_open_missing))
        return
    }
    startActivityForBook(book)
}

fun Context.openReadRecordBookInfo(
    book: Book?,
    fallbackName: String? = null
) {
    if (book == null) {
        fallbackName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            SearchActivity.start(this, it)
            return
        }
        toastOnUi(getString(R.string.read_record_goal_open_missing))
        return
    }
    BookInfoNavigator.open(this, book)
}

fun Context.showReadRecordBookActionDialog(
    title: String,
    book: Book?,
    fallbackName: String? = null,
    onDeleteRecord: () -> Unit
) {
    alert(title) {
        items(
            listOf(
                getString(R.string.read_record_open_book_info),
                getString(R.string.read_record_delete_entry)
            )
        ) { _, _, index ->
            when (index) {
                0 -> openReadRecordBookInfo(book, fallbackName)
                1 -> onDeleteRecord()
            }
        }
    }
}

fun ImageView.loadReadRecordCover(path: String?) {
    BookCover.load(context, path).into(this)
}

fun CoverImageView.loadReadRecordCover(
    book: Book?,
    snapshot: ReadRecentVisualSnapshot?,
    fallbackPath: String? = null
) {
    if (book != null) {
        load(book)
        return
    }
    val originalCover = snapshot?.displayCover() ?: fallbackPath
    val bookKey = snapshot?.bookUrl
        ?.takeIf { it.isNotBlank() }
        ?: listOf(snapshot?.name, snapshot?.author).joinToString("|")
    val collectionCover = CoverCollectionManager.selectedCollectionCover(bookKey, originalCover)
    val usingCollectionCover = collectionCover != null
    val forceOriginalCover = collectionCover == null &&
        CoverCollectionManager.isMixedMode() &&
        originalCover.isRealCoverPath()
    load(
        path = collectionCover ?: originalCover,
        name = snapshot?.name,
        author = snapshot?.author,
        forcePath = usingCollectionCover || forceOriginalCover,
        allowNameOverlay = usingCollectionCover || !originalCover.isRealCoverPath()
    )
}

fun ImageView.loadReadRecordAvatar(path: String?) {
    ImageLoader.load(context, path)
        .placeholder(R.drawable.ic_read_record_default_avatar)
        .error(R.drawable.ic_read_record_default_avatar)
        .centerCrop()
        .into(this)
}

object ReadRecordRankDialog {
    fun show(
        context: Context,
        items: List<ReadRecordRankItem>,
        formatDuring: (Long) -> String,
        onDeleteRecord: ((ReadRecordRankItem) -> Unit)? = null
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 8.dpToPx())
            addView(
                androidx.appcompat.widget.AppCompatTextView(context).apply {
                    text = context.getString(R.string.read_record_read_rank)
                    applyUiSectionTitleStyle(context)
                    textSize = 18f
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                ComposeView(context).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        LegadoComposeTheme {
                            Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                                ReadRecordRankDialogContent(
                                    items = items,
                                    formatDuring = formatDuring,
                                    onClick = {
                                        context.openReadRecordBook(it.book, it.displayName)
                                    },
                                    onLongClick = { item, removeItem ->
                                        context.showReadRecordBookActionDialog(
                                            title = item.book?.name ?: item.snapshot?.name ?: item.displayName,
                                            book = item.book,
                                            fallbackName = item.displayName
                                        ) {
                                            onDeleteRecord?.invoke(item)
                                            removeItem()
                                        }
                                    },
                                    modifier = Modifier.height(420.dp)
                                )
                            }
                        }
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    420.dpToPx()
                ).apply {
                    topMargin = 14.dpToPx()
                }
            )
        }
        AlertDialog.Builder(context)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .applyTint()
            .show()
    }
}

fun Context.showReadRecordGoalDialog(
    initial: ReadRecordGoalConfig,
    onPickAvatarRequest: (((String) -> Unit) -> Unit)? = null,
    onSave: (ReadRecordGoalConfig) -> Unit
) {
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 0)
    }
    container.applyUiBodyTypefaceDeep(uiTypeface())
    val userNameInput = EditText(this).apply {
        hint = getString(R.string.read_record_goal_user_name_hint)
        setText(initial.userName.orEmpty())
        inputType = InputType.TYPE_CLASS_TEXT
        applyUiInputStyle(this@showReadRecordGoalDialog)
    }
    val avatarInput = EditText(this).apply {
        hint = getString(R.string.read_record_goal_avatar_hint)
        setText(initial.avatar.orEmpty())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        applyUiInputStyle(this@showReadRecordGoalDialog)
        maxLines = 2
    }
    val avatarButton = Button(this).apply {
        text = getString(R.string.read_record_goal_avatar_pick)
        applyUiSubtleButtonStyle(this@showReadRecordGoalDialog)
        setOnClickListener {
            onPickAvatarRequest?.invoke { value ->
                avatarInput.setText(value)
                avatarInput.setSelection(avatarInput.text?.length ?: 0)
            }
        }
    }
    val goalInput = EditText(this).apply {
        hint = getString(R.string.read_record_goal_minutes)
        setText(initial.dailyGoalMinutes.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
        applyUiInputStyle(this@showReadRecordGoalDialog)
    }
    fun sectionTitle(textRes: Int) =
        androidx.appcompat.widget.AppCompatTextView(this).apply {
            text = getString(textRes)
            applyUiSectionTitleStyle(this@showReadRecordGoalDialog)
        }
    container.addView(sectionTitle(R.string.read_record_goal_user_name))
    container.addView(userNameInput)
    container.addView(sectionTitle(R.string.read_record_goal_avatar).apply {
        setPadding(0, 14.dpToPx(), 0, 0)
    })
    container.addView(
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(
                avatarInput,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 10.dpToPx()
                }
            )
            addView(
                avatarButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    )
    container.addView(sectionTitle(R.string.read_record_goal_target).apply {
        setPadding(0, 14.dpToPx(), 0, 0)
    })
    container.addView(goalInput)
    AlertDialog.Builder(this)
        .setTitle(R.string.read_record_goal_card)
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val minutes = goalInput.text?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 120
            onSave(
                ReadRecordGoalConfig(
                    userName = userNameInput.text?.toString()?.trim().orEmpty().ifBlank { null },
                    avatar = avatarInput.text?.toString()?.trim().orEmpty().ifBlank { null },
                    dailyGoalMinutes = minutes
                )
            )
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .apply {
            setOnShowListener { applyTint() }
        }
        .show()
}

fun buildReadRecordPreviewBackground(context: Context, weight: Float = 1f): GradientDrawable {
    return UiCorner.rounded(
        ColorUtils.adjustAlpha(context.themeMutedColorOrDefault(), 0.92f),
        UiCorner.panelRadius(context) * weight.coerceAtLeast(0.8f)
    )
}
