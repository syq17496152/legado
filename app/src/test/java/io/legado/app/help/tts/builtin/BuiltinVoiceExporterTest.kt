package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.sin

/**
 * 音色导出（T8.10）：用户音色导出 ZIP → 新库导入 → 名称/逐字稿/性别/标签保留；内置只读不可导出。
 */
class BuiltinVoiceExporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sineWav(durationMs: Int = 4000, sampleRate: Int = 16000): ByteArray {
        val count = sampleRate * durationMs / 1000
        val samples = FloatArray(count) { i ->
            0.4f * sin(2.0 * Math.PI * 220.0 * i / sampleRate).toFloat()
        }
        return PcmAudioUtils.wavBytes(PcmAudioUtils.floatToPcm16(samples), sampleRate)
    }

    @Test
    fun `导出的音色包可被导入还原`() {
        val source = BuiltinVoiceStore(temp.newFolder("src"))
        source.addVoice(
            name = "旁白男",
            pcmWavBytes = sineWav(),
            refText = "这是逐字稿内容",
            tags = listOf("旁白", "青年"),
            origin = BuiltinVoice.ORIGIN_ZIP,
            profileId = "clone",
            gender = "男",
            age = "青年"
        )
        val zip = File(temp.newFolder("out"), "voices.zip")
        val report = BuiltinVoiceExporter(source).export(zip)
        assertEquals(1, report.exported)

        val target = BuiltinVoiceStore(temp.newFolder("dst"))
        val imported = kotlinx.coroutines.runBlocking {
            ClonettsVoiceImporter(target).importZip(zip)
        }
        assertEquals(1, imported.imported)
        val voice = target.list().first()
        assertEquals("旁白男", voice.name)
        assertEquals("这是逐字稿内容", voice.refText)
        // 2026-11-10：导入端统一值域（中文 男/青年 → male/youth），round-trip 以规范值为准
        assertEquals("male", voice.gender)
        assertEquals("youth", voice.age)
        assertTrue(voice.tags.contains("旁白"))
        assertTrue(target.audioFile(voice).isFile)
    }

    @Test
    fun `内置只读音色不参与导出`() {
        val store = BuiltinVoiceStore(temp.newFolder("src2"))
        store.addVoice(
            name = "内置音色",
            pcmWavBytes = sineWav(3000),
            refText = "内置逐字稿",
            tags = listOf("preset"),
            origin = BuiltinVoice.ORIGIN_PRESET,
            profileId = "clone",
            readOnly = true,
            id = "b6_m_teen"
        )
        store.addVoice(
            name = "自录音色",
            pcmWavBytes = sineWav(3000),
            refText = "自录逐字稿",
            tags = listOf("self"),
            origin = BuiltinVoice.ORIGIN_SELF,
            profileId = "clone"
        )
        val zip = File(temp.newFolder("out2"), "voices.zip")
        val report = BuiltinVoiceExporter(store).export(zip)
        assertEquals(1, report.exported)
        // 内置音色被排除：导出包内只有一个 profiles 条目
        val entries = java.util.zip.ZipFile(zip).use { z ->
            z.entries().asSequence().filter { it.name.startsWith("files/profiles/") }.toList()
        }
        assertEquals(1, entries.size)
        assertTrue(!entries.first().name.contains("b6_m_teen"))
    }

    @Test
    fun `prefs XML 转义后可被导入端反解析`() {
        val json = """[{"id":"a","name":"引号\"测试","promptText":"<文本>"}]"""
        val xml = BuiltinVoiceExporter(BuiltinVoiceStore(temp.newFolder("s3"))).buildPrefsXml(json)
        assertTrue(xml.contains("&quot;"))
        assertTrue(xml.contains("&lt;"))
        assertTrue(xml.startsWith("<?xml"))
    }
}
