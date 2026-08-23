package io.legado.app.ui.config

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityS3ContainerManageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.cloud.S3CloudStorageBackend
import io.legado.app.lib.cloud.S3Config
import io.legado.app.lib.cloud.S3Container
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextFormDialogWithChecks
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

class S3ContainerManageActivity : BaseActivity<ActivityS3ContainerManageBinding>() {

    override val binding by viewBinding(ActivityS3ContainerManageBinding::inflate)

    private val containersState = mutableStateOf<List<S3Container>>(emptyList())
    private val waitDialog by lazy { WaitDialog(this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        waitDialog.dismiss()
    }

    private fun initComposeContent() {
        val container = binding.root as? ViewGroup ?: return
        container.removeAllViews()
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                S3ContainerManageScreen(
                    containers = containersState.value,
                    onBack = { finish() },
                    onAdd = { showEditDialog(null) },
                    onItemClick = { showEditDialog(it) },
                    onMoreActions = ::containerActions
                )
            }
        }
        container.addView(cv)
    }

    private fun reload() {
        containersState.value = AppCloudStorage.listContainers()
    }

    private fun showEditDialog(item: S3Container? = null) {
        showComposeTextFormDialogWithChecks(
            title = getString(if (item == null) R.string.s3_container_add else R.string.s3_container_edit),
            labels = listOf(
                getString(R.string.s3_container_name),
                getString(R.string.s3_endpoint),
                getString(R.string.s3_bucket),
                getString(R.string.s3_access_key),
                getString(R.string.s3_secret_key),
                getString(R.string.s3_container_capacity_gb),
                getString(R.string.s3_prefix),
                getString(R.string.s3_region),
                getString(R.string.s3_session_token)
            ),
            initialValues = listOf(
                item?.name.orEmpty(),
                item?.endpoint.orEmpty(),
                item?.bucket.orEmpty(),
                item?.accessKey.orEmpty(),
                item?.secretKey.orEmpty(),
                capacityMbToGbText(item?.capacityMb ?: DEFAULT_CAPACITY_MB),
                item?.prefix ?: "legado",
                item?.region ?: "auto",
                item?.sessionToken.orEmpty()
            ),
            passwordFields = setOf(FIELD_SECRET_KEY, FIELD_SESSION_TOKEN),
            checkboxLabels = listOf(
                getString(R.string.s3_container_enabled),
                getString(R.string.s3_path_style)
            ),
            checkedIndices = listOfNotNull(
                if (item?.enabled ?: true) CHECK_ENABLED else null,
                if (item?.pathStyle ?: true) CHECK_PATH_STYLE else null
            ).toSet(),
            onPositive = { values, checks ->
                saveDialogItem(item, values, checks)?.let { saved ->
                    if (item == null) refreshCapacity(saved, showWait = false)
                }
            }
        )
    }

    private fun saveDialogItem(
        oldItem: S3Container?,
        fields: List<String>,
        checks: BooleanArray
    ): S3Container? {
        val pathStyle = checks.checkedAt(CHECK_PATH_STYLE, default = true)
        val parsed = S3Config.parseAddress(
            fields.fieldAt(FIELD_ENDPOINT),
            fields.fieldAt(FIELD_BUCKET),
            fields.fieldAt(FIELD_REGION),
            pathStyle
        )
        val capacityMb = gbTextToCapacityMb(fields.fieldAt(FIELD_CAPACITY))
        val usedBytes = oldItem?.usedBytes?.coerceAtLeast(0L) ?: 0L
        if (parsed.endpoint.isBlank() || parsed.bucket.isBlank()) {
            toastOnUi(R.string.s3_container_endpoint_bucket_required)
            return null
        }
        if (fields.fieldAt(FIELD_ACCESS_KEY).isBlank()
            || fields.fieldAt(FIELD_SECRET_KEY).isBlank()
        ) {
            toastOnUi(R.string.s3_container_key_required)
            return null
        }
        val newItem = S3Container(
            id = oldItem?.id ?: S3Container.newId(),
            name = fields.fieldAt(FIELD_NAME).trim().ifBlank { parsed.bucket },
            endpoint = parsed.endpoint,
            bucket = parsed.bucket,
            prefix = fields.fieldAt(FIELD_PREFIX).trim().ifBlank { "legado" },
            region = parsed.region.ifBlank { "auto" },
            accessKey = fields.fieldAt(FIELD_ACCESS_KEY).trim(),
            secretKey = fields.fieldAt(FIELD_SECRET_KEY).trim(),
            sessionToken = fields.fieldAt(FIELD_SESSION_TOKEN).trim().ifBlank { null },
            pathStyle = parsed.pathStyle,
            capacityMb = capacityMb,
            usedBytes = if (capacityMb > 0) usedBytes.coerceAtMost(mbToBytes(capacityMb)) else usedBytes,
            lastRefreshTime = oldItem?.lastRefreshTime ?: 0L,
            isFull = capacityMb > 0 && usedBytes >= mbToBytes(capacityMb),
            enabled = checks.checkedAt(CHECK_ENABLED, default = true)
        )
        AppCloudStorage.addContainer(newItem)
        if (AppCloudStorage.selectedContainer(S3ContainerScope.DEFAULT) == null) {
            AppCloudStorage.selectContainer(S3ContainerScope.DEFAULT, newItem.id)
        }
        reload()
        return newItem
    }

    private fun containerActions(item: S3Container): List<AppManagementMenuAction> {
        val actions = listOf(
            Action.EDIT,
            Action.TEST,
            Action.REFRESH,
            Action.SET_DEFAULT,
            if (item.enabled) Action.DISABLE else Action.ENABLE,
            Action.DELETE
        )
        return actions.map { action ->
            AppManagementMenuAction(
                text = getString(action.titleRes),
                danger = action == Action.DELETE
            ) {
                when (action) {
                    Action.EDIT -> showEditDialog(item)
                    Action.TEST -> testConnection(item)
                    Action.REFRESH -> refreshCapacity(item)
                    Action.SET_DEFAULT -> {
                        if (!item.enabled) {
                            toastOnUi(R.string.s3_container_disabled)
                        } else {
                            AppCloudStorage.selectContainer(S3ContainerScope.DEFAULT, item.id)
                            toastOnUi(R.string.s3_container_set_default_success)
                            reload()
                        }
                    }
                    Action.ENABLE -> updateItem(item.copy(enabled = true, isFull = false))
                    Action.DISABLE -> updateItem(item.copy(enabled = false))
                    Action.DELETE -> confirmDelete(item)
                }
            }
        }
    }

    private fun updateItem(item: S3Container) {
        AppCloudStorage.updateContainer(item)
        reload()
    }

    private fun confirmDelete(item: S3Container) {
        showComposeConfirmDialog(
            title = getString(R.string.s3_container_delete),
            message = getString(
                R.string.s3_container_delete_confirm,
                AppCloudStorage.containerDisplayLabel(item)
            ),
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = {
                AppCloudStorage.deleteContainer(item.id)
                reload()
            }
        )
    }

    private fun testConnection(item: S3Container) {
        waitDialog.setText(R.string.loading)
        waitDialog.show()
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    S3CloudStorageBackend(S3ContainerScope.DEFAULT, item.id).check()
                }
            }.onSuccess {
                toastOnUi(R.string.s3_container_test_success)
            }.onFailure {
                toastOnUi(getString(R.string.s3_container_test_failed, it.localizedMessage.orEmpty()))
            }
            waitDialog.dismiss()
        }
    }

    private fun refreshCapacity(item: S3Container, showWait: Boolean = true) {
        if (showWait) {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AppCloudStorage.refreshUsage(item.id) }
            }.onSuccess {
                toastOnUi(R.string.s3_container_capacity_refreshed)
                reload()
            }.onFailure {
                toastOnUi(it.localizedMessage.orEmpty())
            }
            if (showWait) waitDialog.dismiss()
        }
    }

    private enum class Action(val titleRes: Int) {
        EDIT(R.string.s3_container_edit),
        TEST(R.string.s3_container_test),
        REFRESH(R.string.s3_container_refresh_capacity),
        SET_DEFAULT(R.string.s3_container_set_default),
        ENABLE(R.string.s3_container_enable),
        DISABLE(R.string.s3_container_disable),
        DELETE(R.string.s3_container_delete)
    }

    internal companion object {
        const val DEFAULT_CAPACITY_MB = 5L * 1024L
        private const val FIELD_NAME = 0
        private const val FIELD_ENDPOINT = 1
        private const val FIELD_BUCKET = 2
        private const val FIELD_ACCESS_KEY = 3
        private const val FIELD_SECRET_KEY = 4
        private const val FIELD_CAPACITY = 5
        private const val FIELD_PREFIX = 6
        private const val FIELD_REGION = 7
        private const val FIELD_SESSION_TOKEN = 8
        private const val CHECK_ENABLED = 0
        private const val CHECK_PATH_STYLE = 1

        private fun List<String>.fieldAt(index: Int): String = getOrNull(index).orEmpty()

        private fun BooleanArray.checkedAt(index: Int, default: Boolean): Boolean {
            return if (index in indices) this[index] else default
        }

        internal fun mbToBytes(value: Long): Long = value.coerceAtLeast(0L) * 1024L * 1024L
        private fun gbTextToCapacityMb(value: String): Long {
            val gb = value.trim().toDoubleOrNull() ?: return 0L
            if (gb <= 0.0) return 0L
            return max(ceil(gb * 1024.0).toLong(), 1L)
        }

        private fun capacityMbToGbText(value: Long): String {
            if (value <= 0L) return ""
            val gb = value / 1024.0
            return if (value % 1024L == 0L) {
                (value / 1024L).toString()
            } else {
                String.format(Locale.US, "%.2f", gb).trimEnd('0').trimEnd('.')
            }
        }

        internal fun formatBytes(value: Long): String {
            val bytes = value.coerceAtLeast(0L).toDouble()
            val gb = bytes / 1024.0 / 1024.0 / 1024.0
            return if (gb >= 1.0) {
                "${formatDecimal(gb)} GB"
            } else {
                val mb = bytes / 1024.0 / 1024.0
                "${max(mb.toLong(), 0L)} MB"
            }
        }

        private fun formatDecimal(value: Double): String {
            return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }

    }
}
