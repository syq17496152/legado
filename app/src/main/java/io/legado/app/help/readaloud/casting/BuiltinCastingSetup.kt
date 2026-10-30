package io.legado.app.help.readaloud.casting

import android.content.res.AssetManager
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置"沉浸多角色"模板的开箱启用（T5.2 / AD-09）：
 * 读阅读服务里 multiRole 路径的唯一闸门是 `TtsCastingStore.activeTemplateId`，
 * 没有激活模板 ⇒ 永远走 legacy 单声（2026-10-30 模拟器铁证：`resolve 未激活模板（multi 关闭）`）。
 *
 * 这里把"幂等导入 assets 内置模板 + 激活内置多角色模板"收敛成一个入口，
 * 供开书向导/管理页调用；不自造第二套激活写入口（仍走 [TtsCastingStore.setActiveTemplateId]）。
 */
object BuiltinCastingSetup {

    /** assets/defaultData/tts/castingTemplates.json 中的内置多角色模板 id */
    const val TEMPLATE_ID = "builtin_immersive_multi"

    fun isActive(): Boolean = TtsCastingStore.activeTemplateId() == TEMPLATE_ID

    /**
     * 幂等导入内置模板并激活内置多角色模板。
     * @return true=模板可用且已激活；false=模板缺失（assets 异常/导入失败），调用方须明示失败原因
     */
    suspend fun ensureAndActivate(assets: AssetManager): Boolean = withContext(Dispatchers.IO) {
        val assetJson = runCatching {
            assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull()
        assetJson?.let {
            if (!templatePresent(it)) AppLog.put("内置模板 assets 中缺少 $TEMPLATE_ID（请检查 ${ASSET_PATH}）")
        }
        runCatching {
            assetJson?.let { json -> TtsCastingStore.importBuiltinTemplates(json) }
        }.onFailure { AppLog.put("内置选角模板导入失败：${it.message}", it) }
        val exists = runCatching { appDb.ttsCastingTemplateDao.get(TEMPLATE_ID) }.getOrNull() != null
        if (!exists) {
            AppLog.put("内置多角色模板缺失：$TEMPLATE_ID")
            return@withContext false
        }
        TtsCastingStore.setActiveTemplateId(TEMPLATE_ID)
        AppLog.put("已启用内置多角色模板：$TEMPLATE_ID")
        true
    }

    /**
     * 纯函数（可单测）：内置模板 JSON 文本里是否存在目标模板 id。
     * 用宽松正则而非 org.json（单测 JVM 下 org.json 未桩化会抛 "not mocked"）。
     */
    internal fun templatePresent(json: String?, id: String = TEMPLATE_ID): Boolean {
        val text = json ?: return false
        if (id.isBlank()) return false
        return Regex("\"id\"\\s*:\\s*\"" + Regex.escape(id) + "\"").containsMatchIn(text)
    }

    private const val ASSET_PATH = "defaultData/tts/castingTemplates.json"
}
