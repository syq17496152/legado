package io.legado.app.service.relay

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object RelayProtocol {
    const val VERSION = 1
    const val MINIMUM_VERSION = 1
    const val MAX_CONTROL_BYTES = 32 * 1024
    const val MAX_CHUNK_BYTES = 32 * 1024
    const val MAX_BODY_BYTES = 32L * 1024 * 1024
    const val MAX_UNCONSUMED_BYTES = 512 * 1024
    const val MAX_CONCURRENT_REQUESTS = 4
    const val RESPONSE_START_TIMEOUT_MILLIS = 15_000L
    const val REQUEST_TIMEOUT_MILLIS = 60_000L

    private const val MAGIC = 0x4c475231 // LGR1
    private const val HEADER_BYTES = 24

    enum class BinaryType(val id: Int) {
        HttpRequestChunk(1),
        HttpResponseChunk(2),
        WebSocketData(3);

        companion object {
            fun fromId(id: Int): BinaryType? = entries.firstOrNull { it.id == id }
        }
    }

    data class BinaryFrame(
        val type: BinaryType,
        val flags: Int,
        val requestId: Long,
        val sequence: Int,
        val payload: ByteArray
    )

    fun encode(frame: BinaryFrame): ByteArray {
        require(frame.flags in 0..0xffff) { "Invalid flags" }
        require(frame.requestId > 0) { "Invalid request id" }
        require(frame.sequence >= 0) { "Invalid sequence" }
        require(frame.payload.size <= MAX_CHUNK_BYTES) { "Chunk is too large" }
        return ByteBuffer.allocate(HEADER_BYTES + frame.payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(VERSION.toByte())
            .put(frame.type.id.toByte())
            .putShort(frame.flags.toShort())
            .putLong(frame.requestId)
            .putInt(frame.sequence)
            .putInt(frame.payload.size)
            .put(frame.payload)
            .array()
    }

    fun decode(bytes: ByteArray): BinaryFrame {
        require(bytes.size >= HEADER_BYTES) { "Truncated relay frame" }
        require(bytes.size <= HEADER_BYTES + MAX_CHUNK_BYTES) { "Relay frame is too large" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC) { "Invalid relay frame magic" }
        require(buffer.get().toInt() and 0xff == VERSION) { "Unsupported relay protocol" }
        val type = BinaryType.fromId(buffer.get().toInt() and 0xff)
            ?: throw IllegalArgumentException("Unknown relay frame type")
        val flags = buffer.short.toInt() and 0xffff
        val requestId = buffer.long
        val sequence = buffer.int
        val payloadLength = buffer.int
        require(requestId > 0) { "Invalid request id" }
        require(sequence >= 0) { "Invalid sequence" }
        require(payloadLength in 0..MAX_CHUNK_BYTES) { "Invalid payload length" }
        require(buffer.remaining() == payloadLength) { "Relay frame length mismatch" }
        return BinaryFrame(type, flags, requestId, sequence, ByteArray(payloadLength).also(buffer::get))
    }
}

internal data class RelayControlMessage(
    val v: Int = RelayProtocol.VERSION,
    val type: String,
    val protocolVersion: Int? = null,
    val minimumProtocolVersion: Int? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val nonce: String? = null,
    val expiresAt: Long? = null,
    val epoch: Long? = null,
    val proof: String? = null,
    val requestId: Long? = null,
    val method: String? = null,
    val path: String? = null,
    val contentLength: Long? = null,
    val bodyBase64: String? = null,
    val headers: Map<String, String>? = null,
    val status: Int? = null,
    val bytes: Int? = null,
    val code: String? = null,
    val message: String? = null
)
