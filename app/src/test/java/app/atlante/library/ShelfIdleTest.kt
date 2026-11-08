package app.atlante.library

import android.view.ViewTreeObserver
import app.atlante.library.model.Book
import app.atlante.library.storage.LibraryStore
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/** A page left alone stops drawing: the bookcase must not redraw for ever (the emulator never saw it settle). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ShelfIdleTest {
    private fun drawsWhileIdle(shelf: Boolean, list: Boolean = false): Int {
        LibraryStore::class.java.getDeclaredField("shared").apply { isAccessible = true }.set(null, null)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = c.get()
        val store = MainActivity::class.java.getDeclaredField("store").apply { isAccessible = true }.get(a) as LibraryStore
        for (b in store.snapshot()) store.remove(b)
        val json = JSONArray(javaClass.classLoader!!.getResource("sample-library.json").readText())
        for (i in 0 until json.length()) store.addCatalog(Book.from(json.getJSONObject(i)))
        MainActivity::class.java.getDeclaredField("destination").apply { isAccessible = true }.set(a, "Biblioteca")
        MainActivity::class.java.getDeclaredField("shelfView").apply { isAccessible = true }.setBoolean(a, shelf)
        MainActivity::class.java.getDeclaredField("listView").apply { isAccessible = true }.setBoolean(a, list)
        MainActivity::class.java.getDeclaredMethod("showLibrary").apply { isAccessible = true }.invoke(a)
        repeat(60) { ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS) }        // a second to settle
        var draws = 0
        a.window.decorView.viewTreeObserver.addOnDrawListener(ViewTreeObserver.OnDrawListener { draws++ })
        repeat(120) { ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS) }
        c.pause().stop().destroy()
        return draws
    }

    @Test fun theBookcaseSettles() {
        val grid = drawsWhileIdle(false); val shelf = drawsWhileIdle(true)
        println("draws in 2 s while idle: grid=$grid shelf=$shelf")
        assertTrue("shelf=$shelf grid=$grid", shelf <= grid + 5)
    }
}
