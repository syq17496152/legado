package io.legado.app.lib.cloud

import okhttp3.HttpUrl
import okhttp3.Request
import okio.Buffer
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object S3Signer {

    private val dateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
        .withZone(ZoneOffset.UTC)
    private val dateFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd", Locale.ROOT)
        .withZone(ZoneOffset.UTC)

    fun sign(builder: Request.Builder, config: S3Config, method: String, url: HttpUrl, payloadHash: String) {
        val now = Instant.now()
        val amzDate = dateTimeFormatter.format(now)
        val date = dateFormatter.format(now)
        val host = url.host + when {
            url.port == 80 && url.scheme == "http" -> ""
            url.port == 443 && url.scheme == "https" -> ""
            else -> ":${url.port}"
        }
        val token = config.sessionToken?.takeIf { it.isNotBlank() }
        val headerMap = linkedMapOf(
            "host" to host,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate
        )
        token?.let { headerMap["x-amz-security-token"] = it }
        val signedHeaders = headerMap.keys.joinToString(";")
        val canonicalHeaders = headerMap.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }
        val canonicalRequest = listOf(
            method.uppercase(Locale.ROOT),
            canonicalUri(url),
            canonicalQuery(url),
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")
        val credentialScope = "$date/${config.normalizedRegion}/s3/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest.toByteArray())
        ).joinToString("\n")
        val signature = hmacSha256Hex(signingKey(config.secretKey, date, config.normalizedRegion), stringToSign)
        val authorization = "AWS4-HMAC-SHA256 Credential=${config.accessKey}/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
        headerMap.forEach { (name, value) -> builder.header(name, value) }
        builder.header("Authorization", authorization)
    }

    fun payloadHash(request: Request): String {
        val body = request.body ?: return sha256Hex(ByteArray(0))
        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            sha256Hex(buffer.readByteArray())
        }.getOrElse {
            "UNSIGNED-PAYLOAD"
        }
    }

    fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun canonicalUri(url: HttpUrl): String {
        val path = url.encodedPath
        return if (path.isBlank()) "/" else path
    }

    private fun canonicalQuery(url: HttpUrl): String {
        val params = mutableListOf<Pair<String, String>>()
        for (i in 0 until url.querySize) {
            params += url.queryParameterName(i).encodeAwsQuery() to (url.queryParameterValue(i) ?: "").encodeAwsQuery()
        }
        return params.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun signingKey(secretKey: String, date: String, region: String): ByteArray {
        val kDate = hmacSha256(("AWS4$secretKey").toByteArray(), date)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, "s3")
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
    }

    private fun String.encodeAwsPath(): String {
        return URLEncoder.encode(this, "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")
    }

    private fun String.encodeAwsQuery(): String {
        return URLEncoder.encode(this, "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")
    }
}

