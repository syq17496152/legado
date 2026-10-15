package io.legado.app.ui.main.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import io.legado.app.utils.ColorUtils
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.compose.BookCoverImage as ComposeBookCoverImage
import io.legado.app.ui.widget.image.CoverImageView

/**
 * 封面：**Compose 原生位图渲染**（同 `compose/BookshelfComposeCover` 一条路）。
 *
 * 2026-10-15 改（真机量测见 issues-found NEW-18）：原实现是 `AndroidView + ImageView`，
 * 即**每个可见书目项都要创建一个传统 View**，并在滚动进出屏幕时反复创建/销毁 —— 在懒加载网格里
 * 这是持续的重负担（同机同手势对照：书架 90 分位 73ms / 慢 UI 线程 234 帧，而 Compose 对照屏
 * 仅 5ms / 1 帧）。改为调用既有的原生实现后，滚动不再有 View 互操作开销。
 *
 * 视觉与调用方语义保持不变：`fillBounds = true`（沿用父级给定尺寸，不额外套 aspectRatio）、
 * `preferThumb = true`（用缩略图规格解码，与小尺寸网格项匹配）、`forcePath = false`
 * （尊重"使用默认封面"设置，与旧实现一致）。
 */
@Composable
internal fun BookCoverImage(
    book: Book,
    modifier: Modifier = Modifier,
) {
    ComposeBookCoverImage(
        path = book.getDisplayCover(),
        name = book.name,
        author = book.author,
        sourceOrigin = book.origin,
        modifier = modifier,
        style = CoverImageView.CoverStyle.GRID,
        preferThumb = true,
        forcePath = false,
        fillBounds = true,
    )
}

/**
 * 无封面书生成的渐变封面（对标墨境 MoRealm GeneratedCover）：
 * 8 色调色板取稳定色 + 居中格式图标 + 右上角「本地/在线」徽章。
 */
private val coverColorPalette = listOf(
    Color(0xFF37474F), // Blue Grey 800
    Color(0xFF455A64), // Blue Grey 700
    Color(0xFF4E342E), // Brown 800
    Color(0xFF5D4037), // Brown 700
    Color(0xFF424242), // Grey 800
    Color(0xFF546E7A), // Blue Grey 600
    Color(0xFF3E2723), // Brown 900
    Color(0xFF616161), // Grey 700
)

internal fun coverColorForBook(book: Book): Color {
    val key = book.name.replace(Regex("[\\d\\s._\\-]+$"), "").ifBlank { book.name }
    val hash = key.hashCode().let { if (it < 0) -it else it }
    return coverColorPalette[hash % coverColorPalette.size]
}

private fun coverFormatIcon(book: Book): ImageVector = when {
    book.isVideo -> Icons.Default.PlayArrow
    book.isAudio -> Icons.Default.MusicNote
    book.isImage -> Icons.Default.Image
    book.isWebFile -> Icons.Default.Language
    book.isPdf -> Icons.Default.PictureAsPdf
    else -> if (book.isLocal) Icons.Default.Description else Icons.AutoMirrored.Filled.MenuBook
}

@Composable
internal fun GeneratedCover(
    book: Book,
    modifier: Modifier = Modifier,
    iconSize: Int = 32,
) {
    val baseColor = coverColorForBook(book)
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(baseColor, baseColor.copy(alpha = 0.7f)),
            )
        ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            coverFormatIcon(book),
            contentDescription = null,
            // 占位渐变底色亮度自适应（light-theme-contrast-fix 2.12：浅色渐变上白 tint 隐形防御）
            tint = (if (ColorUtils.isColorLight(baseColor.toArgb())) Color.Black else Color.White)
                .copy(alpha = 0.55f),
            modifier = Modifier.size(iconSize.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .background(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.shapes.small,
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = if (book.isLocal) "本地" else "在线",
                // 封面角标紧凑字号豁免（比 labelSmall 11sp 更小，刻意不纳入 Typography）
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}