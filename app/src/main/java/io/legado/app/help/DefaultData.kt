package io.legado.app.help

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.BookCover
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File

object DefaultData {

    fun upVersion() {
        if (LocalConfig.versionCode < AppConst.appInfo.versionCode) {
            Coroutine.async {
                if (LocalConfig.needUpHttpTTS) {
                    importDefaultHttpTTS()
                }
                if (LocalConfig.needUpTxtTocRule) {
                    importDefaultTocRules()
                }
                if (LocalConfig.needUpRssSources) {
                    importDefaultRssSources()
                }
                if (LocalConfig.needUpDictRule) {
                    importDefaultDictRules()
                }
                // E4/F-2：选角模板首装/升级导入（importBuiltinTemplates 自带 id 幂等，无需 delete）
                if (LocalConfig.needUpTtsCastingTemplates) {
                    importDefaultTtsCastingTemplates()
                }
            }.onError {
                it.printOnDebug()
            }
        }
    }

    val httpTTS: List<HttpTTS> by lazy {
        val json =
            String(
                appCtx.assets.open("defaultData${File.separator}httpTTS.json")
                    .readBytes()
            )
        HttpTTS.fromJsonArray(json).getOrElse {
            emptyList()
        }
    }

    val readConfigs: List<ReadBookConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ReadBookConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ReadBookConfig.Config>(json).getOrNull()
            ?: emptyList()
    }

    val txtTocRules: List<TxtTocRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}txtTocRule.json")
                .readBytes()
        )
        GSON.fromJsonArray<TxtTocRule>(json).getOrNull() ?: emptyList()
    }

    val rssSources: List<RssSource> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}rssSources.json")
                .readBytes()
        )
        GSON.fromJsonArray<RssSource>(json).getOrDefault(emptyList())
    }

    val coverRule: BookCover.CoverRule by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}coverRule.json")
                .readBytes()
        )
        GSON.fromJsonObject<BookCover.CoverRule>(json).getOrThrow()
    }

    val dictRules: List<DictRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}dictRules.json")
                .readBytes()
        )
        GSON.fromJsonArray<DictRule>(json).getOrThrow()
    }

    val keyboardAssists: List<KeyboardAssist> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}keyboardAssists.json")
                .readBytes()
        )
        GSON.fromJsonArray<KeyboardAssist>(json).getOrThrow()
    }

    fun importDefaultHttpTTS() {
        runBlocking(IO) {
            appDb.httpTTSDao.deleteDefault()
            appDb.httpTTSDao.insert(*httpTTS.toTypedArray())
        }
    }

    /** E4/F-2：选角模板首装导入（importBuiltinTemplates 自带 id 幂等+builtin 全字段升级刷新，无需 delete） */
    fun importDefaultTtsCastingTemplates() {
        runBlocking(IO) {
            val json = String(
                appCtx.assets.open("defaultData${File.separator}tts${File.separator}castingTemplates.json")
                    .readBytes()
            )
            val (imported, _) = io.legado.app.help.readaloud.casting.TtsCastingStore
                .importBuiltinTemplates(json)
            AppLog.put("内置选角模板导入完成：$imported 条")
        }
    }

    fun importDefaultTocRules() {
        runBlocking(IO) {
            appDb.txtTocRuleDao.deleteDefault()
            appDb.txtTocRuleDao.insert(*txtTocRules.toTypedArray())
        }
    }

    fun importDefaultRssSources() {
        runBlocking(IO) {
            appDb.rssSourceDao.deleteDefault()
            appDb.rssSourceDao.insert(*rssSources.toTypedArray())
        }
    }

    fun importDefaultDictRules() {
        runBlocking(IO) {
            appDb.dictRuleDao.insert(*dictRules.toTypedArray())
        }
    }

}