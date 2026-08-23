package io.legado.app.lib.cloud

import java.util.UUID

data class S3Container(
    val id: String = newId(),
    val name: String = "",
    val endpoint: String = "",
    val bucket: String = "",
    val prefix: String = "legado",
    val region: String = "us-east-1",
    val pathStyle: Boolean? = null,
    val accessKey: String = "",
    val secretKey: String = "",
    val sessionToken: String? = null,
    val capacityMb: Long = 0,
    val usedBytes: Long = 0,
    val lastRefreshTime: Long = 0,
    val isFull: Boolean = false,
    val enabled: Boolean = true
) {

    val capacityBytes: Long
        get() = if (capacityMb <= 0) Long.MAX_VALUE else capacityMb * 1024L * 1024L

    val hasCapacityLimit: Boolean
        get() = capacityMb > 0

    fun normalized(): S3Container {
        val parsed = S3Config.parseAddress(endpoint, bucket, region, pathStyle)
        return copy(
            id = id.ifBlank { newId() },
            endpoint = parsed.endpoint,
            bucket = parsed.bucket,
            region = parsed.region,
            pathStyle = parsed.pathStyle,
            prefix = prefix.trim().trim('/').ifBlank { "legado" },
            capacityMb = capacityMb.coerceAtLeast(0),
            usedBytes = usedBytes.coerceAtLeast(0)
        )
    }

    fun toConfig(): S3Config {
        return S3Config(
            endpoint = endpoint,
            region = region,
            bucket = bucket,
            prefix = prefix,
            accessKey = accessKey,
            secretKey = secretKey,
            sessionToken = sessionToken,
            pathStyle = pathStyle
        )
    }

    fun requireValid() {
        toConfig().requireValid()
    }

    fun capacityBytes(): Long? = capacityMb.takeIf { it > 0 }?.let { it * 1024L * 1024L }

    fun canFit(deltaBytes: Long): Boolean {
        if (!hasCapacityLimit) return true
        return usedBytes + deltaBytes.coerceAtLeast(0L) <= capacityBytes
    }

    fun hasCapacityFor(bytesToAdd: Long): Boolean {
        val capacity = capacityBytes() ?: return true
        return !isFull && usedBytes + bytesToAdd.coerceAtLeast(0L) <= capacity
    }

    companion object {
        const val LEGACY_DEFAULT_ID = "default"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
