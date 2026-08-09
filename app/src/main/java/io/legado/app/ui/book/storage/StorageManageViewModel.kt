package io.legado.app.ui.book.storage

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.book.cache.CacheManageViewModel
import io.legado.app.ui.book.cache.CacheStorageDetail

/**
 * precise-manage: 存储管理页 ViewModel（复用 CacheManageViewModel 的统计/删除逻辑）
 */
class StorageManageViewModel(application: Application) : BaseViewModel(application) {

    private val cacheManageViewModel by lazy { CacheManageViewModel(application) }

    fun buildStorageBreakdown(): Coroutine<List<CacheStorageDetail>> {
        return cacheManageViewModel.buildStorageBreakdown()
    }

    fun deleteStorageTarget(detail: CacheStorageDetail): Coroutine<Boolean> {
        return cacheManageViewModel.deleteStorageTarget(detail)
    }
}