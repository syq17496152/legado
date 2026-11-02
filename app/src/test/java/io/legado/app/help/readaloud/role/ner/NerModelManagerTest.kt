package io.legado.app.help.readaloud.role.ner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * AD-16 模型就绪判据：五个必需文件齐备（非空）才算就绪，缺一即回落规则路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class NerModelManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `模型目录固定在 files ner lac`() {
        assertEquals(File(context.filesDir, "ner/lac"), NerModelManager.dir(context))
    }

    @Test
    fun `无模型文件时未就绪`() {
        NerModelManager.dir(context).deleteRecursively()
        assertFalse(NerModelManager.isReady(context))
    }

    @Test
    fun `五个必需文件齐备才就绪`() {
        val dir = NerModelManager.dir(context).apply { mkdirs() }
        NerModelManager.REQUIRED_FILES.forEach { File(dir, it).writeText("probe") }
        assertTrue(NerModelManager.isReady(context))

        // 任一文件缺失（或为空）都必须立刻判为未就绪，防止用半套模型跑推理
        File(dir, "word.dic").delete()
        assertFalse(NerModelManager.isReady(context))
    }
}
