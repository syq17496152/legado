package io.legado.app.ui.config

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.service.relay.RelayConfig
import io.legado.app.service.relay.RelayConnectionState
import io.legado.app.service.relay.RelayControlClient
import io.legado.app.service.relay.RelaySecretStore
import io.legado.app.service.relay.RelayService
import io.legado.app.service.relay.RelayShareResult
import io.legado.app.service.relay.RelayStateRepository
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.sendToClip
import io.legado.app.utils.shareWithQr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RelaySettingsActivity : BaseActivity<ViewBinding>() {
    override val binding: ViewBinding by lazy {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        object : ViewBinding {
            override fun getRoot(): View = root
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val prefs = defaultSharedPreferences
        val identityId = if (RelaySecretStore.isSupported) {
            runCatching {
                RelaySecretStore(this).loadOrCreate().let { identity ->
                    try {
                        identity.deviceId
                    } finally {
                        identity.secret.fill(0)
                    }
                }
            }.getOrDefault("")
        } else {
            ""
        }
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                RelaySettingsScreen(
                    initialWorkerUrl = prefs.getString(PreferKey.publicWebRelayWorkerUrl, "").orEmpty(),
                    initialDeviceName = prefs.getString(PreferKey.publicWebRelayDeviceName, "").orEmpty(),
                    initialEnabled = prefs.getBoolean(PreferKey.publicWebRelayEnabled, false),
                    initialPermanentShare = prefs.getBoolean(PreferKey.publicWebRelayPermanentShare, false),
                    initialShareProgressSync = prefs.getBoolean(PreferKey.publicWebRelayShareProgressSync, false),
                    deviceId = identityId,
                    secureStorageSupported = RelaySecretStore.isSupported,
                    onBack = ::finish,
                    onEnabledChange = ::setEnabled,
                    onReconnect = ::reconnect,
                    onProvision = ::provision,
                    onCreateShare = ::createShare,
                    onCopyShare = { sendToClip(it) },
                    onShareQr = { shareWithQr(it, getString(R.string.public_web_relay_share_qr)) },
                    onRevokeShare = ::revokeShare,
                    onTest = ::testConnection,
                    onShareOptionsChange = { permanent, progressSync ->
                        prefs.edit()
                            .putBoolean(PreferKey.publicWebRelayPermanentShare, permanent)
                            .putBoolean(PreferKey.publicWebRelayShareProgressSync, progressSync)
                            .apply()
                    },
                    onOpenBatterySettings = ::openBatterySettings,
                )
            }
        }
        (binding.root as FrameLayout).addView(composeView)
    }

    private fun openBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        }
    }

    private fun saveInputs(workerUrl: String, deviceName: String) {
        defaultSharedPreferences.edit()
            .putString(PreferKey.publicWebRelayWorkerUrl, workerUrl.trim())
            .putString(PreferKey.publicWebRelayDeviceName, deviceName.trim().take(64))
            .apply()
    }

    private fun setEnabled(enabled: Boolean, workerUrl: String, deviceName: String): Boolean {
        saveInputs(workerUrl, deviceName)
        if (!enabled) {
            RelayService.stop(this)
            return true
        }
        val config = RelayConfig.load(this).getOrElse {
            toastOnUi(it.message ?: getString(R.string.public_web_relay_invalid_config))
            return false
        }
        runCatching { config.requireDeviceHandle() }.getOrElse {
            config.identity.secret.fill(0)
            toastOnUi(it.message ?: getString(R.string.public_web_relay_invalid_config))
            return false
        }
        defaultSharedPreferences.edit()
            .putBoolean(PreferKey.publicWebRelayEnabled, true)
            .apply()
        RelayService.start(this)
        config.identity.secret.fill(0)
        return true
    }

    private fun testConnection(workerUrl: String, deviceName: String) {
        saveInputs(workerUrl, deviceName)
        val config = RelayConfig.load(this).getOrElse {
            toastOnUi(it.message ?: getString(R.string.public_web_relay_invalid_config))
            return
        }
        runCatching { config.requireDeviceHandle() }.getOrElse {
            config.identity.secret.fill(0)
            toastOnUi(it.message ?: getString(R.string.public_web_relay_invalid_config))
            return
        }
        config.identity.secret.fill(0)
        if (defaultSharedPreferences.getBoolean(PreferKey.publicWebRelayEnabled, false)) {
            RelayService.start(this)
        } else {
            RelayService.test(this)
        }
    }

    private fun reconnect(workerUrl: String, deviceName: String) {
        saveInputs(workerUrl, deviceName)
        val config = RelayConfig.load(this).getOrElse {
            toastOnUi(it.message ?: getString(R.string.public_web_relay_invalid_config))
            return
        }
        runCatching { config.requireDeviceHandle() }.getOrElse {
            config.identity.secret.fill(0)
            toastOnUi(it.message ?: getString(R.string.public_web_relay_invalid_config))
            return
        }
        config.identity.secret.fill(0)
        RelayService.start(this)
    }

    private fun provision(
        workerUrl: String,
        deviceName: String,
        adminToken: String,
        onResult: (Boolean, String) -> Unit
    ) {
        saveInputs(workerUrl, deviceName)
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val config = RelayConfig.load(this@RelaySettingsActivity).getOrThrow()
                    RelayControlClient(config).let { client ->
                        try {
                            client.provision(adminToken.toCharArray())
                        } finally {
                            client.close()
                        }
                    }
                }
            }
            result.getOrNull()?.let { handle ->
                val normalizedWorkerUrl = RelayConfig.normalizeWorkerUrl(workerUrl).toString().trimEnd('/')
                defaultSharedPreferences.edit()
                    .putString(PreferKey.publicWebRelayDeviceHandle, handle)
                    .putString(PreferKey.publicWebRelayPairedWorkerUrl, normalizedWorkerUrl)
                    .apply()
                if (defaultSharedPreferences.getBoolean(PreferKey.publicWebRelayEnabled, false)) {
                    RelayService.start(this@RelaySettingsActivity)
                }
            }
            onResult(result.isSuccess, result.exceptionOrNull()?.message ?: getString(R.string.public_web_relay_paired))
        }
    }

    private fun createShare(
        workerUrl: String,
        deviceName: String,
        permanent: Boolean,
        allowProgress: Boolean,
        onResult: (RelayShareResult?, String) -> Unit
    ) {
        saveInputs(workerUrl, deviceName)
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val config = RelayConfig.load(this@RelaySettingsActivity).getOrThrow()
                    RelayControlClient(config).let { client ->
                        try {
                            client.createReadShare(
                                permanent = permanent,
                                allowProgress = allowProgress
                            )
                        } finally {
                            client.close()
                        }
                    }
                }
            }
            onResult(
                result.getOrNull(),
                result.exceptionOrNull()?.message ?: getString(R.string.public_web_relay_share_created)
            )
        }
    }

    private fun revokeShare(id: String, onResult: (Boolean, String) -> Unit) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val config = RelayConfig.load(this@RelaySettingsActivity).getOrThrow()
                    RelayControlClient(config).let { client ->
                        try { client.revokeShare(id) } finally { client.close() }
                    }
                }
            }
            onResult(result.isSuccess, result.exceptionOrNull()?.message ?: getString(R.string.public_web_relay_share_revoked))
        }
    }
}

@Composable
private fun RelaySettingsScreen(
    initialWorkerUrl: String,
    initialDeviceName: String,
    initialEnabled: Boolean,
    initialPermanentShare: Boolean,
    initialShareProgressSync: Boolean,
    deviceId: String,
    secureStorageSupported: Boolean,
    onBack: () -> Unit,
    onEnabledChange: (Boolean, String, String) -> Boolean,
    onReconnect: (String, String) -> Unit,
    onProvision: (String, String, String, (Boolean, String) -> Unit) -> Unit,
    onCreateShare: (String, String, Boolean, Boolean, (RelayShareResult?, String) -> Unit) -> Unit,
    onCopyShare: (String) -> Unit,
    onShareQr: (String) -> Unit,
    onRevokeShare: (String, (Boolean, String) -> Unit) -> Unit,
    onTest: (String, String) -> Unit,
    onShareOptionsChange: (Boolean, Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    val connectionState by RelayStateRepository.state.collectAsStateWithLifecycle()
    var workerUrl by rememberSaveable { mutableStateOf(initialWorkerUrl) }
    var deviceName by rememberSaveable { mutableStateOf(initialDeviceName) }
    var enabled by rememberSaveable { mutableStateOf(initialEnabled) }
    var adminToken by remember { mutableStateOf("") }
    var shareUrl by remember { mutableStateOf("") }
    var shareId by remember { mutableStateOf("") }
    var permanentShare by rememberSaveable { mutableStateOf(initialPermanentShare) }
    var shareProgressSync by rememberSaveable { mutableStateOf(initialShareProgressSync) }
    var operationMessage by remember { mutableStateOf("") }
    val stateText = relayStateText(connectionState)
    val invalidAdminTokenMessage = stringResource(R.string.public_web_relay_admin_token_invalid)

    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.settings.bodyFontFamily)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = palette.settings.page,
            contentColor = palette.settings.primaryText
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                RelayTopBar(onBack)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 20.dp)
                ) {
                    AppSettingSectionTitle(
                        title = stringResource(R.string.public_web_relay_connection),
                        palette = palette.settings
                    )
                    AppManagementCard(palette = palette) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.public_web_relay_enable),
                                    color = palette.settings.primaryText,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stateText,
                                    color = palette.settings.secondaryText,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                            LegadoMiuixSwitch(
                                checked = enabled,
                                enabled = secureStorageSupported,
                                onCheckedChange = { requested ->
                                    if (onEnabledChange(requested, workerUrl, deviceName)) enabled = requested
                                },
                                palette = palette.miuix
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        RelayTextField(
                            value = workerUrl,
                            onValueChange = {
                                workerUrl = it.take(2048)
                            },
                            label = stringResource(R.string.public_web_relay_worker_url),
                            placeholder = "https://read.example.com",
                            keyboardType = KeyboardType.Uri
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        RelayTextField(
                            value = deviceName,
                            onValueChange = {
                                deviceName = it.take(64)
                            },
                            label = stringResource(R.string.public_web_relay_device_name),
                            placeholder = stringResource(R.string.public_web_relay_device_name_hint)
                        )
                        if (enabled) {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.public_web_relay_save_reconnect),
                                palette = palette.miuix,
                                onClick = { onReconnect(workerUrl, deviceName) },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                primary = true,
                                cornerRadius = palette.miuix.actionRadius
                            )
                        }
                    }

                    AppSettingSectionTitle(
                        title = stringResource(R.string.public_web_relay_pairing),
                        palette = palette.settings
                    )
                    AppManagementCard(palette = palette) {
                        Text(
                            text = stringResource(R.string.public_web_relay_pairing_summary),
                            color = palette.settings.secondaryText,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        RelayTextField(
                            value = adminToken,
                            onValueChange = { adminToken = it.take(512) },
                            label = stringResource(R.string.public_web_relay_admin_token),
                            placeholder = stringResource(R.string.public_web_relay_admin_token_hint),
                            visualTransformation = PasswordVisualTransformation()
                        )
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.public_web_relay_pair),
                            palette = palette.miuix,
                            onClick = {
                                if (adminToken.length < 32) {
                                    operationMessage = invalidAdminTokenMessage
                                } else {
                                    onProvision(workerUrl, deviceName, adminToken) { success, message ->
                                        if (success) adminToken = ""
                                        operationMessage = message
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            primary = true,
                            cornerRadius = palette.miuix.actionRadius
                        )
                        if (operationMessage.isNotBlank()) {
                            Text(
                                text = operationMessage,
                                color = palette.settings.secondaryText,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }

                    AppSettingSectionTitle(
                        title = stringResource(R.string.public_web_relay_status),
                        palette = palette.settings
                    )
                    AppManagementCard(palette = palette) {
                        RelayInfoLine(stringResource(R.string.public_web_relay_status), stateText)
                        RelayInfoLine(
                            stringResource(R.string.public_web_relay_device_id),
                            deviceId.ifBlank { stringResource(R.string.public_web_relay_unavailable) }
                        )
                        RelayOptionSwitch(
                            title = stringResource(R.string.public_web_relay_permanent_share),
                            checked = permanentShare,
                            onCheckedChange = {
                                permanentShare = it
                                onShareOptionsChange(permanentShare, shareProgressSync)
                            },
                            palette = palette
                        )
                        RelayOptionSwitch(
                            title = stringResource(R.string.public_web_relay_progress_sync),
                            summary = stringResource(R.string.public_web_relay_progress_sync_summary),
                            checked = shareProgressSync,
                            onCheckedChange = {
                                shareProgressSync = it
                                onShareOptionsChange(permanentShare, shareProgressSync)
                            },
                            palette = palette
                        )
                        Text(
                            text = stringResource(R.string.public_web_relay_share_options_new_link),
                            color = palette.settings.secondaryText,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.public_web_relay_test),
                                palette = palette.miuix,
                                onClick = { onTest(workerUrl, deviceName) },
                                modifier = Modifier.weight(1f),
                                cornerRadius = palette.miuix.actionRadius
                            )
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.public_web_relay_create_share),
                                palette = palette.miuix,
                                onClick = {
                                    onCreateShare(
                                        workerUrl,
                                        deviceName,
                                        permanentShare,
                                        shareProgressSync
                                    ) { share, message ->
                                        shareUrl = share?.shareUrl.orEmpty()
                                        shareId = share?.id.orEmpty()
                                        operationMessage = message
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                primary = true,
                                cornerRadius = palette.miuix.actionRadius
                            )
                        }
                        if (shareUrl.isNotBlank()) {
                            Text(
                                text = shareUrl,
                                color = palette.settings.secondaryText,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.public_web_relay_copy_share),
                                palette = palette.miuix,
                                onClick = { onCopyShare(shareUrl) },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                cornerRadius = palette.miuix.actionRadius
                            )
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.public_web_relay_share_qr),
                                palette = palette.miuix,
                                onClick = { onShareQr(shareUrl) },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                cornerRadius = palette.miuix.actionRadius
                            )
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.public_web_relay_revoke_share),
                                palette = palette.miuix,
                                onClick = {
                                    onRevokeShare(shareId) { success, message ->
                                        if (success) {
                                            shareUrl = ""
                                            shareId = ""
                                        }
                                        operationMessage = message
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                danger = true,
                                cornerRadius = palette.miuix.actionRadius
                            )
                        }
                    }

                    AppSettingSectionTitle(
                        title = stringResource(R.string.public_web_relay_security),
                        palette = palette.settings
                    )
                    AppManagementCard(palette = palette) {
                        Text(
                            text = stringResource(R.string.public_web_relay_security_summary),
                            color = palette.settings.secondaryText,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        if (android.os.Build.VERSION.SDK_INT >= 35) {
                            Text(
                                text = stringResource(R.string.public_web_relay_android_limit),
                                color = palette.settings.danger,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.public_web_relay_vivo_background_hint),
                            color = palette.settings.secondaryText,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.public_web_relay_open_battery_settings),
                            palette = palette.miuix,
                            onClick = onOpenBatterySettings,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            cornerRadius = palette.miuix.actionRadius
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelayTopBar(onBack: () -> Unit) {
    val palette = rememberAppManagementPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(42.dp)
                .clickable(onClick = onBack),
            shape = RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp),
            color = Color.Transparent
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = palette.settings.primaryText,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = stringResource(R.string.public_web_relay),
            color = palette.settings.primaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.settings.titleFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(42.dp))
    }
}

@Composable
private fun RelayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val palette = rememberAppManagementPalette()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.settings.primaryText,
            unfocusedTextColor = palette.settings.primaryText,
            focusedBorderColor = palette.settings.accent,
            unfocusedBorderColor = palette.settings.secondaryText.copy(alpha = 0.45f),
            focusedLabelColor = palette.settings.accent,
            unfocusedLabelColor = palette.settings.secondaryText,
            cursorColor = palette.settings.accent
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RelayOptionSwitch(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    palette: AppManagementPalette
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = palette.settings.primaryText, fontSize = 14.sp)
            summary?.let {
                Text(
                    text = it,
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        LegadoMiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            palette = palette.miuix
        )
    }
}

@Composable
private fun RelayInfoLine(label: String, value: String) {
    val palette = rememberAppManagementPalette()
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = palette.settings.secondaryText, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = palette.settings.primaryText,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun relayStateText(state: RelayConnectionState): String = when (state) {
    RelayConnectionState.Disabled -> stringResource(R.string.public_web_relay_disabled)
    RelayConnectionState.UnsupportedPlatform -> stringResource(R.string.public_web_relay_unsupported)
    RelayConnectionState.WaitingForNetwork -> stringResource(R.string.public_web_relay_waiting_network)
    RelayConnectionState.Authenticating -> stringResource(R.string.public_web_relay_authenticating)
    is RelayConnectionState.Connecting -> stringResource(R.string.public_web_relay_connecting_attempt, state.attempt)
    is RelayConnectionState.Connected -> stringResource(R.string.public_web_relay_connected_latency, state.latencyMillis)
    is RelayConnectionState.TestSucceeded -> stringResource(R.string.public_web_relay_test_succeeded_latency, state.latencyMillis)
    is RelayConnectionState.Reconnecting -> stringResource(
        R.string.public_web_relay_reconnecting,
        (state.delayMillis / 1000L).coerceAtLeast(1L)
    )
    is RelayConnectionState.ConfigurationError -> stringResource(
        R.string.public_web_relay_config_error,
        state.reason
    )
    is RelayConnectionState.Failed -> stringResource(R.string.public_web_relay_failed, state.reason)
}
