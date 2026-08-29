package io.legado.app.model.rss

import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.splitNotBlank
import splitties.init.appCtx

/**
 * 订阅源统一搜索范围（rss-unified-search 新增）
 *
 * 参考 [io.legado.app.ui.book.search.SearchScope] 的设计，简化为两种范围：
 * - 空字符串（全部）：所有启用且 searchUrl 非空的订阅源
 * - "分组1,分组2"（按分组）：指定分组下启用且 searchUrl 非空的订阅源
 *
 * 与 SearchScope 的差异：
 * - 不支持"按类型"范围（订阅源无类型概念）
 * - 不支持"单源"范围（订阅源搜索不需要指定单源）
 * - [getRssSources] 过滤 searchUrl 非空（只有配置了 searchUrl 的源才能搜索）
 */
@Suppress("unused")
data class RssSearchScope(private var scope: String) {

    constructor(groups: List<String>) : this(groups.joinToString(","))

    override fun toString(): String {
        return scope
    }

    val stateLiveData = MutableLiveData(scope)

    fun update(scope: String, postValue: Boolean = true, save: Boolean = true) {
        this.scope = scope
        if (postValue) stateLiveData.postValue(scope)
        if (save) {
            save()
        }
    }

    fun update(groups: List<String>) {
        scope = groups.joinToString(",")
        stateLiveData.postValue(scope)
        save()
    }

    fun remove(scope: String) {
        val stringBuilder = StringBuilder()
        this.scope.split(",").forEach {
            if (it != scope) {
                if (stringBuilder.isNotEmpty()) {
                    stringBuilder.append(",")
                }
                stringBuilder.append(it)
            }
        }
        this.scope = stringBuilder.toString()
        stateLiveData.postValue(this.scope)
    }

    /**
     * 搜索范围显示（fix-rss-search-scope: token 映射友好文案，用户不感知 token 原文）
     */
    val display: String
        get() {
            if (scope.isEmpty()) {
                return appCtx.getString(R.string.all_source)
            }
            return displayNames.joinToString(",")
        }

    /**
     * 搜索范围显示
     */
    val displayNames: List<String>
        get() {
            val list = arrayListOf<String>()
            scope.splitNotBlank(",").forEach {
                list.add(
                    when {
                        it == TOKEN_NO_GROUP -> appCtx.getString(R.string.no_group)
                        it.startsWith(TOKEN_TYPE_PREFIX) -> when (it.substringAfter(TOKEN_TYPE_PREFIX).toIntOrNull()) {
                            0 -> appCtx.getString(R.string.type_web)
                            1 -> appCtx.getString(R.string.type_image)
                            2 -> appCtx.getString(R.string.type_video)
                            else -> it
                        }
                        else -> it
                    }
                )
            }
            return list
        }

    /**
     * 获取搜索范围内的订阅源列表（已启用且 searchUrl 非空）
     *
     * fix-rss-search-scope: 支持上下文 token（@type:N 类型 / @no_group 未分组），
     * 普通分组走 getByGroup 粗筛 + hasGroup 精筛，消除 like 子串误匹配
     *
     * @return 过滤后的订阅源列表，按 customOrder 排序
     */
    fun getRssSources(): List<RssSource> {
        val list = arrayListOf<RssSource>()
        if (scope.isEmpty()) {
            // 全部：过滤启用且 searchUrl 非空
            list.addAll(appDb.rssSourceDao.allEnabled.filter { !it.searchUrl.isNullOrBlank() })
        } else {
            // token/分组逐项解析
            val items = scope.splitNotBlank(",")
            val validItems = arrayListOf<String>()
            items.forEach { item ->
                val sources = when {
                    item.startsWith(TOKEN_TYPE_PREFIX) -> {
                        val type = item.substringAfter(TOKEN_TYPE_PREFIX).toIntOrNull() ?: -1
                        appDb.rssSourceDao.getEnabledByType(type)
                            .filter { !it.searchUrl.isNullOrBlank() }
                    }
                    item == TOKEN_NO_GROUP -> appDb.rssSourceDao.noGroup
                        .filter { it.enabled && !it.searchUrl.isNullOrBlank() }
                    else -> appDb.rssSourceDao.getByGroup(item)
                        .filter { it.enabled && it.hasGroup(item) && !it.searchUrl.isNullOrBlank() }
                }
                if (sources.isNotEmpty()) {
                    list.addAll(sources)
                    validItems.add(item)
                }
            }
            // 如果分组变化（某些分组无有效源），更新 scope
            if (validItems.size != items.size) {
                update(validItems)
            }
            // 如果所有分组都无有效源，回退到全部
            if (list.isEmpty()) {
                scope = ""
                stateLiveData.postValue(scope)
                list.addAll(appDb.rssSourceDao.allEnabled.filter { !it.searchUrl.isNullOrBlank() })
            }
        }
        return list.sortedBy { it.customOrder }
    }

    companion object {
        /** 上下文 token 前缀（fix-rss-search-scope）：@ 前缀避开用户分组名字面冲突 */
        private const val TOKEN_TYPE_PREFIX = "@type:"
        private const val TOKEN_NO_GROUP = "@no_group"
    }

    fun isAll(): Boolean {
        return scope.isEmpty()
    }

    fun save() {
        // fix-rss-search-scope: token 形态（@type:N / @no_group）为浏览上下文临时范围，不持久化
        if (scope.splitNotBlank(",").any { it.startsWith("@") }) {
            return
        }
        AppConfig.rssSearchScope = scope
        if (isAll() || scope.contains(",")) {
            AppConfig.rssSearchGroup = ""
        } else {
            AppConfig.rssSearchGroup = scope
        }
    }

}
