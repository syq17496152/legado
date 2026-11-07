package io.legado.app.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IF-08：切换线路时保留当前集号（`switchBookRoute` / `switchToRoute` / **`switchRssRoute`** 共用）。
 *
 * 真机铁证（2026-11-07，`3.26.110723` 23:38）：52 集书源在第 50 集切线路后
 * `switch_req fromIndex=0 toIndex=0 | episode=第01集` ⇒ 旧实现一律归零。
 *
 * 二次回归（2026-11-08，`3.26.110804` 05:34）：**传统布局**点线路 chip 时
 * `isNewRoutesMode()` 因 `volumes` 为空返回 false ⇒ 走旧模式 `switchRssRoute`
 * （原实现硬编码 `rssEpisodeIndex = 0`），第 51 集换线路后又回到第 01 集。
 */
class RouteTargetIndexTest {

    private val videoPlaySource by lazy {
        listOf(
            File("src/main/java/io/legado/app/model/VideoPlay.kt"),
            File("../app/src/main/java/io/legado/app/model/VideoPlay.kt"),
            File("app/src/main/java/io/legado/app/model/VideoPlay.kt")
        ).first { it.isFile }.readText()
    }

    @Test
    fun `集号在新线路范围内时保持不变`() {
        assertEquals(49, routeTargetIndex(keepIndex = 49, newSize = 52))
        assertEquals(0, routeTargetIndex(keepIndex = 0, newSize = 52))
    }

    @Test
    fun `新线路集数不足时退到最后一集`() {
        assertEquals(17, routeTargetIndex(keepIndex = 49, newSize = 18))
        assertEquals(0, routeTargetIndex(keepIndex = 0, newSize = 1))
    }

    @Test
    fun `新线路无集数时返回零且不抛异常`() {
        assertEquals(0, routeTargetIndex(keepIndex = 49, newSize = 0))
        assertEquals(0, routeTargetIndex(keepIndex = 0, newSize = 0))
    }

    @Test
    fun `负索引按第一集处理`() {
        assertEquals(0, routeTargetIndex(keepIndex = -3, newSize = 52))
    }

    @Test
    fun `旧模式内存切换与新模式同口径`() {
        // 旧模式 switchRssRoute 的三种落点必须与新链路一致（同一纯函数契约）
        assertEquals(50, routeTargetIndex(keepIndex = 50, newSize = 52))
        assertEquals(17, routeTargetIndex(keepIndex = 50, newSize = 18))
        assertEquals(0, routeTargetIndex(keepIndex = 50, newSize = 0))
        // 回归红线：旧模式不得再硬编码归零
        val body = videoPlaySource.substringAfter("fun switchRssRoute").substringBefore("\n    }")
        assertFalse("switchRssRoute 不得再硬编码 rssEpisodeIndex = 0", body.contains("rssEpisodeIndex = 0"))
        assertTrue(
            "switchRssRoute 必须走 routeTargetIndex 保留集号",
            body.contains("routeTargetIndex(keepIndex, route.episodes.size)")
        )
    }
}
