package io.legado.app.ui.rss.search

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import io.legado.app.R
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

/**
 * 订阅源文章详情页 Compose 实现（my-compose-full W6.1，替代原 View 主体+RecyclerAdapter）。
 * 结构对齐原布局：封面区（无图/加载失败隐藏）→ 标题+信息行 → 简介 → 多源列表 → 底部操作栏。
 * 颜色全部经 AppManagementPalette 跟随主题体系，原 applyThemeColors 手动取色链删除。
 */
data class RssArticleSourceUi(
    val displayName: String,
    val origin: String
)

@Composable
fun RssArticleInfoScreen(
    title: String,
    pubDate: String,
    typeText: String,
    sourceCountText: String,
    description: String,
    coverUrl: String?,
    coverSourceOrigin: String?,
    sources: List<RssArticleSourceUi>,
    selectedOrigin: String?,
    onSourceClick: (RssArticleSourceUi) -> Unit,
    onRead: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()
    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = stringResource(R.string.rss_article_info_title),
            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = onBack
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (coverUrl != null) {
                item(key = "cover") {
                    RssArticleCover(
                        coverUrl = coverUrl,
                        sourceOrigin = coverSourceOrigin,
                        palette = palette
                    )
                }
            }
            item(key = "info") {
                RssArticleInfoHeader(
                    title = title,
                    pubDate = pubDate,
                    typeText = typeText,
                    sourceCountText = sourceCountText,
                    description = description,
                    palette = palette
                )
            }
            item(key = "sources_label") {
                Text(
                    text = stringResource(R.string.rss_all_sources),
                    color = palette.settings.secondaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
                )
            }
            items(sources, key = { it.origin }) { source ->
                RssArticleSourceRow(
                    source = source,
                    selected = source.origin == selectedOrigin,
                    palette = palette,
                    onClick = { onSourceClick(source) }
                )
            }
            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        RssArticleBottomBar(
            palette = palette,
            onRead = onRead,
            onCancel = onBack
        )
    }
}

@Composable
private fun RssArticleInfoHeader(
    title: String,
    pubDate: String,
    typeText: String,
    sourceCountText: String,
    description: String,
    palette: AppManagementPalette
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
    ) {
        Text(
            text = title,
            color = palette.settings.primaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        InfoRow(
            iconRes = R.drawable.ic_book_last,
            iconDesc = stringResource(R.string.rss_article_info_pub_date),
            text = pubDate,
            palette = palette,
            topPadding = 10.dp
        )
        InfoRow(
            iconRes = R.drawable.ic_web_outline,
            iconDesc = stringResource(R.string.rss_article_type),
            text = typeText,
            palette = palette
        )
        InfoRow(
            iconRes = R.drawable.ic_groups,
            iconDesc = stringResource(R.string.rss_source_count),
            text = sourceCountText,
            palette = palette
        )
        HorizontalDivider(
            color = palette.settings.divider,
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
        )
        Text(
            text = stringResource(R.string.book_intro),
            color = palette.settings.secondaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = description,
            color = palette.settings.primaryText,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        HorizontalDivider(
            color = palette.settings.divider,
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
        )
    }
}

@Composable
private fun InfoRow(
    iconRes: Int,
    iconDesc: String,
    text: String,
    palette: AppManagementPalette,
    topPadding: androidx.compose.ui.unit.Dp = 4.dp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = topPadding, bottom = 4.dp)
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(iconRes),
            contentDescription = iconDesc,
            tint = palette.settings.secondaryText,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            color = palette.settings.secondaryText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp)
        )
    }
}

/**
 * 封面图：Glide 加载（带 sourceOrigin 供 OkHttpModelLoader 注入 referer/cookie），
 * fitCenter 语义（对齐原 AppCompatImageView），加载失败时整块隐藏。
 */
@Composable
private fun RssArticleCover(
    coverUrl: String,
    sourceOrigin: String?,
    palette: AppManagementPalette
) {
    val context = LocalContext.current
    var bitmap by remember(coverUrl) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(coverUrl) { mutableStateOf(false) }

    DisposableEffect(coverUrl, sourceOrigin) {
        var active = true
        var options = RequestOptions().diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (active && !resource.isRecycled) {
                    bitmap = resource
                }
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (active) {
                    loadFailed = true
                }
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                if (active) {
                    bitmap = null
                }
            }
        }
        ImageLoader.loadBitmap(context, coverUrl)
            .apply(options)
            .placeholder(R.drawable.image_rss_article)
            .addListener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap>,
                    isFirstResource: Boolean
                ): Boolean {
                    // 加载失败回调由 CustomTarget.onLoadFailed 承接，隐藏封面区
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any,
                    target: Target<Bitmap>?,
                    dataSource: com.bumptech.glide.load.DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    return false
                }
            })
            .into(target)
        onDispose {
            active = false
            runCatching { Glide.with(context.applicationContext).clear(target) }
        }
    }

    if (bitmap != null && !loadFailed) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .height(220.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(palette.settings.row)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = stringResource(R.string.img_cover),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun RssArticleSourceRow(
    source: RssArticleSourceUi,
    selected: Boolean,
    palette: AppManagementPalette,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (selected) {
            androidx.compose.material3.Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = palette.settings.accent,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Spacer(modifier = Modifier.width(18.dp))
        }
        Column(modifier = Modifier.padding(start = 6.dp)) {
            Text(
                text = source.displayName,
                color = if (selected) palette.settings.accent else palette.settings.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = source.origin,
                color = palette.settings.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RssArticleBottomBar(
    palette: AppManagementPalette,
    onRead: () -> Unit,
    onCancel: () -> Unit
) {
    HorizontalDivider(color = palette.settings.divider)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Text(
            text = stringResource(R.string.cancel),
            color = palette.settings.primaryText,
            fontSize = 15.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onCancel)
                .padding(vertical = 15.dp)
        )
        Text(
            text = stringResource(R.string.reading),
            color = palette.settings.accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onRead)
                .padding(vertical = 15.dp)
        )
    }
}
