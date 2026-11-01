package io.legado.app.help.storage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

/**
 * 2026-11-01：内置朗读音色库 / 角色规则纳入备份的**行为级**回归。
 *
 * 缺陷背景：`filesDir/tts/voices`（自录 + CloneTTS 导入的参考音频）与 `filesDir/legado_rules`
 * （已学角色 cue / 角色规则）此前不在备份范围 ⇒ 换机、重装、恢复后用户手里的音色与角色全丢，
 * 且参考音频是本地资产（不可下载、不可重建）。
 *
 * 本用例守住四件事：
 *  ① 选择器暴露两个新类别（否则用户无从勾选）；
 *  ② `Backup.stageTtsVoices/stageLegadoRules` 真把目录复制进备份根（不是只登记不写）；
 *  ③ `Restore.restoreTtsVoiceAssets` 真把目录复制回 `filesDir`（防「只备份不还原」同类缺口）；
 *  ④ 源目录缺失时不得产出空目录（防备份包被空壳污染）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TtsVoiceAssetsBackupTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.injectAsAppCtx()
    }

    @Test
    fun `选择器包含音色库与角色规则两个类别`() {
        val voices = BackupSelectorConfig.allItems.firstOrNull { it.key == "ttsVoices" }
        val rules = BackupSelectorConfig.allItems.firstOrNull { it.key == "ttsRules" }
        assertTrue("缺少 ttsVoices 备份类别", voices != null)
        assertTrue("缺少 ttsRules 备份类别", rules != null)
        assertEquals(Backup.TTS_VOICES_DIR, voices!!.fileName)
        assertEquals(Backup.LEGADO_RULES_DIR, rules!!.fileName)
        // 默认勾选（换机/重装默认就要保住这些不可重建的资产）
        assertTrue(BackupSelectorConfig.isSelected("ttsVoices"))
        assertTrue(BackupSelectorConfig.isSelected("ttsRules"))
    }

    @Test
    fun `备份把音色库与角色规则目录复制到备份根`() = runBlocking {
        val voicesSrc = File(context.filesDir, Backup.TTS_VOICES_DIR).apply { mkdirs() }
        File(voicesSrc, "index.json").writeText("{\"voices\":[]}")
        File(voicesSrc, "v1").apply { mkdirs() }
        File(voicesSrc, "v1/ref.wav").writeBytes(ByteArray(16) { 1 })
        val rulesSrc = File(context.filesDir, Backup.LEGADO_RULES_DIR).apply { mkdirs() }
        File(rulesSrc, "fixed_roles.json").writeText("{}")

        val root = File(context.filesDir, "backup_probe").apply { mkdirs() }
        Backup.stageTtsVoices(root.absolutePath)
        Backup.stageLegadoRules(root.absolutePath)

        assertTrue("音色索引未进入备份", File(root, "${Backup.TTS_VOICES_DIR}/index.json").isFile)
        assertTrue("参考音频未进入备份", File(root, "${Backup.TTS_VOICES_DIR}/v1/ref.wav").isFile)
        assertTrue("角色规则未进入备份", File(root, "${Backup.LEGADO_RULES_DIR}/fixed_roles.json").isFile)
    }

    @Test
    fun `恢复把音色库与角色规则目录复制回 filesDir`() = runBlocking {
        // 模拟换机：先清空本地，再从备份根还原
        val root = File(context.filesDir, "backup_probe2").apply { mkdirs() }
        File(root, Backup.TTS_VOICES_DIR).apply { mkdirs() }
        File(root, "${Backup.TTS_VOICES_DIR}/index.json").writeText("{\"voices\":[]}")
        File(root, Backup.LEGADO_RULES_DIR).apply { mkdirs() }
        File(root, "${Backup.LEGADO_RULES_DIR}/book").apply { mkdirs() }
        File(root, "${Backup.LEGADO_RULES_DIR}/book/workA.json").writeText("[]")

        File(context.filesDir, Backup.TTS_VOICES_DIR).deleteRecursively()
        File(context.filesDir, Backup.LEGADO_RULES_DIR).deleteRecursively()

        Restore.restoreTtsVoiceAssets(root.absolutePath)

        assertTrue(
            "音色索引未还原",
            File(context.filesDir, "${Backup.TTS_VOICES_DIR}/index.json").isFile
        )
        assertTrue(
            "角色规则未还原",
            File(context.filesDir, "${Backup.LEGADO_RULES_DIR}/book/workA.json").isFile
        )
    }

    @Test
    fun `源目录缺失时不产空目录`() = runBlocking {
        File(context.filesDir, Backup.TTS_VOICES_DIR).deleteRecursively()
        File(context.filesDir, Backup.LEGADO_RULES_DIR).deleteRecursively()
        val root = File(context.filesDir, "backup_probe3").apply { mkdirs() }
        Backup.stageTtsVoices(root.absolutePath)
        Backup.stageLegadoRules(root.absolutePath)
        assertFalse("缺失源不得产出空音色目录", File(root, Backup.TTS_VOICES_DIR).exists())
        assertFalse("缺失源不得产出空规则目录", File(root, Backup.LEGADO_RULES_DIR).exists())
    }
}
