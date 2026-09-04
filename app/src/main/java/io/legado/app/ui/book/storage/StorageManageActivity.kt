package io.legado.app.ui.book.storage

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityStorageManageBinding
import io.legado.app.ui.book.cache.CacheStorageDetail
import io.legado.app.ui.book.cache.formatBytes
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 书库存储管理页（L-B15，S2 列表族 + 统计卡）。
 *
 * Compose 内容区（[StorageManageScreen]）桥接：分项统计、清除确认、刷新、
 * 清空全部、视频播放中删除保护（CacheManageViewModel 内置）业务逻辑保留，
 * UI 收敛到受控组件（GlassTopAppBar / MetricGrid / SettingsClickRow 风格分项行 / 弹窗族）。
 */
class StorageManageActivity : BaseActivity<ActivityStorageManageBinding>() {

    override val binding by viewBinding(ActivityStorageManageBinding::inflate)
    private val viewModel by viewModels<StorageManageViewModel>()

    // Compose 桥接状态
    private var composeItems by mutableStateOf(listOf<StorageManageDisplayItem>())
    private var composeTotalSize by mutableStateOf("")
    private var composeIsLoading by mutableStateOf(true)
    private var composeError by mutableStateOf<String?>(null)
    // 原始分项数据（删除需用 deletePaths）
    private var currentDetails = listOf<CacheStorageDetail>()

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        loadData()
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                StorageManageScreen(
                    items = composeItems,
                    totalSize = composeTotalSize,
                    isLoading = composeIsLoading,
                    loadError = composeError,
                    onBack = { finish() },
                    onItemClick = { /* 详情弹窗由 Screen 内部 AppTextDialog 管理 */ },
                    onDeleteConfirm = { index -> confirmDelete(index) },
                    onClearAllConfirm = { confirmClearAll() },
                    onRefresh = { loadData() }
                )
            }
        }
    }

    private fun loadData() {
        composeIsLoading = true
        composeError = null
        viewModel.buildStorageBreakdown().onSuccess { list ->
            composeIsLoading = false
            currentDetails = list
            composeItems = list.map { detail ->
                StorageManageDisplayItem(
                    name = getString(detail.nameRes),
                    size = formatBytes(detail.bytes),
                    path = detail.deletePaths.firstOrNull().orEmpty(),
                    detailText = getString(R.string.cache_total, formatBytes(detail.bytes), 1) +
                        "\n" + (detail.deletePaths.firstOrNull().orEmpty())
                )
            }
            composeTotalSize = formatBytes(list.sumOf { it.bytes })
        }.onError {
            composeIsLoading = false
            composeError = getString(R.string.error) + it.localizedMessage
        }
    }

    private fun confirmDelete(index: Int) {
        currentDetails.getOrNull(index) ?: return
        viewModel.deleteStorageTarget(currentDetails[index]).onSuccess { success ->
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

    private fun confirmClearAll() {
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
}
