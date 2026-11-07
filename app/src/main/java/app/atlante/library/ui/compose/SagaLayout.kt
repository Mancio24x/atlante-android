package app.atlante.library.ui.compose

import app.atlante.library.saga.ShelfItem

/**
 * Where each volume goes on the vertical saga map, from the bibliographic data only:
 * - Saga order: the main line holds the volumes with an explicit whole number (prequels, numbered 0 or below,
 *   on top); a volume numbered 2.5, or marked as a novella, branches to the side of the volume before it.
 *   Volumes without a number are not placed by guess: they go to "Posizione da verificare". Two works with the
 *   same number: the first is placed, the other waits there too ("stesso numero di …").
 * - Publication order: by first publication year; volumes without a year wait in "Posizione da verificare".
 * - Your order: exactly the order you saved; volumes added later wait in "Posizione da verificare".
 * Arrows only join volumes with a documented relation: consecutive numbers, a missing number in between
 * (dashed, "manca il n. 3"), a branch to its volume, consecutive years, or your order. Same input, same map.
 */
object SagaLayout {
    enum class Order(val label: String) { SAGA("Saga"), PUBLICATION("Pubblicazione"), PERSONAL("Il tuo ordine") }
    enum class Kind { PREQUEL, MAIN, SIDE }
    enum class Edge { NEXT, GAP, BRANCH }

    data class Node(val item: Int, val row: Int, val kind: Kind, val label: String?)
    data class Link(val from: Int, val to: Int, val type: Edge, val note: String = "")
    data class Waiting(val item: Int, val reason: String)
    data class Layout(val nodes: List<Node>, val links: List<Link>, val waiting: List<Waiting>, val note: String)

    fun number(ordinal: String?): Double? = ordinal?.trim()?.replace(',', '.')?.toDoubleOrNull()

    private fun whole(n: Double) = n == Math.floor(n)
    private fun show(n: Double) = if (whole(n)) n.toLong().toString() else n.toString()

    fun layout(items: List<ShelfItem>, order: Order, manual: List<Long>?): Layout = when (order) {
        Order.PERSONAL -> personal(items, manual)
        Order.PUBLICATION -> publication(items)
        Order.SAGA -> saga(items)
    }

    private fun saga(items: List<ShelfItem>): Layout {
        val waiting = ArrayList<Waiting>()
        val numbered = ArrayList<Pair<Int, Double>>()
        val taken = HashMap<Double, Int>()
        for ((i, it) in items.withIndex()) {
            val n = number(it.ordinal)
            if (n == null) { waiting += Waiting(i, "nessuna fonte indica il numero"); continue }
            val first = taken[n]
            if (first != null) { waiting += Waiting(i, "stesso numero di «${items[first].title}»"); continue }
            taken[n] = i; numbered += i to n
        }
        numbered.sortWith(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first })
        val nodes = ArrayList<Node>(); val links = ArrayList<Link>()
        var lastMain: Pair<Int, Double>? = null   // node index, number
        for ((item, n) in numbered) {
            val side = !whole(n) || items[item].membership == "novella"
            val kind = when { side && lastMain != null -> Kind.SIDE; n <= 0 || items[item].membership == "prequel" -> Kind.PREQUEL; else -> Kind.MAIN }
            val node = nodes.size
            nodes += Node(item, node, kind, show(n))
            val previous = lastMain
            if (kind == Kind.SIDE) { links += Link(previous!!.first, node, Edge.BRANCH); continue }
            if (previous != null) {
                val gap = n - previous.second
                if (previous.second > 0 && n > 0 && gap > 1.0 && whole(n) && whole(previous.second)) {
                    val missing = ((previous.second + 1).toLong() until n.toLong()).joinToString(", ")
                    links += Link(previous.first, node, Edge.GAP, "manca il n. $missing")
                } else links += Link(previous.first, node, Edge.NEXT)
            }
            lastMain = node to n
        }
        val note = if (nodes.isEmpty()) "Nessuna fonte numera i volumi di questa saga: scegli tu l’ordine con «Il tuo ordine»." else ""
        return Layout(nodes, links, waiting, note)
    }

    private fun year(it: ShelfItem) = it.year?.let { Regex("\\d{4}").find(it)?.value?.toInt() }

    private fun publication(items: List<ShelfItem>): Layout {
        val waiting = items.indices.filter { year(items[it]) == null }.map { Waiting(it, "anno di prima pubblicazione sconosciuto") }
        val dated = items.indices.filter { year(items[it]) != null }.sortedWith(compareBy<Int> { year(items[it]) }.thenBy { number(items[it].ordinal) ?: Double.MAX_VALUE }.thenBy { it })
        val nodes = dated.mapIndexed { row, i -> Node(i, row, Kind.MAIN, year(items[i]).toString()) }
        val links = (1 until nodes.size).map { Link(it - 1, it, Edge.NEXT) }
        return Layout(nodes, links, waiting, "Dagli anni di prima pubblicazione dell’opera, non delle edizioni italiane.")
    }

    private fun personal(items: List<ShelfItem>, manual: List<Long>?): Layout {
        if (manual == null) return Layout(emptyList(), emptyList(), emptyList(), "Non hai ancora un ordine tuo: tocca «Cambia l’ordine».")
        val byWork = items.indices.associateBy { items[it].workId }
        val placed = manual.mapNotNull { byWork[it] }.distinct()
        val nodes = placed.mapIndexed { row, i -> Node(i, row, Kind.MAIN, (row + 1).toString()) }
        val links = (1 until nodes.size).map { Link(it - 1, it, Edge.NEXT) }
        val waiting = items.indices.filter { it !in placed }.map { Waiting(it, "aggiunto dopo il tuo ordine") }
        return Layout(nodes, links, waiting, "L’ordine che hai scelto tu: nessun aggiornamento lo cambia.")
    }

    /** The volumes top to bottom for the order editor: your order, else the saga order, then the waiting ones. */
    fun editable(items: List<ShelfItem>, manual: List<Long>?): List<Int> {
        val start = if (manual != null) layout(items, Order.PERSONAL, manual) else layout(items, Order.SAGA, null)
        return start.nodes.map { it.item } + start.waiting.map { it.item }
    }
}
