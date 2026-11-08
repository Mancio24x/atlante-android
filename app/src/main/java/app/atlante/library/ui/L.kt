package app.atlante.library.ui

import android.content.Context
import android.content.res.Resources
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * The app's language. The code and its data speak Italian (book states, page names, saved values); English is
 * applied only to what is shown, from `assets/i18n/en.tsv`: exact sentences, sentences with blanks ("{}" for a
 * number or a title) and, failing those, a sentence cut at " · ", ": " or a new line and translated piece by piece.
 * Italian (the phone's language, or the choice in Impostazioni › Lingua) changes nothing at all.
 */
object L {
    private const val PREFS = "atlante_language"
    /** "" follows the phone, "it" or "en" forces one. */
    @JvmStatic var choice: String = ""; private set
    @JvmStatic var english: Boolean = false; private set
    private var exact: Map<String, String> = emptyMap()
    private var patterns: List<Pair<Regex, String>> = emptyList()
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 6000
    }
    private var loaded = false

    /** Reads the choice and, for English, the dictionary. Cheap after the first time. */
    @JvmStatic @Synchronized
    fun init(context: Context) {
        val c = context.applicationContext ?: context
        choice = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("app", "") ?: ""
        english = decide(choice)
        if (english && !loaded) load(c)
    }

    private fun decide(choice: String): Boolean {
        if (choice == "it") return false
        if (choice == "en") return true
        // Android 13+: the language picked for this app in the system settings.
        val app = AppCompatDelegate.getApplicationLocales()
        val lang = if (!app.isEmpty) app[0]?.language else Resources.getSystem().configuration.locales[0]?.language
        return (lang ?: "it") != "it"
    }

    /** Saves the choice ("", "it", "en") and tells Android, so the system's per-app language agrees. */
    @JvmStatic
    fun choose(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("app", value).commit()
        try { AppCompatDelegate.setApplicationLocales(if (value.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(value)) } catch (_: RuntimeException) { }
        synchronized(this) { cache.clear() }
        init(context)
    }

    /** For tests: English or Italian without touching preferences. */
    @JvmStatic @Synchronized
    fun force(context: Context?, en: Boolean) { english = en; cache.clear(); if (en && !loaded && context != null) load(context.applicationContext ?: context) }

    private fun load(c: Context) {
        val e = HashMap<String, String>(2048); val p = ArrayList<Pair<Regex, String>>()
        c.assets.open("i18n/en.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val tab = line.indexOf('\t'); if (tab <= 0) continue
                val it = line.substring(0, tab).replace("\\n", "\n"); val en = line.substring(tab + 1).replace("\\n", "\n")
                if (it.contains("{}")) p.add(compile(it) to en) else e[it] = en
            }
        }
        // The more fixed words a sentence has, the earlier it is tried.
        exact = e; patterns = p.sortedByDescending { it.first.pattern.replace("(.+?)", "").length }; loaded = true
    }

    private fun compile(it: String): Regex {
        val parts = it.split("{}")
        return Regex("^" + parts.joinToString("(.+?)") { Regex.escape(it) } + "$", RegexOption.DOT_MATCHES_ALL)
    }

    /** The text as shown in the app's language. */
    @JvmStatic
    fun t(text: String?): String {
        if (text == null) return ""
        if (!english || text.isBlank()) return text
        synchronized(this) { cache[text]?.let { return it } }
        val out = translate(text, 0)
        synchronized(this) { cache[text] = out; if (out == text && misses != null && text.any { it.isLetter() }) misses!!.add(text) }
        return out
    }

    @JvmStatic fun t(text: CharSequence?): CharSequence? =
        if (text == null || !english || (text is Spanned && text.getSpans(0, text.length, Any::class.java).isNotEmpty())) text else t(text.toString())

    /** Set by tests: the Italian-looking texts the dictionary does not cover. */
    @JvmStatic var misses: MutableSet<String>? = null
    private val italian = Regex("\\b(il|lo|la|gli|di|del|della|dei|non|per|con|una|libri|libro|pagine|pagina|tocca|cerca|leggi|letti|letto|nessun|nessuna|tutti|tutte|sono|questo|questa|alla|nel|nella|sul|che|ancora|oggi|giorno|giorni|mese|apri|salva|annulla|chiudi|aggiungi|elimina|scegli|copertina|copertine|saga|saghe|autore|autori|anni|anno|lettura|scheda|schede|biblioteca|libreria|impostazioni|da|ed?|o|ai|al|alle|sui|più|già|è)\\b|[àèìòù]", RegexOption.IGNORE_CASE)

    private val separators = listOf("\n", " · ", " — ", ": ", ". ", ", ")
    private val number = Regex("^([+\\-−]?[\\d.,:/%]+\\s*)(.+)$")

    private fun translate(s: String, depth: Int): String {
        if (depth > 6) return s
        val lead = s.length - s.trimStart().length; val trail = s.length - s.trimEnd().length
        val core = s.trim()
        if (core.isEmpty()) return s
        fun wrap(x: String) = s.substring(0, lead) + x + s.substring(s.length - trail)
        exact[core]?.let { return wrap(it) }
        // Capital first letter differs only ("libri letti" / "Libri letti").
        exact[core.replaceFirstChar { it.lowercase() }]?.let { return wrap(it.replaceFirstChar { c -> c.uppercase() }) }
        date(core)?.let { return wrap(it) }
        // Written in capitals by the screen ("SAGHE", "LA TUA ROTTA · 2026"): translated as normal text, then capitals again.
        if (core == core.uppercase() && core.any { it.isLetter() } && core.lowercase() != core) {
            val lower = core.lowercase(Locale.ITALIAN).replaceFirstChar { it.uppercase() }
            val done = translate(lower, depth + 1)
            if (done != lower) return wrap(done.uppercase(Locale.ENGLISH))
        }
        for ((re, en) in patterns) {
            val m = re.find(core) ?: continue
            val groups = m.groupValues.drop(1).map { translate(it, depth + 1) }
            return wrap(fill(en, groups))
        }
        // Cut at the separator that leaves the fewest pieces in Italian.
        var best: String? = null; var bestLeft = Int.MAX_VALUE
        for (sep in separators) {
            if (!core.contains(sep)) continue
            val pieces = core.split(sep)
            val done = pieces.map { translate(it, depth + 1) }
            if (done == pieces) continue
            val left = done.indices.count { done[it] == pieces[it] && italian.containsMatchIn(pieces[it]) }
            if (left < bestLeft) { bestLeft = left; best = done.joinToString(sep) }
            if (left == 0) break
        }
        if (best != null) return wrap(best)
        number.find(core)?.let { m ->
            val rest = translate(m.groupValues[2], depth + 1)
            if (rest != m.groupValues[2]) return wrap(m.groupValues[1] + rest)
        }
        // «Title» and a word or two around it: the title stays as it is.
        return s
    }

    /** "{}" in order, or "{1}", "{2}"… when English wants another order. */
    private fun fill(template: String, groups: List<String>): String {
        if (Regex("\\{\\d\\}").containsMatchIn(template)) return Regex("\\{(\\d)\\}").replace(template) { groups.getOrElse(it.groupValues[1].toInt() - 1) { "" } }
        val sb = StringBuilder(); var i = 0; var g = 0
        while (i < template.length) {
            if (template.startsWith("{}", i)) { sb.append(groups.getOrElse(g++) { "" }); i += 2 } else sb.append(template[i++])
        }
        return sb.toString()
    }

    private val months = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")
    private val days = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
    private val dateRe = Regex("^(?:(" + days.joinToString("|") + ") )?(\\d{1,2}) (" + months.joinToString("|") + "|" + months.joinToString("|") { it.take(3) } + ")(?: (\\d{4}))?(?: (\\d{1,2}:\\d{2}))?$", RegexOption.IGNORE_CASE)
    private val monthYear = Regex("^(" + months.joinToString("|") + ")(?: (\\d{4}))?$", RegexOption.IGNORE_CASE)

    /** "8 novembre 2026", "SABATO 8 NOVEMBRE", "Novembre 2026" written by Italian date formats. */
    private fun date(s: String): String? {
        val upper = s == s.uppercase() && s.any { it.isLetter() }
        dateRe.find(s)?.let { m ->
            val (day, d, mon, year, time) = m.destructured
            val mi = months.indexOfFirst { it.equals(mon, true) || it.take(3).equals(mon, true) }
            val monthEn = java.time.Month.of(mi + 1).getDisplayName(if (mon.length <= 3) java.time.format.TextStyle.SHORT else java.time.format.TextStyle.FULL, Locale.ENGLISH)
            val dayEn = if (day.isEmpty()) "" else java.time.DayOfWeek.of(days.indexOf(day.lowercase()) + 1).getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " "
            val out = dayEn + "$d $monthEn" + (if (year.isEmpty()) "" else " $year") + (if (time.isEmpty()) "" else " $time")
            return if (upper) out.uppercase(Locale.ENGLISH) else out
        }
        monthYear.find(s)?.let { m ->
            val mi = months.indexOf(m.groupValues[1].lowercase())
            val out = java.time.Month.of(mi + 1).getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + (if (m.groupValues[2].isEmpty()) "" else " " + m.groupValues[2])
            return if (upper) out.uppercase(Locale.ENGLISH) else out
        }
        return null
    }

    /** Locale for dates and numbers shown to the reader. */
    @JvmStatic fun locale(): Locale = if (english) Locale.ENGLISH else Locale.ITALIAN

    /** Month initials, January first. */
    @JvmStatic fun monthInitials(): List<String> = (if (english) "JFMAMJJASOND" else "GFMAMGLASOND").map { it.toString() }

    /** Week day initials, Monday first. */
    @JvmStatic fun weekInitials(): List<String> = if (english) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("L", "M", "M", "G", "V", "S", "D")

    @JvmStatic fun toast(c: Context, text: CharSequence, duration: Int): Toast = Toast.makeText(c, t(text), duration)

    /**
     * Translates every label under [root] (never what is typed in a field). Called after each layout, so new
     * pages, list cells, sheets and dialogs are covered; a label already in English is left alone.
     */
    @JvmStatic
    fun translateTree(root: View?) {
        if (!english || root == null) return
        walk(root)
    }

    private fun walk(v: View) {
        v.contentDescription?.let { d -> val n = t(d); if (n !== d && n.toString() != d.toString()) v.contentDescription = n }
        when (v) {
            is TextInputLayout -> v.hint?.let { h -> val n = t(h); if (n.toString() != h.toString()) v.hint = n }
            is EditText -> v.hint?.let { h -> val n = t(h); if (n.toString() != h.toString()) v.hint = n }
            is TextView -> { val cur = v.text; val n = t(cur); if (n != null && n !== cur && n.toString() != cur.toString()) v.text = n }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    /** Keeps [root] translated: every layout pass re-checks the labels. */
    @JvmStatic
    fun watch(root: View) {
        if (!english) return
        translateTree(root)
        root.viewTreeObserver.addOnGlobalLayoutListener { translateTree(root) }
    }
}
