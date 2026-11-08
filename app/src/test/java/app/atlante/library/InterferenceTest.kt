package app.atlante.library

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.atlante.library.catalog.BibliographicEngine
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import app.atlante.library.storage.LibraryStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Two features at once: a repair merges the book whose timer is running, or whose page is open. Nothing is lost. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InterferenceTest {
    /** One library per test: the shared store would otherwise keep the previous test's application. */
    @org.junit.Before fun freshStore() { LibraryStore::class.java.getDeclaredField("shared").apply { isAccessible = true }.set(null, null) }

    private fun call(a: MainActivity, name: String, vararg args: Any): Any? =
        MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(a, *args)
    private fun labels(v: View): List<TextView> = (if (v is TextView) listOf(v) else emptyList()) + (if (v is ViewGroup) (0 until v.childCount).flatMap { labels(v.getChildAt(it)) } else emptyList())
    private fun store(a: MainActivity) = MainActivity::class.java.getDeclaredField("store").apply { isAccessible = true }.get(a) as LibraryStore

    @Test fun timerSurvivesAMergeAndTheSessionGoesToTheKeptBook() {
        var controller = Robolectric.buildActivity(MainActivity::class.java).setup(); var a = controller.get()
        val keep = Book().apply { id = "1".repeat(64); title = "Il deserto dei Tartari"; author = "Dino Buzzati"; format = "CATALOGO"; totalPages = 250; notes = "mia" }
        val copy = Book().apply { id = "2".repeat(64); title = "Il deserto dei tartari"; author = "Dino Buzzati"; format = "CATALOGO"; totalPages = 250 }
        store(a).addCatalog(keep); store(a).addCatalog(copy)
        call(a, "startTimer", store(a).find(copy.id)!!)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(12))
        // Ripara duplicati runs meanwhile: the copy goes into the kept record.
        store(a).merge(BibliographicEngine.merged(store(a).find(keep.id), store(a).find(copy.id)), store(a).find(copy.id))
        controller.recreate(); a = controller.get()
        assertEquals(keep.id, MainActivity::class.java.getDeclaredField("timerBook").apply { isAccessible = true }.get(a))
        assertTrue((call(a, "timerSeconds") as Long) >= 12 * 60)
        call(a, "timerScreen", store(a).find(keep.id)!!)
        labels(a.window.decorView).first { it.text.toString() == "Termina" }.performClick()
        labels(a.window.decorView).first { it.text.toString() == "Salva la sessione" }.performClick()
        val saved = store(a).find(keep.id)!!
        assertEquals(1, ReadingJournal.array(saved, "sessions").length())
        assertNull(store(a).find(copy.id))
        controller.pause().stop().destroy()
    }

    @Test fun theOpenPageFollowsTheMerge() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); val a = controller.get()
        val keep = Book().apply { id = "3".repeat(64); title = "Fondazione"; author = "Isaac Asimov"; format = "CATALOGO"; notes = "mia" }
        val copy = Book().apply { id = "4".repeat(64); title = "Fondazione"; author = "Isaac Asimov"; format = "CATALOGO" }
        store(a).addCatalog(keep); store(a).addCatalog(copy)
        call(a, "details", store(a).find(copy.id)!!)
        store(a).merge(BibliographicEngine.merged(store(a).find(keep.id), store(a).find(copy.id)), store(a).find(copy.id))
        call(a, "renderAfterRepair")
        assertEquals(keep.id, MainActivity::class.java.getDeclaredField("detailId").apply { isAccessible = true }.get(a))
        controller.pause().stop().destroy()
    }
}
