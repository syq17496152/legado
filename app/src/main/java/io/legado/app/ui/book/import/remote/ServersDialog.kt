package io.legado.app.ui.book.import.remote

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.constant.AppConst.DEFAULT_WEBDAV_ID
import io.legado.app.data.appDb
import io.legado.app.data.entities.Server
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.flow.conflate

/**
 * 服务器配置（D1 P0 迁移：BaseDialogFragment 旧 View 弹框 → ComposeDialogFragment，随主题全量纳管）
 */
class ServersDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management
    override val dialogGravity: Int = Gravity.CENTER
    override val dialogWindowAnimations: Int = R.style.AnimDialogFade

    val viewModel by viewModels<ServersViewModel>()

    private val callback get() = (activity as? Callback)
    private var selectServerId by mutableStateOf(AppConfig.remoteServerId)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val servers by appDb.serverDao.observeAll()
                    .conflate()
                    .collectAsState(initial = emptyList())
                ServersPanel(
                    servers = servers,
                    selectServerId = selectServerId,
                    onSelectChange = { selectServerId = it },
                    onAdd = { showDialogFragment(ServerConfigDialog()) },
                    onEdit = { server -> showDialogFragment(ServerConfigDialog(server.id)) },
                    onDelete = { server -> confirmDeleteServer(server) },
                    onUseDefault = {
                        AppConfig.remoteServerId = DEFAULT_WEBDAV_ID
                        dismissAllowingStateLoss()
                    },
                    onCancel = { dismissAllowingStateLoss() },
                    onConfirm = {
                        AppConfig.remoteServerId = selectServerId
                        dismissAllowingStateLoss()
                    }
                )
            }
        }
    }

    private fun confirmDeleteServer(server: Server) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = { viewModel.delete(server) }
        )
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        callback?.onDialogDismiss("serversDialog")
    }

    interface Callback {

        fun onDialogDismiss(tag: String)

    }

}

@Composable
private fun ServersPanel(
    servers: List<Server>,
    selectServerId: Long,
    onSelectChange: (Long) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Server) -> Unit,
    onDelete: (Server) -> Unit,
    onUseDefault: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    val style = rememberAppDialogStyle()
    AppDialogFrame(
        title = stringResource(R.string.server_config),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (servers.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onAdd)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.add),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                    ) {
                        items(servers, key = { it.id }) { server ->
                            ServersRow(
                                server = server,
                                selected = server.id == selectServerId,
                                onClick = { onSelectChange(server.id) },
                                onEdit = { onEdit(server) },
                                onDelete = { onDelete(server) }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.text_default),
                        modifier = Modifier
                            .clickable(onClick = onUseDefault)
                            .padding(vertical = 8.dp),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier
                            .clickable(onClick = onCancel)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = style.accent
                    )
                    Text(
                        text = stringResource(R.string.confirm),
                        modifier = Modifier
                            .clickable(onClick = onConfirm)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = style.accent
                    )
                }
            }
        },
        actions = { },
        scrollContent = false
    )
}

@Composable
private fun ServersRow(
    server: Server,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(
            text = server.name,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            maxLines = 1,
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
        )
        IconButton(onClick = onEdit) {
            Icon(imageVector = Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete)
            )
        }
    }
}