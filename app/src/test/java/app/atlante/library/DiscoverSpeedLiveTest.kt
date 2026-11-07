package app.atlante.library

import app.atlante.library.online.OnlineCatalog
import app.atlante.library.saga.CoverCache
import app.atlante.library.saga.Discover
import app.atlante.library.saga.Hardcover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real timings of Scopri's covers, the way the screen loads them: twelve cards (two pages) asking their cover at
 * the same moment, through the cover cache on disk and OnlineCatalog's per-host limits. Prints the time to the
 * first cover, to six and to twelve, the HTTP requests, the duplicates and, on a second pass, the cache hits.
 * HARDCOVER_TOKEN=… gradle :app:testDebugUnitTest --tests '*DiscoverSpeedLiveTest' -i
 */
class DiscoverSpeedLiveTest {
    @Test fun covers() {
        val token = System.getenv("HARDCOVER_TOKEN") ?: ""
        assumeTrue("HARDCOVER_TOKEN not set", token.isNotBlank())
        Hardcover.useKey(token)
        for (genre in listOf(null, Discover.GENRES.first { it.label == "Fantasy" }, Discover.GENRES.first { it.label == "Horror" })) {
            val t0 = System.nanoTime()
            val json = Hardcover.discoverJson(genre?.hardcover?.takeIf { it.isNotEmpty() })
            val list = (System.nanoTime() - t0) / 1e6
            val urls = Discover.parseHardcover(json, genre).mapNotNull { it.coverUrl }.take(12)
            repeat(2) { run ->
                val dir = Files.createTempDirectory("covers").toFile()
                val cache = CoverCache(dir) { 100L * 1024 * 1024 }
                val requests = AtomicInteger(); val perUrl = ConcurrentHashMap<String, AtomicInteger>()
                val download: (String) -> ByteArray = { u ->
                    requests.incrementAndGet(); perUrl.getOrPut(u) { AtomicInteger() }.incrementAndGet()
                    OnlineCatalog.download(u, 4 * 1024 * 1024)
                }
                fun pass(): Triple<List<Double>, Long, Int> = runBlocking {
                    val start = System.nanoTime()
                    val bytes = java.util.concurrent.atomic.AtomicLong()
                    val done = urls.map { u -> async(Dispatchers.IO) { runCatching { bytes.addAndGet(cache.get(u, download).size.toLong()) }; (System.nanoTime() - start) / 1e6 } }.awaitAll().sorted()
                    Triple(done, bytes.get(), requests.get())
                }
                val (cold, bytes, coldRequests) = pass()
                val (warm, _, allRequests) = pass()
                println(String.format("%-10s run %d · lista %5.0f ms · 1ª %5.0f ms · 6 %5.0f ms · 12 %5.0f ms · %2d richieste, %d doppie, %4d KB · ripasso: 12 in %4.0f ms, %d dalla cache",
                    genre?.label ?: "Più letti", run + 1, list, cold.first(), cold[minOf(5, cold.size - 1)], cold.last(), coldRequests,
                    perUrl.values.count { it.get() > 1 }, bytes / 1024, warm.last(), urls.size - (allRequests - coldRequests)))
                dir.deleteRecursively()
            }
        }
    }
}
