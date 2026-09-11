package io.legado.app.ui.config

import android.app.Application
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.AppWebDav
import io.legado.app.utils.toastOnUi

class ConfigViewModel(application: Application) : BaseViewModel(application) {

    fun upWebDavConfig() {
        execute {
            AppWebDav.upConfig()
        }
    }

    fun upCloudStorageConfig() {
        execute {
            AppCloudStorage.upConfig()
        }
    }

    // F6/4.7：clearCache/clearWebViewData 已删除——清除缓存与精准管理-缓存管理重复且全量删 cacheDir
    // 无播放中保护；WebView 清理迁移为缓存管理第 4 分项（CacheManageViewModel）

    fun shrinkDatabase() {
        execute {
            appDb.openHelper.writableDatabase.execSQL("VACUUM")
        }.onSuccess {
            context.toastOnUi(R.string.success)
        }
    }

}
