package io.legado.app.ui.image.adapter

import android.content.Context

/**
 * 全屏横向浏览适配器（V4 实施 Phase 3.1，design.md AD-05）
 *
 * 与 ImageDetailAdapter 的关系：
 * - 继承复用全部能力：自研 PhotoView 双指缩放/双击缩放/旋转/平移、原图加载、
 *   长按保存/分享菜单回调、单击沉浸式回调、预加载下一张到磁盘缓存
 * - 数据源同为 ImagePlay.allImageUrls（构造时快照过滤 ImageItem，与垂直列表同源）
 *
 * 使用场景差异：
 * - ImageDetailAdapter：ImageDetailActivity 独立大图页（跨 Activity 共享元素动画）
 * - ImageDetailViewPagerAdapter：ImageGalleryActivity 内嵌全屏 ViewPager2 层
 *   （点击列表项时显示并定位，退出时同步索引回垂直列表，消除 Activity 切换断感）
 *
 * @param context Activity Context
 * @param sourceOrigin 订阅源 URL（用于 Referer 注入防盗链）
 * @param referer 文章页 URL（用于 Referer 注入）
 */
class ImageDetailViewPagerAdapter(
    context: Context,
    sourceOrigin: String?,
    referer: String? = null
) : ImageDetailAdapter(context, sourceOrigin, referer)
