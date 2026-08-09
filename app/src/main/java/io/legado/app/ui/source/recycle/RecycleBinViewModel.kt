package io.legado.app.ui.source.recycle

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SourceRecycleBin
import io.legado.app.help.source.SourceRecycleBinHelp
import io.legado.app.utils.toastOnUi

class RecycleBinViewModel(application: Application) : BaseViewModel(application) {

    fun restore(item: SourceRecycleBin, overwrite: Boolean) {
        execute {
            SourceRecycleBinHelp.restore(item, overwrite)
        }.onError {
            val msg = "恢复规则失败\n${it.localizedMessage}"
            AppLog.put(msg, it)
            context.toastOnUi(msg)
        }.onSuccess {
            context.toastOnUi("已恢复")
        }
    }

    fun delete(vararg items: SourceRecycleBin) {
        execute {
            appDb.sourceRecycleBinDao.delete(*items)
        }.onError {
            val msg = "删除回收项失败\n${it.localizedMessage}"
            AppLog.put(msg, it)
            context.toastOnUi(msg)
        }
    }

    fun empty() {
        execute {
            appDb.sourceRecycleBinDao.deleteAll()
        }.onError {
            val msg = "清空回收站失败\n${it.localizedMessage}"
            AppLog.put(msg, it)
            context.toastOnUi(msg)
        }
    }

    fun hasConflict(item: SourceRecycleBin): io.legado.app.help.coroutine.Coroutine<Boolean> {
        return execute {
            SourceRecycleBinHelp.hasConflict(item)
        }
    }
}
