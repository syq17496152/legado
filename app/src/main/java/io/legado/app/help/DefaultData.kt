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
        // F7/4.10 + builtin-replace-id-fix：替换净化内置同步每次启动幂等执行（AD-03 v1.2 修订）。
        // 不用 per-key 旗标的原因：isLastVersion「读后即写回」——评估与执行非原子，执行被打断后
        // 机会永久丢失（实测：启动 18s 内 force-stop，旗标已写 2 但迁移未跑，之后永不触发）。
        // 本操作幂等：负行迁移 + insertIfAbsent(IGNORE) 缺失追加，无变化时零写盘、静默不打日志；
        // 开销仅每次启动 12 次 findById + 12 次 IGNORE 检查，微秒级，可忽略。
        Coroutine.async {
            importDefaultReplaceRules()
        }.onError {
            it.printOnDebug()
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

    /** 替换净化内置规则资产（12 条，id 为正数 1~12；历史版本曾用负 id -1~-12，已迁移） */
    val replaceRules: List<io.legado.app.data.entities.ReplaceRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}replaceRules.json")
                .readBytes()
        )
        GSON.fromJsonArray<io.legado.app.data.entities.ReplaceRule>(json).getOrDefault(emptyList())
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
            // F7/4.9：deleteDefault+全量重插改为 insertIfAbsent 追加模式——
            // 不重置用户对既有内置规则的修改/开关状态，新增规则以缺失 id delta 追加
            val inserted = appDb.txtTocRuleDao.insertIfAbsent(*txtTocRules.toTypedArray())
            val added = inserted.count { it == -1L }
            AppLog.put("内置 TXT 目录规则同步：新增 $added 条（共 ${txtTocRules.size} 条内置）")
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

    /**
     * F7/4.10 + builtin-replace-id-fix：替换净化内置规则同步（共 12 条内置，id 为 JSON 写死的 1~12 正数）。
     * 每次启动由 upVersion() 幂等调用（无旗标），三分支逻辑：
     * 1) 存在旧负 id 行（-N，F7/4.10 首版写入）→ 保数据换 id：旧行内容原样 copy 到新 id（保留用户修改），
     *    再删负行。新 id 槽位已被占（撞 id）时用户数据优先：跳过写入，负行修改丢弃（概率极低）。
     * 2) 无旧负行 → insertIfAbsent 幂等追加（首装/新增内置规则推送语义，不重置用户对已有规则的修改）。
     * 全程无变化时零写盘、不打日志（migrated/added 均为 0 时静默）。
     * 注意：新 id 必须取 JSON 写死的 builtin.id，禁止用遍历序号推导——避免未来 JSON 插删规则导致存量 id 映射漂移。
     */
    fun importDefaultReplaceRules() {
        runBlocking(IO) {
            val dao = appDb.replaceRuleDao
            var migrated = 0
            var added = 0
            replaceRules.forEach { builtin ->
                val old = dao.findById(-builtin.id)
                if (old != null) {
                    // 保数据换 id：保留用户对内置规则的修改（pattern/isEnabled 等）
                    // 新 id 槽位被占（撞 id）时用户数据优先：跳过写入，负行修改丢弃（AD-01 tradeoff）
                    if (dao.findById(builtin.id) == null) {
                        dao.insert(old.copy(id = builtin.id))
                    }
                    dao.delete(old)
                    migrated++
                } else {
                    if (dao.insertIfAbsent(builtin).firstOrNull() != -1L) {
                        added++
                    }
                }
            }
            if (migrated > 0 || added > 0) {
                AppLog.put("内置替换净化规则同步：迁移 $migrated 条、新增 $added 条（共 ${replaceRules.size} 条内置）")
            }
        }
    }
}