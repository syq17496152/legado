package io.legado.app.ui.association

import io.legado.app.help.http.decompressed
import io.legado.app.utils.readBytesLimited
import okhttp3.ResponseBody
import java.io.ByteArrayInputStream
import java.io.InputStream

internal const val MAX_LEGACY_IMPORT_BYTES = 4L * 1024L * 1024L

internal inline fun <T> ResponseBody.useLimitedImportStream(
    maxBytes: Long = MAX_LEGACY_IMPORT_BYTES,
    block: (InputStream) -> T
): T {
    val bytes = decompressed().byteStream().use { it.readBytesLimited(maxBytes) }
    return ByteArrayInputStream(bytes).use(block)
}

internal fun ResponseBody.readLimitedImportText(
    maxBytes: Long = MAX_LEGACY_IMPORT_BYTES
): String {
    return useLimitedImportStream(maxBytes) { input ->
        input.reader(Charsets.UTF_8).readText()
    }
}
