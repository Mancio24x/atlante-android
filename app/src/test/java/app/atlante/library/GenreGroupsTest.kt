package app.atlante.library

import app.atlante.library.model.Book
import app.atlante.library.ui.compose.genreCounts
import org.junit.Assert.assertEquals
import org.junit.Test

/** Statistics: genres grouped into main genres, most read first, the non-genres last. */
class GenreGroupsTest {
    private fun b(genre: String) = Book().apply { this.genre = genre }

    @Test fun manyLabelsFewGenres() {
        val books = listOf(b("Epic Fantasy"), b("fantasy / magia"), b("Fantasy"), b("Science Fiction"), b("Fantascienza distopica"),
            b("Gialli"), b("Mystery & Detective"), b("Cucina regionale"), b(""), b("Adventure stories"))
        assertEquals(listOf("Fantasy" to 3, "Fantascienza" to 2, "Giallo" to 2, "Avventura" to 1, "Altri generi" to 1, "Genere non indicato" to 1), genreCounts(books))
    }
}
