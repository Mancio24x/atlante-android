package app.atlante.library.ui.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The one memory cache of the covers shown in Compose (Scopri, saga and author carousels).
 * - Keys are stable: the cover URL (it holds the Open Library cover id, so each edition has its own)
 *   or "own:<book id>" for a cover saved with a library book.
 * - The same cover asked by two cards at once is loaded once.
 * - A cover that could not be loaded is not asked again for [RETRY_MS] (the card shows its text cover).
 * - Bitmaps are decoded at the size they are shown, in RGB_565 (covers have no transparency).
 * Files on disk stay where they are: library covers with the books, the others in the size-limited CoverCache.
 */
object CoverImages {
    const val RETRY_MS = 10 * 60_000L

    private val memory = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 1024 / 16).coerceAtMost(32 * 1024).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = (value.width * value.height * 2 / 1024).coerceAtLeast(1)
    }
    private val failed = ConcurrentHashMap<String, Long>()
    private val inflight = HashMap<String, CompletableDeferred<ImageBitmap?>>()

    /** Counters for the measurements (memory hits, loads that reached disk or network, failures). */
    @Volatile var hits = 0; private set
    @Volatile var loads = 0; private set
    @Volatile var misses = 0; private set

    fun cached(key: String): ImageBitmap? = memory.get(key)?.also { hits++ }

    /** After a cover changes (chosen by you, found online): the next card loads it again. */
    @JvmStatic fun forget(key: String) { memory.remove(key); failed.remove(key) }
    @JvmStatic fun forgetBook(bookId: String) = forget("own:$bookId")
    @JvmStatic fun clear() { memory.evictAll(); failed.clear() }

    /** The cover for [key]: from memory, or from [read] (run off the main thread, once even if asked twice). */
    suspend fun load(key: String, read: suspend () -> Bitmap?): ImageBitmap? {
        cached(key)?.let { return it }
        failed[key]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return null else failed.remove(key) }
        while (true) {
            var mine = false
            val pending = synchronized(inflight) {
                inflight[key] ?: CompletableDeferred<ImageBitmap?>().also { inflight[key] = it; mine = true }
            }
            if (!mine) {
                // Another card is loading the same cover: wait for it. If it was cancelled (scrolled away), take over.
                try { return pending.await() } catch (e: CancellationException) { currentCoroutineContext().ensureActive(); continue }
            }
            try {
                loads++
                val image = withContext(Dispatchers.IO) {
                    try { read() } catch (e: CancellationException) { throw e } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
                }?.asImageBitmap()
                if (image != null) memory.put(key, image) else { failed[key] = System.currentTimeMillis(); misses++ }
                pending.complete(image)
                return image
            } catch (e: CancellationException) {
                pending.cancel(e); throw e
            } finally {
                synchronized(inflight) { if (inflight[key] === pending) inflight.remove(key) }
            }
        }
    }

    /** Decodes at the smallest power-of-two scale that still covers [maxPx] on the longer side. */
    fun decode(bytes: ByteArray?, file: String?, maxPx: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (file != null) BitmapFactory.decodeFile(file, options) else if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) else return null
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / (sample * 2) >= maxPx) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.RGB_565
        return if (file != null) BitmapFactory.decodeFile(file, options) else BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size, options)
    }
}

/**
 * A book cover: the image when it is ready, otherwise a text cover (title in serif on the theme's surface).
 * [identity] is the book shown in this place: while it stays the same, a new [imageKey] (for example the
 * Italian edition's cover) replaces the old image only when it is ready, without going back to the text.
 */
@Composable
fun BookCover(
    identity: Any?, imageKey: String?, title: String, modifier: Modifier,
    border: BorderStroke? = null, textPadding: Dp = 6.dp,
    read: suspend () -> Bitmap?,
) {
    var image by remember(identity) { mutableStateOf(imageKey?.let { CoverImages.cached(it) }) }
    val currentRead by rememberUpdatedState(read)
    LaunchedEffect(identity, imageKey) {
        val key = imageKey ?: return@LaunchedEffect
        (CoverImages.cached(key) ?: CoverImages.load(key) { currentRead() })?.let { image = it }
    }
    val shape = RoundedCornerShape(8.dp)
    Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp, border = border) {
        val img = image
        if (img != null) Image(img, null, Modifier.fillMaxSize().clip(shape), contentScale = ContentScale.Crop)
        else if (title.isNotEmpty()) Box(Modifier.fillMaxSize().padding(textPadding), contentAlignment = Alignment.Center) {
            Text(title, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
    }
}
