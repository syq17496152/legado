package io.legado.app.service.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.random.Random

internal sealed interface RelayConnectionState {
    data object Disabled : RelayConnectionState
    data object UnsupportedPlatform : RelayConnectionState
    data object WaitingForNetwork : RelayConnectionState
    data class Connecting(val attempt: Int) : RelayConnectionState
    data object Authenticating : RelayConnectionState
    data class Connected(val connectedAt: Long, val latencyMillis: Long) : RelayConnectionState
    data class TestSucceeded(val latencyMillis: Long) : RelayConnectionState
    data class Reconnecting(val delayMillis: Long, val reason: String?) : RelayConnectionState
    data class ConfigurationError(val reason: String) : RelayConnectionState
    data class Failed(val reason: String) : RelayConnectionState
}

internal object RelayStateRepository {
    private val mutableState = MutableStateFlow<RelayConnectionState>(RelayConnectionState.Disabled)
    val state: StateFlow<RelayConnectionState> = mutableState.asStateFlow()

    fun update(value: RelayConnectionState) {
        mutableState.value = value
    }
}

internal class RelayBackoff(
    private val random: Random = Random.Default,
    private val initialMillis: Long = 1_000L,
    private val maximumMillis: Long = 60_000L
) {
    private var failures = 0

    fun reset() {
        failures = 0
    }

    fun nextDelayMillis(): Long {
        val exponent = min(failures++, 6)
        val ceiling = min(maximumMillis, initialMillis shl exponent)
        val floor = (ceiling / 2).coerceAtLeast(1L)
        return random.nextLong(floor, ceiling + 1)
    }
}

internal class RelayRequestBudget {
    private data class Budget(var received: Long = 0, var unconsumed: Int = 0)
    private val requests = LinkedHashMap<Long, Budget>()

    @Synchronized
    fun begin(requestId: Long, declaredLength: Long): String? {
        if (requestId <= 0 || requestId in requests) return "invalid_request_id"
        if (declaredLength !in 0..RelayProtocol.MAX_BODY_BYTES) return "body_too_large"
        if (requests.size >= RelayProtocol.MAX_CONCURRENT_REQUESTS) return "too_many_requests"
        requests[requestId] = Budget()
        return null
    }

    @Synchronized
    fun acceptChunk(requestId: Long, byteCount: Int): String? {
        if (byteCount !in 0..RelayProtocol.MAX_CHUNK_BYTES) return "chunk_too_large"
        val budget = requests[requestId] ?: return "unknown_request"
        val received = budget.received + byteCount
        val unconsumed = budget.unconsumed + byteCount
        if (received > RelayProtocol.MAX_BODY_BYTES) return "body_too_large"
        if (unconsumed > RelayProtocol.MAX_UNCONSUMED_BYTES) return "flow_control_violation"
        budget.received = received
        budget.unconsumed = unconsumed
        return null
    }

    @Synchronized
    fun acknowledge(requestId: Long, byteCount: Int): Boolean {
        if (byteCount < 0) return false
        val budget = requests[requestId] ?: return false
        budget.unconsumed = (budget.unconsumed - byteCount).coerceAtLeast(0)
        return true
    }

    @Synchronized
    fun finish(requestId: Long): Boolean = requests.remove(requestId) != null

    @Synchronized
    fun contains(requestId: Long): Boolean = requestId in requests

    @Synchronized
    fun clear() = requests.clear()
}
