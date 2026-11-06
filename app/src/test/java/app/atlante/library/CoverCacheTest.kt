package app.atlante.library

import app.atlante.library.saga.CoverCache
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Suggestion covers stay under the configured size; the least recently used go first. */
class CoverCacheTest {
    @Test fun keepsUnderLimitDroppingLeastRecentlyUsed() {
        val dir = Files.createTempDirectory("covers").toFile()
        try {
            var limit = 2500L; var downloads = 0
            val cache = CoverCache(dir) { limit }
            val fake: (String) -> ByteArray = { downloads++; ByteArray(1000) }
            cache.get("a", fake); Thread.sleep(20); cache.get("b", fake); Thread.sleep(20)
            cache.get("a", fake); Thread.sleep(20)          // "a" used again: now "b" is the oldest
            cache.get("c", fake)
            assertEquals(3, downloads)
            assertTrue(cache.bytes() <= limit)
            cache.get("a", fake); assertEquals("a kept", 3, downloads)
            cache.get("b", fake); assertEquals("b evicted", 4, downloads)
            limit = 0; cache.trim(); assertEquals(0, cache.bytes())
            cache.get("d", fake); assertEquals(0L, cache.clear().let { cache.bytes() })
        } finally { dir.deleteRecursively() }
    }
}
