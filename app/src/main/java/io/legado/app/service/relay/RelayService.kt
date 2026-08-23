package io.legado.app.service.relay

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.ui.config.RelaySettingsActivity
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RelayService : BaseService() {
    companion object {
        private const val ACTION_START = "io.legado.app.relay.START"
        private const val ACTION_STOP = "io.legado.app.relay.STOP"
        private const val ACTION_TEST = "io.legado.app.relay.TEST"

        fun start(context: Context) {
            startAction(context, ACTION_START)
        }

        fun test(context: Context) {
            startAction(context, ACTION_TEST)
        }

        fun stop(context: Context) {
            context.defaultSharedPreferences.edit()
                .putBoolean(PreferKey.publicWebRelayEnabled, false)
                .apply()
            runCatching { context.startService(Intent(context, RelayService::class.java).setAction(ACTION_STOP)) }
            context.stopService(Intent(context, RelayService::class.java))
            RelayStateRepository.update(RelayConnectionState.Disabled)
        }

        private fun startAction(context: Context, action: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RelayService::class.java).setAction(action)
                )
            }.onFailure {
                RelayStateRepository.update(
                    RelayConnectionState.Failed(it.javaClass.simpleName.ifBlank { "service_start_failed" })
                )
            }
        }
    }

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var relayClient: RelayClient? = null
    private var stateJob: Job? = null
    private var testTimeoutJob: Job? = null
    private var testCompletionJob: Job? = null
    private var callbackRegistered = false
    private var testOnly = false
    private var startGeneration = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork()
        override fun onLost(network: Network) = updateNetwork()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateNetwork()
    }

    override fun onCreate() {
        super.onCreate()
        registerNetworkCallback()
        stateJob = lifecycleScope.launch {
            RelayStateRepository.state.collectLatest { state ->
                if (state != RelayConnectionState.Disabled) startForegroundNotification()
                if (state is RelayConnectionState.Connected && testOnly) {
                    val generation = startGeneration
                    testCompletionJob?.cancel()
                    testCompletionJob = lifecycleScope.launch {
                        delay(1_000)
                        if (testOnly && generation == startGeneration) {
                            RelayStateRepository.update(RelayConnectionState.TestSucceeded(state.latencyMillis))
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startGeneration++
        testTimeoutJob?.cancel()
        testTimeoutJob = null
        testCompletionJob?.cancel()
        testCompletionJob = null
        when (intent?.action) {
            ACTION_STOP -> {
                defaultSharedPreferences.edit()
                    .putBoolean(PreferKey.publicWebRelayEnabled, false)
                    .apply()
                RelayStateRepository.update(RelayConnectionState.Disabled)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST -> testOnly = true
            else -> testOnly = false
        }
        if (testOnly) releaseWakeLock() else acquireWakeLock()
        startRelay()
        return if (testOnly) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        testTimeoutJob?.cancel()
        testCompletionJob?.cancel()
        stateJob?.cancel()
        relayClient?.stop()
        relayClient = null
        releaseWakeLock()
        if (callbackRegistered) runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        callbackRegistered = false
        if (!defaultSharedPreferences.getBoolean(PreferKey.publicWebRelayEnabled, false) && !testOnly) {
            RelayStateRepository.update(RelayConnectionState.Disabled)
        }
        super.onDestroy()
    }

    override fun startForegroundNotification() {
        val settingsIntent = Intent(this, RelaySettingsActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            NotificationId.PublicWebRelayService,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            NotificationId.PublicWebRelayService + 1,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stateText = when (RelayStateRepository.state.value) {
            RelayConnectionState.WaitingForNetwork -> getString(R.string.public_web_relay_waiting_network)
            is RelayConnectionState.Connected -> getString(R.string.public_web_relay_connected)
            is RelayConnectionState.TestSucceeded -> getString(R.string.public_web_relay_test_succeeded)
            else -> getString(R.string.public_web_relay_connecting)
        }
        val notification = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle(getString(R.string.public_web_relay))
            .setContentText(stateText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(R.drawable.ic_stop_black_24dp, getString(R.string.cancel), stopIntent)
            .build()
        startForeground(NotificationId.PublicWebRelayService, notification)
    }

    private fun startRelay() {
        relayClient?.stop()
        relayClient = null
        val result = RelayConfig.load(this)
        result.onFailure {
            RelayStateRepository.update(
                if (!RelaySecretStore.isSupported) RelayConnectionState.UnsupportedPlatform
                else RelayConnectionState.ConfigurationError(it.message ?: "invalid_configuration")
            )
            stopSelf()
        }.onSuccess { config ->
            runCatching { config.requireDeviceHandle() }.onFailure {
                config.identity.secret.fill(0)
                RelayStateRepository.update(RelayConnectionState.ConfigurationError(it.message.orEmpty()))
                stopSelf()
                return@onSuccess
            }
            relayClient = RelayClient(config).also { it.start(hasUsableNetwork()) }
            if (testOnly) {
                testTimeoutJob?.cancel()
                testTimeoutJob = lifecycleScope.launch {
                    delay(30_000)
                    if (RelayStateRepository.state.value !is RelayConnectionState.Connected) {
                        RelayStateRepository.update(RelayConnectionState.Failed("connection_test_timeout"))
                    }
                    stopSelf()
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, networkCallback) }
            .onSuccess { callbackRegistered = true }
            .onFailure { RelayStateRepository.update(RelayConnectionState.Failed("network_callback_failed")) }
    }

    private fun updateNetwork() {
        relayClient?.updateNetworkAvailable(hasUsableNetwork())
    }

    private fun hasUsableNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:publicWebRelay"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }
}
