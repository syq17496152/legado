package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IF-14-M 配对用例：朗读起播前置条件的归因（真机长跑「无痕断流」诊断）。
 */
class ReadAloudStartGuardTest {

    @Test
    fun `初始化未完成时归因为 not_init`() {
        assertEquals(
            ReadAloudStartGuard.REASON_NOT_INIT,
            ReadAloudStartGuard.blockReason(initFinished = false, focusGranted = false, contentEmpty = true)
        )
    }

    @Test
    fun `焦点申请失败时归因为 no_focus`() {
        assertEquals(
            ReadAloudStartGuard.REASON_NO_FOCUS,
            ReadAloudStartGuard.blockReason(initFinished = true, focusGranted = false, contentEmpty = false)
        )
    }

    @Test
    fun `朗读列表为空时归因为 empty_list`() {
        assertEquals(
            ReadAloudStartGuard.REASON_EMPTY_LIST,
            ReadAloudStartGuard.blockReason(initFinished = true, focusGranted = true, contentEmpty = true)
        )
    }

    @Test
    fun `条件齐备时可起播`() {
        assertEquals(
            ReadAloudStartGuard.REASON_OK,
            ReadAloudStartGuard.blockReason(initFinished = true, focusGranted = true, contentEmpty = false)
        )
    }
}
