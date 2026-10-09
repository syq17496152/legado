package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.help.ImageSourceOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** A bounded chapter-local pass. It never analyzes source scripts or fetches image URLs. */
internal class TextReaderBubblePreparation(
    private val render: suspend (String) -> TextReaderImageResource,
    private val maxUniqueBubbles: Int = 64,
    private val maxImageBytes: Int = 64 * 1024,
    private val maxInlineBytes: Long = 512L * 1024,
    private val maxInlineChars: Long = 1024L * 1024,
    private val timeoutMs: Long = 1500L,
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> }
) {
    private data class Prepared(val image: TextReaderPreparedImage, val bytes: Long)

    init {
        require(maxUniqueBubbles > 0)
        require(maxImageBytes in 1..TextReaderImageResource.MAX_BYTES)
        require(maxInlineBytes > 0 && maxInlineChars > 0 && timeoutMs > 0)
    }

    suspend fun prepare(images: List<TextReaderImage>, managedBubble: Boolean): Map<String, TextReaderPreparedImage> {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val result = LinkedHashMap<String, TextReaderPreparedImage>()
        val preparedSources = HashMap<String, Prepared?>()
        var inlineBytes = 0L
        var inlineChars = 0L
        // This is a cooperative batch budget. Native SVG parsing may only observe cancellation
        // when it returns; do not publish its late result or turn caller cancellation into fallback.
        withTimeoutOrNull(timeoutMs) {
            val batch = currentCoroutineContext()
            for (image in images) {
                batch.ensureActive()
                if (inlineBytes >= maxInlineBytes || inlineChars >= maxInlineChars) break
                val canonical = try {
                    canonicalSource(image, managedBubble)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    batch.ensureActive()
                    onFailure(image.id, error)
                    null
                } ?: continue
                batch.ensureActive()
                val prepared = if (preparedSources.containsKey(canonical)) {
                    preparedSources[canonical]
                } else {
                    if (preparedSources.size >= maxUniqueBubbles) continue
                    val generated = try {
                        val resource = render(canonical)
                        batch.ensureActive()
                        if (!resource.isBubble) null else resource.dataUri(maxImageBytes)?.let { dataUri ->
                            batch.ensureActive()
                            Prepared(TextReaderPreparedImage(dataUri, resource.scale, resource.isBubble), resource.length)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        batch.ensureActive()
                        onFailure(canonical, error)
                        null
                    }
                    // Remember failed/oversized sources too; repeated count badges get one attempt.
                    preparedSources[canonical] = generated
                    generated
                } ?: continue
                // Data URIs are repeated in HTML even when the pixels were generated only once.
                if (prepared.bytes > maxInlineBytes - inlineBytes ||
                    prepared.image.dataUri.length.toLong() > maxInlineChars - inlineChars
                ) continue
                batch.ensureActive()
                result[image.id] = prepared.image
                inlineBytes += prepared.bytes
                inlineChars += prepared.image.dataUri.length
            }
        }
        caller.ensureActive()
        return result
    }

    private fun canonicalSource(image: TextReaderImage, managedBubble: Boolean): String? {
        val parsed = ImageSourceOptions.parse(image.renderSource) ?: return null
        if (TextReaderImageSource.needsScript(parsed)) return null
        // Keep the same special-resource order as TextReaderImageResolver, before data/HTTP routing.
        return TextReaderImageSource.bubbleSource(parsed)
            ?: TextReaderSourceBubblePolicy.resolve(
                image.source, parsed.source, image.sourceStyle, image.click, enabled = managedBubble
            )
    }
}
