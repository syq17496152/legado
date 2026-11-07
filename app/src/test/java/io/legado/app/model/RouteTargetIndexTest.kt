package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IF-08：切换线路时保留当前集号（`switchBookRoute` / `switchToRoute` 共用）。
 *
 * 真机铁证（2026-11-07，`3.26.110723` 23:38）：52 集书源在第 50 集切线路后
 * `switch_req fromIndex=0 toIndex=0 | episode=第01集` ⇒ 旧实现一律归零。
 */
class RouteTargetIndexTest {

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
}
