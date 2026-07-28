package io.legado.app.ui.highlight

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleStore

/**
 * F-P1-2 高亮规则管理 ViewModel（借鉴阅读T，适配 SharedPreferences 存储）
 * 简化说明：用 MutableLiveData 模拟 Room flow | 已知上限：非数据库 flow，跨进程不实时 | 升级路径：迁移到 Room 后改用 flow
 */
class HighlightRuleViewModel(application: Application) : BaseViewModel(application) {

    private val _rulesLiveData = MutableLiveData<List<HighlightRule>>()
    val rulesLiveData: LiveData<List<HighlightRule>> = _rulesLiveData

    init {
        loadRules()
    }

    fun loadRules() {
        execute {
            HighlightRuleStore.load(context)
        }.onSuccess {
            _rulesLiveData.postValue(it)
        }.onError {
            AppLog.put("加载高亮规则失败", it)
        }
    }

    fun update(vararg rules: HighlightRule) {
        execute {
            val list = HighlightRuleStore.load(context).toMutableList()
            for (rule in rules) {
                val idx = list.indexOfFirst { it.id == rule.id }
                // T-B3: upsert 语义（对齐 HighlightRuleEditDialog.kt:229-236 先例）
                // 修复 R-P1-2 根因 b：原实现 idx<0 静默丢弃，导致预设添加后规则未入库
                if (idx >= 0) list[idx] = rule else list.add(rule)
            }
            HighlightRuleStore.save(context, list)
            list
        }.onSuccess {
            _rulesLiveData.postValue(it)
            // T-B5: 即时生效（修复 R-P1-2 即时性缺陷：原仅依赖 Activity.onDestroy 触发）
            // 对标 HighlightRuleEditDialog.kt:238 既有模式
            ReadBook.upHighlightRules()
        }
    }

    fun delete(rule: HighlightRule) {
        execute {
            val list = HighlightRuleStore.load(context).filterNot { it.id == rule.id }
            HighlightRuleStore.save(context, list)
            list
        }.onSuccess {
            _rulesLiveData.postValue(it)
        }
    }

    fun toTop(rule: HighlightRule) {
        execute {
            val list = HighlightRuleStore.load(context).toMutableList()
            val idx = list.indexOfFirst { it.id == rule.id }
            if (idx > 0) {
                val r = list.removeAt(idx)
                list.add(0, r)
                HighlightRuleStore.save(context, list)
            }
            list
        }.onSuccess {
            _rulesLiveData.postValue(it)
        }
    }

    fun toBottom(rule: HighlightRule) {
        execute {
            val list = HighlightRuleStore.load(context).toMutableList()
            val idx = list.indexOfFirst { it.id == rule.id }
            if (idx >= 0 && idx < list.size - 1) {
                val r = list.removeAt(idx)
                list.add(r)
                HighlightRuleStore.save(context, list)
            }
            list
        }.onSuccess {
            _rulesLiveData.postValue(it)
        }
    }

    fun upOrder(items: List<HighlightRule>) {
        execute {
            HighlightRuleStore.save(context, items)
            items
        }.onSuccess {
            _rulesLiveData.postValue(it)
        }
    }
}
