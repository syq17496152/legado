package io.legado.app.lib.cloud

import java.net.URI

/**
 * Keeps the stored S3 preferences compatible while accepting common pasted URLs.
 */
data class S3Config(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val accessKey: String,
    val secretKey: String,
    val sessionToken: String?,
    val pathStyle: Boolean?
) {
    private val parsed: ParsedAddress by lazy {
        parseAddress(endpoint, bucket, region, pathStyle)
    }

    val normalizedEndpoint: String
        get() = parsed.endpoint

    val normalizedPrefix: String
        get() {
            val clean = prefix.trim().trim('/')
            return if (clean.isBlank()) "" else "$clean/"
        }

    val normalizedBucket: String
        get() = parsed.bucket

    val normalizedRegion: String
        get() = parsed.region

    val usePathStyle: Boolean
        get() = parsed.pathStyle

    fun requireValid() {
        require(endpoint.isNotBlank()) { "S3 endpoint not configured" }
        require(normalizedRegion.isNotBlank()) { "S3 region not configured" }
        require(normalizedBucket.isNotBlank()) { "S3 bucket not configured" }
        require(accessKey.isNotBlank()) { "S3 access key not configured" }
        require(secretKey.isNotBlank()) { "S3 secret key not configured" }
    }

    data class ParsedAddress(
        val endpoint: String,
        val bucket: String,
        val region: String,
        val pathStyle: Boolean
    )

    companion object {
        fun parseAddress(
            rawEndpoint: String,
            rawBucket: String = "",
            rawRegion: String = "",
            rawPathStyle: Boolean? = null
        ): ParsedAddress {
            val endpoint = normalizeEndpoint(rawEndpoint)
            val explicitBucket = rawBucket.trim().trim('/')
            val uri = runCatching { URI(endpoint) }.getOrNull()
                ?: return fallback(endpoint, explicitBucket, rawRegion, rawPathStyle)
            val scheme = uri.scheme ?: "https"
            val host = uri.host.orEmpty().lowercase()
            val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }

            if (host.equals("catalog.cloudflarestorage.com", ignoreCase = true) && pathSegments.size >= 2) {
                return ParsedAddress(
                    endpoint = "$scheme://${pathSegments[0]}.r2.cloudflarestorage.com",
                    bucket = pathSegments[1],
                    region = "auto",
                    pathStyle = true
                )
            }

            if (host.endsWith(".r2.cloudflarestorage.com", ignoreCase = true)) {
                return ParsedAddress(
                    endpoint = "$scheme://$host${uri.portPart()}",
                    bucket = pathSegments.firstOrNull().orEmpty().ifBlank { explicitBucket },
                    region = rawRegion.trim().takeUnless { it.isBlank() || it.equals("us-east-1", ignoreCase = true) } ?: "auto",
                    pathStyle = rawPathStyle ?: true
                )
            }

            parseAmazonVirtualHost(scheme, host, uri.portPart(), rawRegion, rawPathStyle)?.let { parsed ->
                return parsed.copy(bucket = explicitBucket.ifBlank { parsed.bucket })
            }

            if (pathSegments.isNotEmpty()) {
                return ParsedAddress(
                    endpoint = "$scheme://$host${uri.portPart()}",
                    bucket = pathSegments.first(),
                    region = defaultRegion(rawRegion, endpoint),
                    pathStyle = rawPathStyle ?: defaultPathStyle(endpoint)
                )
            }

            return fallback(endpoint, explicitBucket, rawRegion, rawPathStyle)
        }

        private fun fallback(
            endpoint: String,
            bucket: String,
            region: String,
            pathStyle: Boolean?
        ): ParsedAddress {
            return ParsedAddress(
                endpoint = endpoint,
                bucket = bucket,
                region = defaultRegion(region, endpoint),
                pathStyle = pathStyle ?: defaultPathStyle(endpoint)
            )
        }

        private fun parseAmazonVirtualHost(
            scheme: String,
            host: String,
            portPart: String,
            rawRegion: String,
            rawPathStyle: Boolean?
        ): ParsedAddress? {
            val marker = ".s3"
            val markerIndex = host.indexOf(marker, ignoreCase = true)
            if (markerIndex <= 0 || !host.endsWith("amazonaws.com", ignoreCase = true)) return null
            val bucket = host.substring(0, markerIndex)
            val endpointHost = host.substring(markerIndex + 1)
            val region = rawRegion.trim().ifBlank {
                when {
                    endpointHost.equals("s3.amazonaws.com", ignoreCase = true) -> "us-east-1"
                    endpointHost.startsWith("s3.", ignoreCase = true) -> endpointHost.removePrefix("s3.").removeSuffix(".amazonaws.com")
                    endpointHost.startsWith("s3-", ignoreCase = true) -> endpointHost.removePrefix("s3-").removeSuffix(".amazonaws.com")
                    else -> "us-east-1"
                }.ifBlank { "us-east-1" }
            }
            return ParsedAddress(
                endpoint = "$scheme://$endpointHost$portPart",
                bucket = bucket,
                region = region,
                pathStyle = rawPathStyle ?: false
            )
        }

        private fun normalizeEndpoint(endpoint: String): String {
            var value = endpoint.trim()
            if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
                value = "https://$value"
            }
            return value.trimEnd('/')
        }

        private fun defaultRegion(region: String, endpoint: String): String {
            return region.trim().ifBlank {
                if (endpoint.contains("r2.cloudflarestorage.com", ignoreCase = true)) "auto" else "us-east-1"
            }
        }

        private fun defaultPathStyle(endpoint: String): Boolean {
            return !endpoint.contains("amazonaws.com", ignoreCase = true)
        }

        private fun URI.portPart(): String {
            return if (port > 0 && !((scheme == "http" && port == 80) || (scheme == "https" && port == 443))) {
                ":$port"
            } else {
                ""
            }
        }
    }
}
