package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * CloneTTS 备份包导入（T6.1 / C-5）：profiles XML + 音频 + voice-pack 叠加；重复去重。
 */
class ClonettsVoiceImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun buildZip(voiceId: String, text: String): File {
        val file = File(temp.newFolder("zip-${System.nanoTime()}"), "voices.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            val prefs = """
                <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
                <map>
                  <string name="profiles_list">[{"id":"$voiceId","name":"旁白男","promptText":"$text","roleTags":["旁白"],"numSteps":4}]</string>
                </map>
            """.trimIndent()
            zip.putNextEntry(ZipEntry("shared_prefs/voice_profiles_prefs.xml"))
            zip.write(prefs.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("files/profiles/$voiceId/prompt.wav"))
            zip.write(PcmAudioUtils.wavBytes(ByteArray(128) { 7 }, 16000))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("voice-pack.json"))
            zip.write(
                """{"version":1,"voices":[{"id":"$voiceId","name":"旁白男","gender":"男",
                   "ageByName":"青年","numSteps":6,"dur":5.2}]}""".toByteArray()
            )
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun `导入备份包并叠加清洗清单字段`() {
        val store = BuiltinVoiceStore(temp.newFolder("store"))
        val importer = ClonettsVoiceImporter(store)
        val report = kotlinx.coroutines.runBlocking { importer.importZip(buildZip("v-1", "这是逐字稿")) }
        assertEquals(1, report.imported)
        assertEquals(1, store.list().size)
        val voice = store.list().first()
        assertEquals("旁白男", voice.name)
        assertEquals("这是逐字稿", voice.refText)
        assertEquals("男", voice.gender)
        assertEquals("青年", voice.age)
        assertEquals(BuiltinVoice.ORIGIN_ZIP, voice.origin)
    }

    @Test
    fun `重复音频按 md5 去重`() {
        val store = BuiltinVoiceStore(temp.newFolder("store2"))
        val importer = ClonettsVoiceImporter(store)
        kotlinx.coroutines.runBlocking { importer.importZip(buildZip("v-2", "稿一")) }
        val second = kotlinx.coroutines.runBlocking { importer.importZip(buildZip("v-3", "稿二")) }
        assertEquals(0, second.imported)
        assertEquals(1, second.duplicates)
    }

    @Test
    fun `非备份包返回空报告而非崩溃`() {
        val store = BuiltinVoiceStore(temp.newFolder("store3"))
        val file = File(temp.newFolder("junk"), "junk.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("hi".toByteArray())
            zip.closeEntry()
        }
        val report = kotlinx.coroutines.runBlocking { ClonettsVoiceImporter(store).importZip(file) }
        assertTrue(report.imported == 0)
        assertTrue(store.list().isEmpty())
    }

    /** 两条不同音频的音色包（批量入库路径：整批一次写索引） */
    private fun buildMultiZip(): File {
        val file = File(temp.newFolder("zip-multi-${System.nanoTime()}"), "multi.zip")
        val profiles = listOf("m-1" to "旁白男", "m-2" to "旁白女")
        ZipOutputStream(file.outputStream()).use { zip ->
            val list = profiles.joinToString(",") {
                """{"id":"${it.first}","name":"${it.second}","promptText":"稿-${it.first}","roleTags":["旁白"],"numSteps":4}"""
            }
            val prefs = """
                <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
                <map>
                  <string name="profiles_list">[$list]</string>
                </map>
            """.trimIndent()
            zip.putNextEntry(ZipEntry("shared_prefs/voice_profiles_prefs.xml"))
            zip.write(prefs.toByteArray())
            zip.closeEntry()
            profiles.forEachIndexed { index, (id, _) ->
                zip.putNextEntry(ZipEntry("files/profiles/$id/prompt.wav"))
                // 音频填不同样值，避免 md5 去重把第二条吞掉
                zip.write(PcmAudioUtils.wavBytes(ByteArray(128) { (7 + index).toByte() }, 16000))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `重复导入同一备份包按稳定 id 全量去重`() {
        val store = BuiltinVoiceStore(temp.newFolder("store-reimport"))
        val zip = buildMultiZip()
        kotlinx.coroutines.runBlocking { ClonettsVoiceImporter(store).importZip(zip) }
        val second = kotlinx.coroutines.runBlocking { ClonettsVoiceImporter(store).importZip(zip) }
        assertEquals(2, second.total)
        assertEquals(0, second.imported)
        assertEquals(2, second.duplicates)
        assertEquals(2, store.list().size)
    }

    @Test
    fun `多音色备份包整批导入且索引完整`() {
        val store = BuiltinVoiceStore(temp.newFolder("store-multi"))
        val report = kotlinx.coroutines.runBlocking {
            ClonettsVoiceImporter(store).importZip(buildMultiZip())
        }
        assertEquals(2, report.total)
        assertEquals(2, report.imported)
        assertEquals(0, report.skipped)
        assertEquals(2, store.list().size)
        assertEquals(listOf("旁白女", "旁白男"), store.list().map { it.name }.sorted())
        // 导入内部完成池聚合：roleTags=旁白 ⇒ groupId 已回写
        assertEquals(listOf("旁白", "旁白"), store.list().map { it.groupId })
    }
}
