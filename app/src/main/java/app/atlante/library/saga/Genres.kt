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
        "Saggistica" to listOf("saggi", "nonfiction", "non-fiction", "storia", "history", "filosofi", "philosophy", "scienz", "science", "biografi", "biography"),
        "Narrativa" to listOf("narrativa", "fiction", "romanz", "novel", "letteratura"),
    )

    private val SUBJECT = mapOf(
        "Fantascienza" to "science_fiction", "Fantasy" to "fantasy", "Horror" to "horror", "Giallo" to "mystery_and_detective_stories",
        "Thriller" to "thrillers", "Romanzo storico" to "historical_fiction", "Rosa" to "romance", "Classici" to "classic_literature",
        "Fumetti" to "comics_&_graphic_novels", "Ragazzi" to "young_adult_fiction", "Poesia" to "poetry",
    )

    val main: List<String> get() = MAIN.keys.toList()

    /** "Epic Fantasy", "Fantasy epico", "fantasy / magia" → "Fantasy"; unknown labels → null. */
    @JvmStatic fun normalize(label: String?): String? {
        val text = fold(label ?: return null).takeIf { it.isNotBlank() } ?: return null
        return MAIN.entries.firstOrNull { (_, words) -> words.any { text.contains(it) } }?.key
    }

    fun subjectFor(label: String?) = normalize(label)?.let { SUBJECT[it] }

    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
