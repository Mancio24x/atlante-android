package app.atlante.library

import app.atlante.library.saga.ShelfItem
import app.atlante.library.ui.compose.SagaLayout
import app.atlante.library.ui.compose.SagaLayout.Edge
import app.atlante.library.ui.compose.SagaLayout.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases 5 and 6 of the bibliographic tests: a complex saga and an incomplete one, on the vertical map. */
class SagaLayoutTest {
    private fun v(title: String, ordinal: String?, year: String? = null, membership: String = "volume", work: Long = title.hashCode().toLong()) =
        ShelfItem(title, "Brandon Sanderson", year, ordinal, null, false, null, null, title, workId = work, membership = membership)

    /** Mistborn: era 1 and era 2, a 0 prequel-like novella, "Secret History" numbered 3.5, the order the sources give. */
    @Test fun complexSagaKeepsTheNumberingAndBranchesTheIntermediateBooks() {
        val items = listOf(
            v("The Alloy of Law", "4", "2011"), v("L'ultimo impero", "1", "2006"), v("Il pozzo dell'ascensione", "2", "2007"),
            v("Mistborn: Secret History", "3.5", "2016", "novella"), v("L'eroe delle ere", "3", "2008"), v("Shadows of Self", "5", "2015"),
            v("The Eleventh Metal", "0", "2011", "prequel"))
        val map = SagaLayout.layout(items, SagaLayout.Order.SAGA, null)
        assertEquals(listOf("The Eleventh Metal", "L'ultimo impero", "Il pozzo dell'ascensione", "L'eroe delle ere", "Mistborn: Secret History", "The Alloy of Law", "Shadows of Self"),
            map.nodes.map { items[it.item].title })
        assertEquals(Kind.PREQUEL, map.nodes[0].kind)
        assertEquals(Kind.SIDE, map.nodes[4].kind)
        // Arrows: 0→1→2→3→4→5 on the main line, 3 → 3.5 as a branch; nothing joins 3.5 to 4.
        val pairs = map.links.map { map.nodes[it.from].label to map.nodes[it.to].label to it.type }
        assertTrue(pairs.toString(), ("3" to "3.5" to Edge.BRANCH) in pairs)
        assertTrue(("3" to "4" to Edge.NEXT) in pairs)
        assertTrue(pairs.none { it.first.first == "3.5" })
        assertTrue(map.waiting.isEmpty())
        // Deterministic: same input, same map.
        assertEquals(map, SagaLayout.layout(items.reversed().reversed(), SagaLayout.Order.SAGA, null))
    }

    /** An incomplete saga: known volumes in order, a gap marked, no number invented, unnumbered books waiting. */
    @Test fun incompleteSagaNeverInventsPositions() {
        val items = listOf(v("Uno", "1"), v("Tre", "3"), v("Senza numero", null, "2019"), v("Doppione di Uno", "1"))
        val map = SagaLayout.layout(items, SagaLayout.Order.SAGA, null)
        assertEquals(listOf("Uno", "Tre"), map.nodes.map { items[it.item].title })
        assertEquals(Edge.GAP, map.links.single().type); assertEquals("manca il n. 2", map.links.single().note)
        assertEquals(listOf("Senza numero", "Doppione di Uno"), map.waiting.map { items[it.item].title })
        assertTrue(map.waiting[1].reason.startsWith("stesso numero"))
        // No number at all: nothing is placed, the map says so.
        val none = SagaLayout.layout(listOf(v("A", null), v("B", null)), SagaLayout.Order.SAGA, null)
        assertTrue(none.nodes.isEmpty()); assertEquals(2, none.waiting.size); assertTrue(none.note.isNotEmpty())
    }

    @Test fun personalOrderKeepsNewVolumesApart() {
        val items = listOf(v("A", "1", work = 1), v("B", "2", work = 2), v("Nuovo", "3", work = 3))
        val map = SagaLayout.layout(items, SagaLayout.Order.PERSONAL, listOf(2, 1))
        assertEquals(listOf("B", "A"), map.nodes.map { items[it.item].title })
        assertEquals(listOf("Nuovo"), map.waiting.map { items[it.item].title })
        assertEquals(listOf(1, 0, 2), SagaLayout.editable(items, listOf(2, 1)))
    }
}
