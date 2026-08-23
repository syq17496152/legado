package io.legado.app.service.relay

import io.legado.app.utils.GSON
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import okio.ByteString.Companion.toByteString

internal class RelayClient(
    private val config: RelayConfig,
    private val stateRepository: RelayStateRepository = RelayStateRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkAvailable = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val currentSocket = AtomicReference<WebSocket?>()
    private val requestBudget = RelayRequestBudget()
    private val requestJobs = ConcurrentHashMap<Long, Job>()
    private val creditWindows = ConcurrentHashMap<Long, RelayCreditWindow>()
    private var loopJob: Job? = null

    fun start(initialNetworkAvailable: Boolean) {
        if (loopJob != null) return
        networkAvailable.value = initialNetworkAvailable
        loopJob = scope.launch { connectionLoop() }
    }

    fun updateNetworkAvailable(available: Boolean) {
        networkAvailable.value = available
        if (!available) {
            cleanupActiveRequests()
            currentSocket.getAndSet(null)?.cancel()
        }
    }

    fun stop() {
        currentSocket.getAndSet(null)?.close(1000, "client stopped")
        cleanupActiveRequests()
        config.identity.secret.fill(0)
        scope.cancel()
    }

    private suspend fun connectionLoop() {
        val backoff = RelayBackoff()
        var attempt = 0
        while (scope.isActive) {
            if (!networkAvailable.value) {
                stateRepository.update(RelayConnectionState.WaitingForNetwork)
                networkAvailable.filter { it }.first()
                attempt = 0
            }
            attempt++
            stateRepository.update(RelayConnectionState.Connecting(attempt))
            val disconnected = CompletableDeferred<String?>()
            val openedAt = System.currentTimeMillis()
            val listener = createListener(disconnected, openedAt) { backoff.reset() }
            val connectProof = RelayAuthenticator.createConnectProof(config.identity)
            val request = Request.Builder()
                .url(config.socketUrl)
                .header("User-Agent", "Legado-Relay/${RelayProtocol.VERSION}")
                .header("x-legado-device-id", config.identity.deviceId)
                .header("x-legado-device-handle", config.requireDeviceHandle())
                .header("x-legado-timestamp", connectProof.timestamp)
                .header("x-legado-nonce", connectProof.nonce)
                .header("x-legado-signature", connectProof.signature)
                .build()
            val socket = client.newWebSocket(request, listener)
            currentSocket.getAndSet(socket)?.cancel()
            if (!networkAvailable.value && currentSocket.compareAndSet(socket, null)) {
                socket.cancel()
            }
            val reason = disconnected.await()
            currentSocket.compareAndSet(socket, null)
            cleanupActiveRequests()
            if (!scope.isActive) break
            if (!networkAvailable.value) continue
            val retryDelay = backoff.nextDelayMillis()
            stateRepository.update(RelayConnectionState.Reconnecting(retryDelay, reason))
            delay(retryDelay)
        }
    }

    private fun createListener(
        disconnected: CompletableDeferred<String?>,
        openedAt: Long,
        onReady: () -> Unit
    ): WebSocketListener {
        val challenged = AtomicBoolean(false)
        val ready = AtomicBoolean(false)
        val challengeEpoch = AtomicLong(0L)
        val readyEpoch = AtomicLong(0L)
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                stateRepository.update(RelayConnectionState.Authenticating)
                sendControl(
                    webSocket,
                    RelayControlMessage(
                        type = "hello",
                        protocolVersion = RelayProtocol.VERSION,
                        minimumProtocolVersion = RelayProtocol.MINIMUM_VERSION,
                        deviceId = config.identity.deviceId,
                        deviceName = config.deviceName
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > RelayProtocol.MAX_CONTROL_BYTES ||
                    text.toByteArray(Charsets.UTF_8).size > RelayProtocol.MAX_CONTROL_BYTES
                ) {
                    protocolFailure(webSocket, disconnected, "control_frame_too_large")
                    return
                }
                val message = runCatching {
                    GSON.fromJson(text, RelayControlMessage::class.java)
                }.getOrNull()
                if (message == null || message.v != RelayProtocol.VERSION || message.type.isBlank()) {
                    protocolFailure(webSocket, disconnected, "invalid_control_frame")
                    return
                }
                when (message.type) {
                    "challenge" -> {
                        if (ready.get()) {
                            protocolFailure(webSocket, disconnected, "challenge_after_ready")
                            return
                        }
                        if (!challenged.compareAndSet(false, true)) {
                            protocolFailure(webSocket, disconnected, "duplicate_challenge")
                            return
                        }
                        val nonce = message.nonce
                        val expiresAt = message.expiresAt
                        val epoch = message.epoch
                        val proof = runCatching {
                            require(message.protocolVersion == RelayProtocol.VERSION)
                            val minimumProtocolVersion = requireNotNull(message.minimumProtocolVersion)
                            require(minimumProtocolVersion in RelayProtocol.MINIMUM_VERSION..RelayProtocol.VERSION)
                            requireNotNull(nonce)
                            requireNotNull(expiresAt)
                            requireNotNull(epoch)
                            require(epoch > 0)
                            require(challengeEpoch.compareAndSet(0L, epoch))
                            RelayAuthenticator.createProof(config.identity, nonce, expiresAt, epoch)
                        }.getOrElse {
                            protocolFailure(webSocket, disconnected, "invalid_challenge")
                            return
                        }
                        sendControl(
                            webSocket,
                            RelayControlMessage(
                                type = "authenticate",
                                deviceId = config.identity.deviceId,
                                nonce = nonce,
                                expiresAt = expiresAt,
                                epoch = epoch,
                                proof = proof
                            )
                        )
                    }

                    "ready" -> {
                        if (!challenged.get() || !ready.compareAndSet(false, true) ||
                            message.protocolVersion != RelayProtocol.VERSION
                        ) {
                            protocolFailure(webSocket, disconnected, "protocol_mismatch")
                            return
                        }
                        val epoch = message.epoch
                        if (epoch == null || epoch <= 0 || epoch != challengeEpoch.get()) {
                            protocolFailure(webSocket, disconnected, "invalid_epoch")
                            return
                        }
                        readyEpoch.set(epoch)
                        onReady()
                        stateRepository.update(
                            RelayConnectionState.Connected(
                                connectedAt = System.currentTimeMillis(),
                                latencyMillis = (System.currentTimeMillis() - openedAt).coerceAtLeast(0)
                            )
                        )
                    }

                    "http_request" -> {
                        if (!ready.get()) protocolFailure(webSocket, disconnected, "request_before_ready")
                        else if (message.epoch != readyEpoch.get() || readyEpoch.get() <= 0) {
                            protocolFailure(webSocket, disconnected, "stale_request_epoch")
                        } else handleHttpRequest(webSocket, message, readyEpoch.get())
                    }
                    "http_request_end" -> {
                        if (!ready.get()) protocolFailure(webSocket, disconnected, "request_before_ready")
                        else if (message.epoch != readyEpoch.get() ||
                            message.requestId?.let(requestBudget::contains) != true
                        ) protocolFailure(webSocket, disconnected, "invalid_request_end")
                    }
                    "credit" -> {
                        if (!ready.get()) protocolFailure(webSocket, disconnected, "credit_before_ready")
                        else {
                            val id = message.requestId
                            val bytes = message.bytes
                            if (message.epoch != readyEpoch.get() || id == null || bytes == null) {
                                protocolFailure(webSocket, disconnected, "invalid_credit")
                            } else {
                                // A final pull can race with http_response_end across the socket.
                                // Ignore credit for an already completed request, but keep strict
                                // validation for every request that still owns an active window.
                                creditWindows[id]?.let { window ->
                                    if (!window.grant(bytes)) {
                                        protocolFailure(webSocket, disconnected, "invalid_credit")
                                    }
                                }
                            }
                        }
                    }
                    "cancel" -> {
                        if (!ready.get()) protocolFailure(webSocket, disconnected, "cancel_before_ready")
                        else if (message.epoch != readyEpoch.get()) {
                            protocolFailure(webSocket, disconnected, "stale_cancel_epoch")
                        } else message.requestId?.let { id ->
                            requestJobs.remove(id)?.cancel()
                            creditWindows.remove(id)?.close()
                            requestBudget.finish(id)
                        }
                    }
                    "ping" -> sendControl(webSocket, RelayControlMessage(type = "pong"))
                    else -> protocolFailure(webSocket, disconnected, "unsupported_control_type")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Protocol v1 exposes read-only GET routes and never accepts request body frames.
                protocolFailure(webSocket, disconnected, "binary_request_not_supported")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                disconnected.complete("closed_$code")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                disconnected.complete(t.javaClass.simpleName)
            }
        }
    }

    private fun handleHttpRequest(webSocket: WebSocket, message: RelayControlMessage, epoch: Long) {
        val requestId = message.requestId
        if (requestId == null || requestId <= 0) {
            webSocket.close(1002, "invalid request id")
            return
        }
        val authorizationError = RelayReadAllowlist.validate(message)
        if (authorizationError != null) {
            sendRequestError(webSocket, requestId, authorizationError, 403, epoch)
            return
        }
        val budgetError = requestBudget.begin(requestId, message.contentLength ?: 0L)
        if (budgetError != null) {
            sendRequestError(webSocket, requestId, budgetError, 429, epoch)
            return
        }
        val credit = RelayCreditWindow()
        creditWindows[requestId] = credit
        val dispatcher = RelayReadDispatcher(
            sendControl = { sendControl(webSocket, it) },
            sendBinary = { webSocket.send(RelayProtocol.encode(it).toByteString()) }
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runCatching {
                kotlinx.coroutines.withTimeout(RelayProtocol.REQUEST_TIMEOUT_MILLIS) {
                    dispatcher.dispatch(message, epoch, credit)
                }
            }
                .onFailure { sendRequestError(webSocket, requestId, "dispatch_failed", 502, epoch) }
            creditWindows.remove(requestId)?.close()
            requestBudget.finish(requestId)
            requestJobs.remove(requestId)
        }
        requestJobs[requestId] = job
        job.start()
    }

    private fun sendRequestError(
        webSocket: WebSocket,
        requestId: Long,
        code: String,
        status: Int,
        epoch: Long? = null
    ) {
        sendControl(
            webSocket,
            RelayControlMessage(
                type = "http_error",
                requestId = requestId,
                epoch = epoch,
                status = status,
                code = code,
                message = "Request rejected by Android relay"
            )
        )
    }

    private fun sendControl(webSocket: WebSocket, message: RelayControlMessage): Boolean {
        val json = GSON.toJson(message)
        if (json.toByteArray(Charsets.UTF_8).size > RelayProtocol.MAX_CONTROL_BYTES) return false
        return webSocket.send(json)
    }

    private fun protocolFailure(
        webSocket: WebSocket,
        disconnected: CompletableDeferred<String?>,
        reason: String
    ) {
        cleanupActiveRequests()
        webSocket.close(1002, reason.take(123))
        disconnected.complete(reason)
    }

    private fun cleanupActiveRequests() {
        requestJobs.values.forEach(Job::cancel)
        requestJobs.clear()
        creditWindows.values.forEach(RelayCreditWindow::close)
        creditWindows.clear()
        requestBudget.clear()
    }
}
