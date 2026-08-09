package io.legado.app.ui.book.storage

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityStorageManageBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.cache.CacheStorageDetail
import io.legado.app.ui.book.cache.formatBytes
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * precise-manage: 存储管理页（复用 CacheManageViewModel 统计/删除）
 */
class StorageManageActivity :
    BaseActivity<ActivityStorageManageBinding>() {

    override val binding by viewBinding(ActivityStorageManageBinding::inflate)
    private val viewModel by viewModels<StorageManageViewModel>()
    private val adapter by lazy { StorageManageAdapter(this, object : StorageManageAdapter.CallBack {
        override fun clear(detail: CacheStorageDetail) {
            confirmDelete(detail)
        }

        override fun showDetail(detail: CacheStorageDetail) {
            alert(R.string.storage_manage) {
                setMessage(
                    getString(R.string.cache_total, formatBytes(detail.bytes), 1)
                        + "\n" + (detail.deletePaths.firstOrNull() ?: "")
                )
                okButton()
            }
        }
    }) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        loadData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.storage_manage, menu)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_refresh -> loadData()
            R.id.menu_clear_all -> confirmClearAll()
            else -> return super.onCompatOptionsItemSelected(item)
        }
        return true
    }

    private fun loadData() {
        viewModel.buildStorageBreakdown().onSuccess { list ->
            adapter.setItems(list)
        }.onError {
            toastOnUi(getString(R.string.error) + it.localizedMessage)
        }
    }

    private fun confirmDelete(detail: CacheStorageDetail) {
        alert(R.string.clear, R.string.sure_del) {
            yesButton {
                viewModel.deleteStorageTarget(detail).onSuccess { success ->
                    if (success) {
                        toastOnUi(R.string.del_file_success)
                        loadData()
                    } else {
                        toastOnUi(R.string.delete_fail)
                    }
                }.onError {
                    toastOnUi(R.string.delete_fail)
                }
            }
            noButton()
        }
    }

    private fun confirmClearAll() {
        alert(R.string.clear_all_cache, R.string.clear_all_cache_confirm) {
            yesButton {
                viewModel.buildStorageBreakdown().onSuccess { list ->
                    list.forEach { detail ->
                        viewModel.deleteStorageTarget(detail).onSuccess { }.onError { }
                    }
                    toastOnUi(R.string.clear_cache_success)
                    loadData()
                }.onError {
                    toastOnUi(R.string.delete_fail)
                }
            }
            noButton()
        }
    }
}