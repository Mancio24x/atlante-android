package app.atlante.library

import app.atlante.library.model.Book
import app.atlante.library.saga.Hardcover
import app.atlante.library.saga.SagaRecognizer
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real Hardcover answers for Italian books. Runs only with a personal key in the environment:
 * HARDCOVER_TOKEN=… gradle :app:testDebugUnitTest --tests '*HardcoverLiveTest' -i
 */
class HardcoverLiveTest {
    @Test fun italianLibrary() {
        val token = System.getenv("HARDCOVER_TOKEN") ?: ""
        assumeTrue("HARDCOVER_TOKEN not set", token.isNotBlank())
        Hardcover.useKey(token)
        val books = listOf(
            "L'ultimo impero" to "Brandon Sanderson", "Il pozzo dell'ascensione" to "Brandon Sanderson", "La via dei re" to "Brandon Sanderson",
            "Il canto proibito" to "Pierce Brown", "Dune" to "Frank Herbert", "I figli di Dune" to "Frank Herbert",
            "Harry Potter e la pietra filosofale" to "J. K. Rowling", "Il guardiano degli innocenti" to "Andrzej Sapkowski",
            "Fondazione" to "Isaac Asimov", "Io, robot" to "Isaac Asimov", "Città di ossa" to "Cassandra Clare",
            "Sei di corvi" to "Leigh Bardugo", "La nona casa" to "Leigh Bardugo", "Il colore della magia" to "Terry Pratchett",
            "Il nome del vento" to "Patrick Rothfuss", "La compagnia dell'anello" to "J. R. R. Tolkien", "Il nome della rosa" to "Umberto Eco",
            "Il trono di spade" to "George R. R. Martin", "Hunger Games" to "Suzanne Collins", "Dungeon Crawler Carl" to "Matt Dinniman",
        )
        val start = System.currentTimeMillis()
        for ((title, author) in books) {
            val t = System.currentTimeMillis()
            val m = SagaRecognizer.identify(Book().apply { this.title = title; this.author = author }, object : SagaRecognizer.Sources by SagaRecognizer.Live {})
            val saga = m.sagas.firstOrNull()
            println(String.format("%-38s %-12s %-40s #%-5s %-28s %5.1fs", title, m.status, saga?.seriesLabel ?: "-", saga?.ordinal ?: "-",
                m.universes.firstOrNull()?.seriesLabel ?: saga?.universeLabel ?: "", (System.currentTimeMillis() - t) / 1000.0))
        }
        println(String.format("%d libri in %.1f s", books.size, (System.currentTimeMillis() - start) / 1000.0))
    }
}
