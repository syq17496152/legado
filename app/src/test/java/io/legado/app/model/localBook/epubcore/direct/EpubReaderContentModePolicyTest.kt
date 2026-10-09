package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五态分流 → 渲染参数判据单测（AD-06 / AD-15）。
 *
 * 覆盖：①只有 REFLOWABLE 做阅读器归一化；②固定版式/媒体/交互单页；
 * ③竖排透传且不归一化；④视口缺省/非法值退化；⑤缓存键隔离不同内容态。
 */
class EpubReaderContentModePolicyTest {

    @Test
    fun reflowableIsReaderNormalizedMultiPage() {
        val mode = EpubReaderContentModePolicy.resolve(EpubDirectLayoutMode.REFLOWABLE)
        assertFalse(mode.preservePublisherLayout)
        assertFalse(mode.singlePage)
        assertFalse(mode.verticalWriting)
        assertTrue(mode.readerNormalized)
    }

    @Test
    fun publisherStyledKeepsPublisherLayoutWithoutReaderChrome() {
        val mode = EpubReaderContentModePolicy.resolve(EpubDirectLayoutMode.PUBLISHER_STYLED)
        assertTrue(mode.preservePublisherLayout)
        assertFalse("出版方语义排版仍可多页滚动", mode.singlePage)
        assertFalse(mode.readerNormalized)
    }

    @Test
    fun fixedLayoutIsSinglePageAndScalesWhenViewportDeclared() {
        val withViewport = EpubReaderContentModePolicy.resolve(
            layoutMode = EpubDirectLayoutMode.FIXED,
            publisherViewportWidth = 1024f,
            publisherViewportHeight = 768f
        )
        assertTrue(withViewport.singlePage)
        assertTrue(withViewport.preservePublisherLayout)
        assertTrue(withViewport.scaleToPublisherViewport)
        assertEquals(1024f, withViewport.publisherViewportWidthPx!!, 0.001f)

        val withoutViewport = EpubReaderContentModePolicy.resolve(EpubDirectLayoutMode.FIXED)
        assertTrue(withoutViewport.singlePage)
        assertFalse("未声明视口不得臆造缩放", withoutViewport.scaleToPublisherViewport)
    }

    @Test
    fun interactiveNeverScalesToPreserveHitTesting() {
        val mode = EpubReaderContentModePolicy.resolve(
            layoutMode = EpubDirectLayoutMode.INTERACTIVE,
            publisherViewportWidth = 800f,
            publisherViewportHeight = 600f
        )
        assertTrue(mode.singlePage)
        assertFalse(mode.scaleToPublisherViewport)
    }

    @Test
    fun mediaIsSinglePage() {
        val mode = EpubReaderContentModePolicy.resolve(EpubDirectLayoutMode.MEDIA)
        assertTrue(mode.singlePage)
        assertTrue(mode.preservePublisherLayout)
    }

    @Test
    fun verticalWritingIsPropagatedForPreservedLayouts() {
        val mode = EpubReaderContentModePolicy.resolve(
            layoutMode = EpubDirectLayoutMode.PUBLISHER_STYLED,
            writingMode = "vertical-rl"
        )
        assertTrue(mode.verticalWriting)

        // 归一化路径不声明竖排（阅读器主题横排语义，避免与主题冲突）。
        val reflowable = EpubReaderContentModePolicy.resolve(
            layoutMode = EpubDirectLayoutMode.REFLOWABLE,
            writingMode = "vertical-rl"
        )
        assertFalse(reflowable.verticalWriting)
    }

    @Test
    fun invalidViewportValuesAreDiscarded() {
        val mode = EpubReaderContentModePolicy.resolve(
            layoutMode = EpubDirectLayoutMode.FIXED,
            publisherViewportWidth = 0f,
            publisherViewportHeight = -12f
        )
        assertEquals(null, mode.publisherViewportWidthPx)
        assertEquals(null, mode.publisherViewportHeightPx)
        assertFalse(mode.scaleToPublisherViewport)
    }

    @Test
    fun cacheKeySeparatesModesAndViewports() {
        val reflowable = EpubReaderContentModePolicy.resolve(EpubDirectLayoutMode.REFLOWABLE)
        val styled = EpubReaderContentModePolicy.resolve(EpubDirectLayoutMode.PUBLISHER_STYLED)
        val fixedA = EpubReaderContentModePolicy.resolve(
            EpubDirectLayoutMode.FIXED, publisherViewportWidth = 100f, publisherViewportHeight = 200f
        )
        val fixedB = EpubReaderContentModePolicy.resolve(
            EpubDirectLayoutMode.FIXED, publisherViewportWidth = 300f, publisherViewportHeight = 400f
        )
        assertNotNull(reflowable.cacheKey())
        assertTrue(reflowable.cacheKey() != styled.cacheKey())
        assertTrue(fixedA.cacheKey() != fixedB.cacheKey())
    }
}