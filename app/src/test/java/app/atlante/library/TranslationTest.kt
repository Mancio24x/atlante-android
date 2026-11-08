package app.atlante.library

import androidx.test.core.app.ApplicationProvider
import app.atlante.library.ui.L
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** English is applied only to what is shown: exact sentences, sentences with blanks, dates, capitals; Italian is untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationTest {
    @After fun italian() = L.force(null, false)

    @Test fun englishWhenChosen() {
        L.force(ApplicationProvider.getApplicationContext(), true)
        assertEquals("Library", L.t("Biblioteca"))
        assertEquals("To read", L.t("Da leggere"))
        assertEquals("Page 12 of 300", L.t("Pagina 12 di 300"))                        // a sentence with blanks
        assertEquals("«Dune» is already in your library", L.t("«Dune» è già nella tua biblioteca"))   // the title stays
        assertEquals("14 books read", L.t("14 libri letti"))                             // a number and known words
        assertEquals("SERIES", L.t("SAGHE"))                                             // capitals
        assertEquals("8 November 2026", L.t("8 novembre 2026"))                          // dates
        assertEquals("SUNDAY 8 NOVEMBER", L.t("DOMENICA 8 NOVEMBRE"))
        assertEquals("Reading · 43%", L.t("In lettura · 43%"))                           // pieces around " · "
        assertEquals("Il nome della rosa", L.t("Il nome della rosa"))                    // a book's title is never translated
        assertEquals("M", L.weekInitials()[0]); assertEquals("J", L.monthInitials()[0])
    }

    @Test fun italianUntouched() {
        L.force(ApplicationProvider.getApplicationContext(), false)
        assertEquals("Biblioteca", L.t("Biblioteca"))
        assertEquals("Pagina 12 di 300", L.t("Pagina 12 di 300"))
        assertEquals("G", L.monthInitials()[0])
    }
}
