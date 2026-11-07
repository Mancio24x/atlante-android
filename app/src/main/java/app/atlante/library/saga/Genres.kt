package app.atlante.library.saga

import java.text.Normalizer

/**
 * Genre normalization: many labels from catalogs and users, one main genre ("Epic Fantasy" → Fantasy).
 * Also gives the Open Library subject used by "Potrebbero interessarti".
 */
object Genres {
    /** Main genre → words that identify it (lower case, no accents). Order matters: the first match wins. */
    private val MAIN = linkedMapOf(
        "Fantascienza" to listOf("fantascienz", "science fiction", "sci-fi", "scifi", "cyberpunk", "space opera", "distopi", "dystopi"),
        "Fantasy" to listOf("fantasy", "fantastic", "litrpg", "sword and sorcery", "magia", "magic"),
        "Horror" to listOf("horror", "orrore", "gotico", "gothic"),
        "Giallo" to listOf("giall", "mystery", "detective", "poliziesc", "noir", "crime"),
        "Thriller" to listOf("thriller", "suspense", "spionaggio", "spy"),
        "Romanzo storico" to listOf("storic", "historical fiction"),
        "Rosa" to listOf("rosa", "romance", "romantic", "sentimental"),
        "Classici" to listOf("classic", "classici"),
        "Fumetti" to listOf("fumett", "comic", "graphic novel", "manga"),
        "Ragazzi" to listOf("ragazz", "young adult", "juvenile", "children", "bambini"),
        "Poesia" to listOf("poesi", "poetry", "poems"),
        "Avventura" to listOf("avventur", "adventure", "western", "pirat"),
        // Non-fiction by subject; "Saggistica" stays for the rest (essays, generic non-fiction).
        "Biografia" to listOf("biografi", "biography", "memoir", "autobiograf"),
        "Filosofia" to listOf("filosofi", "philosoph"),
        "Religione" to listOf("religio", "teolog", "theolog", "spiritual"),
        "Storia" to listOf("history", "storia", "world war", "guerra mondiale"),
        "Scienza" to listOf("scienz", "science", "matemat", "mathemat", "physics", "fisica", "biolog", "astronom"),
        "Saggistica" to listOf("saggi", "nonfiction", "non-fiction", "essay"),
        "Narrativa" to listOf("narrativa", "fiction", "romanz", "novel", "letteratura"),
    )

    private val SUBJECT = mapOf(
        "Fantascienza" to "science_fiction", "Fantasy" to "fantasy", "Horror" to "horror", "Giallo" to "mystery_and_detective_stories",
        "Thriller" to "thrillers", "Romanzo storico" to "historical_fiction", "Rosa" to "romance", "Classici" to "classic_literature",
        "Fumetti" to "comics_&_graphic_novels", "Ragazzi" to "young_adult_fiction", "Poesia" to "poetry", "Avventura" to "adventure",
    )

    val main: List<String> get() = MAIN.keys.toList()

    /** "Epic Fantasy", "Fantasy epico", "fantasy / magia" → "Fantasy"; unknown labels → null. */
    @JvmStatic fun normalize(label: String?): String? {
        val text = fold(label ?: return null).takeIf { it.isNotBlank() } ?: return null
        return MAIN.entries.firstOrNull { (_, words) -> words.any { text.contains(it) } }?.key
    }

    fun subjectFor(label: String?) = normalize(label)?.let { SUBJECT[it] }

    /** Generic labels that only count when nothing more specific is known ("Fiction" next to "Fantasy" is noise). */
    private val GENERIC = setOf("Narrativa", "Classici")

    /**
     * Every main genre of a label made of several ("Fiction, Science fiction, Dystopias" → Fantascienza; "Fantasy;
     * Adventure" → Fantasy, Avventura), in the order written; each part is normalized on its own, so "Science
     * fiction" is Fantascienza and never also Scienza. Generic labels only when they are alone.
     */
    @JvmStatic fun all(label: String?): List<String> {
        if (label.isNullOrBlank()) return emptyList()
        val found = label.split(Regex("[,;/|·]+|\\s+-\\s+")).mapNotNull { normalize(it) }.distinct()
        val specific = found.filter { it !in GENERIC }
        return specific.ifEmpty { found }.ifEmpty { listOfNotNull(normalize(label)) }
    }

    /** The genres indexed for a book: the canonical list saved by "Indicizza i generi", else those of its label. */
    @JvmStatic fun of(b: app.atlante.library.model.Book): List<String> {
        val saved = b.meta.optJSONArray("genres")
        if (saved != null && saved.length() > 0) return (0 until saved.length()).map { saved.optString(it) }.filter { it.isNotBlank() }.distinct()
        return all(b.genre)
    }

    /** The main genre of a book (the first of [of]), or null. */
    @JvmStatic fun mainOf(b: app.atlante.library.model.Book): String? = of(b).firstOrNull()

    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
