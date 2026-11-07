package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import app.atlante.library.ui.ThemePalette
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

const val OTHER_GENRES = "Altri generi"
const val NO_GENRE = "Genere non indicato"

fun genreCounts(books: List<Book>): List<Pair<String, Int>> = genreCounts(books, membership = false)

/**
 * Books per genre from the genre index (Genres.of: "Indicizza i generi", else the sheet's label).
 * [membership]: a book with several genres counts in each (the bars add up to more than the books);
 * otherwise only its main genre (exclusive: the bars add up to the books). Unknown labels go to "Altri generi",
 * books without any genre last.
 */
fun genreCounts(books: List<Book>, membership: Boolean): List<Pair<String, Int>> {
    val counts = HashMap<String, Int>()
    for (b in books) {
        val all = app.atlante.library.saga.Genres.of(b)
        val keys = when { all.isEmpty() -> listOf(if (b.genre.isBlank()) NO_GENRE else OTHER_GENRES); membership -> all; else -> listOf(all.first()) }
        for (k in keys) counts[k] = (counts[k] ?: 0) + 1
    }
    return counts.entries.sortedWith(compareBy<Map.Entry<String, Int>>({ it.key == OTHER_GENRES || it.key == NO_GENRE }, { it.key == NO_GENRE }, { -it.value }, { it.key }))
        .map { it.key to it.value }
}

/** A reading session as shown in the calendar. */
data class DaySession(val title: String, val pages: Int, val minutes: Long)

/** Everything the Statistics screen shows, computed once from the catalog. */
class StatsData(books: List<Book>, val yearGoal: Int, val monthGoal: Int, today: LocalDate = LocalDate.now()) {
    val pages: Long; val minutes: Long
    val streak = ReadingJournal.streak(books, today)
    val yearDone = ReadingJournal.completed(books, today, false)
    val monthDone = ReadingJournal.completed(books, today, true)
    val byState = listOf(Book.READING, Book.TO_READ, Book.PAUSED, Book.READ).map { s -> s to books.count { it.status == s } }
    val total = books.size
    /**
     * One main genre per book ("Epic Fantasy", "fantasy / magia" and "Fantasy" are all Fantasy), most read first;
     * labels no main genre recognizes go together in "Altri generi", books without a genre last.
     */
    val genres: List<Pair<String, Int>> = genreCounts(books)
    /** The books themselves, for the genre section's own filters (state, membership or main genre). */
    val books: List<Book> = books
    val sessions: Map<LocalDate, List<DaySession>>
    /** Previous months against their monthly goal (empty when the monthly goal is off). */
    var monthHistory: List<Pair<java.time.YearMonth, app.atlante.library.model.ReadingGoals.Progress>> = emptyList()
    init {
        val totals = ReadingJournal.totals(books, null); pages = totals[0]; minutes = totals[1] / 60
        val map = HashMap<LocalDate, MutableList<DaySession>>()
        for (b in books) {
            val a = ReadingJournal.array(b, "sessions")
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                val day = runCatching { LocalDate.parse(s.optString("date")) }.getOrNull() ?: continue
                if (s.optInt("pages") > 0 || s.optLong("seconds") > 0)
                    map.getOrPut(day) { mutableListOf() }.add(DaySession(b.title, s.optInt("pages"), s.optLong("seconds") / 60))
            }
        }
        sessions = map
    }
}

object StatisticsView {
    /** Called from MainActivity: the Compose screen inside the existing View-based navigation. */
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, books: List<Book>, yearGoal: Int, monthGoal: Int, motion: Boolean, onGoals: Runnable,
               goals: app.atlante.library.model.ReadingGoals? = null): View =
        ComposeView(activity).apply {
            val data = StatsData(books, yearGoal, monthGoal).also { d -> goals?.takeIf { it.monthOn }?.let { g -> d.monthHistory = g.history(books, 6) } }
            setContent { AtlanteTheme(palette) { StatisticsScreen(data, palette, motion) { onGoals.run() } } }
        }
}

@Composable
fun StatisticsScreen(data: StatsData, palette: ThemePalette, motion: Boolean, onGoals: () -> Unit) {
    var shown by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { shown = true }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 28.dp)) {
        Text("Ogni pagina conta.", style = MaterialTheme.typography.headlineSmall)
        Text("Le tue letture, un giorno alla volta.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Section("IL TUO VIAGGIO") {
            Row(Modifier.fillMaxWidth()) {
                Metric(Modifier.weight(1f), if (shown) data.pages.toInt() else 0, "pagine registrate", motion)
                Metric(Modifier.weight(1f), if (shown) data.minutes.toInt() else 0, "minuti di lettura", motion)
                Metric(Modifier.weight(1f), if (shown) data.streak else 0, "giorni di fila", motion)
            }
        }
        GoalCard(data, shown, motion, onGoals)
        Headline("Calendario di lettura")
        Calendar(data, palette, motion)
        Headline("La tua biblioteca")
        Section("") {
            data.byState.forEach { (state, count) ->
                val share by animateFloatAsState(if (shown && data.total > 0) count.toFloat() / data.total else 0f, if (motion) tween(700) else tween(0), label = "state")
                Text("$state · $count", style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp))
            }
        }
        Headline("Generi nella tua biblioteca")
        if (data.genres.isNotEmpty()) Section("") { GenreSection(data.books, shown, motion) }
    }
}

/**
 * Horizontal bars, one per main genre, longest first: one series, so one colour (the theme's primary) and no
 * legend; the count sits at the end of each bar in text colour. "Altri generi" and "Genere non indicato" are
 * not genres: they recede in the outline colour. Each row reads as one sentence for TalkBack.
 */
@Composable
fun GenreBars(genres: List<Pair<String, Int>>, total: Int, shown: Boolean, motion: Boolean) {
    val max = genres.maxOf { it.second }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        genres.forEach { (genre, count) ->
            val share by animateFloatAsState(if (shown) count.toFloat() / max else 0f, if (motion) tween(700) else tween(0), label = "genre")
            val percent = if (total > 0) count * 100 / total else 0
            val muted = genre == OTHER_GENRES || genre == NO_GENRE
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$genre: $count libri, $percent per cento" },
                verticalAlignment = Alignment.CenterVertically) {
                Text(genre, Modifier.width(118.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (share > 0f) Box(Modifier.weight(share.coerceAtLeast(0.001f)).height(16.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                        .background(if (muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary))
                    Text("$count", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                    if (share < 1f) Spacer(Modifier.weight((1f - share).coerceAtLeast(0.001f)))
                }
            }
        }
    }
}

/** Genres of the library, of the books read, being read or to read; every genre of each book, or its main one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreSection(books: List<Book>, shown: Boolean, motion: Boolean) {
    var state by remember { mutableStateOf<String?>(null) }
    var membership by remember { mutableStateOf(true) }
    val chosen = remember(books, state) { if (state == null) books else books.filter { it.status == state } }
    val genres = remember(chosen, membership) { genreCounts(chosen, membership) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(null to "Tutti", Book.READ to "Letti", Book.READING to "In lettura", Book.TO_READ to "Da leggere").forEach { (s, label) ->
            FilterChip(state == s, { state = s }, label = { Text(label) })
        }
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SegmentedButton(membership, { membership = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Tutti i generi", maxLines = 1) }
        SegmentedButton(!membership, { membership = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Genere principale", maxLines = 1) }
    }
    Text(if (membership) "Un libro con più generi conta in ognuno: le percentuali sono sui ${chosen.size} libri e insieme possono superare il 100%."
        else "Ogni libro conta una volta, nel suo genere principale: le percentuali sui ${chosen.size} libri fanno 100%.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    if (genres.isEmpty()) Text("Nessun libro in questo stato.", style = MaterialTheme.typography.bodyMedium)
    else GenreBars(genres, chosen.size, shown, motion)
    if (genres.any { it.first == NO_GENRE }) Text("Per i libri senza genere: Impostazioni › Operazioni in background › Indicizza i generi.",
        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun Headline(text: String) {
    Spacer(Modifier.height(8.dp)); Text(text, style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(14.dp))
}

@Composable private fun Section(label: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(18.dp)) {
            if (label.isNotEmpty()) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** A number that counts up to its value (or appears at once when animations are off). */
@Composable private fun Metric(modifier: Modifier, value: Int, label: String, motion: Boolean) {
    val shown by animateIntAsState(value, if (motion) tween(900) else tween(0), label = label)
    Column(modifier.semantics { contentDescription = "$value $label" }) {
        Text("$shown", style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun GoalCard(data: StatsData, shown: Boolean, motion: Boolean, onGoals: () -> Unit) {
    val target = if (shown) (data.yearDone.toFloat() / maxOf(1, data.yearGoal)).coerceAtMost(1f) else 0f
    val progress by animateFloatAsState(target, if (motion) spring(dampingRatio = 0.8f, stiffness = 60f) else tween(0), label = "goal")
    Section("LA TUA ROTTA · ${LocalDate.now().year}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(74.dp).semantics { contentDescription = "${data.yearDone} libri su ${data.yearGoal}" }, strokeWidth = 7.dp)
            Column(Modifier.padding(start = 18.dp)) {
                Text(if (data.yearGoal > 0) "${data.yearDone} / ${data.yearGoal} libri" else "${data.yearDone} libri", style = MaterialTheme.typography.headlineSmall)
                Text("Quest’anno", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(if (data.monthGoal > 0) "${data.monthDone} / ${data.monthGoal} questo mese" else "${data.monthDone} questo mese", color = MaterialTheme.colorScheme.primary)
            }
        }
        if (data.monthHistory.isNotEmpty()) {
            Text("Mesi precedenti", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
            data.monthHistory.forEach { (m, p) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(m.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ITALIAN).replaceFirstChar { it.uppercase() } + " ${m.year}",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${p.done} / ${p.goal}" + if (p.reached) "  ✓" else "", style = MaterialTheme.typography.bodyMedium,
                        color = if (p.reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (data.yearGoal > 0 && data.yearDone >= data.yearGoal) Text("★ Obiettivo annuale raggiunto!", Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onGoals, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Imposta obiettivi") }
    }
}

/** Month calendar: slides left or right when changing month; a tap on a day lists its sessions. */
@Composable private fun Calendar(data: StatsData, palette: ThemePalette, motion: Boolean) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var forward by remember { mutableStateOf(true) }
    var opened by remember { mutableStateOf<LocalDate?>(null) }
    Section("") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { forward = false; month = month.minusMonths(1) }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN)).replaceFirstChar { it.uppercase() },
                Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { forward = true; month = month.plusMonths(1) }) { Text("›", style = MaterialTheme.typography.titleLarge) }
        }
        AnimatedContent(month, transitionSpec = {
            if (!motion) slideInHorizontally(tween(0)) { 0 } togetherWith slideOutHorizontally(tween(0)) { 0 }
            else if (forward) slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
            else slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
        }, label = "month") { shownMonth ->
            Column {
                Row { listOf("L", "M", "M", "G", "V", "S", "D").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                val blanks = shownMonth.atDay(1).dayOfWeek.value - 1
                val cells = List(blanks) { null } + (1..shownMonth.lengthOfMonth()).map { shownMonth.atDay(it) }
                cells.chunked(7).forEach { week ->
                    Row {
                        week.forEach { day -> DayCell(Modifier.weight(1f), day, day?.let { data.sessions[it] }, palette) { opened = it } }
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Text("Un punto segna i giorni con sessioni. Tocca un giorno per i dettagli.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    opened?.let { day ->
        val list = data.sessions[day].orEmpty()
        AlertDialog(onDismissRequest = { opened = null }, confirmButton = { TextButton(onClick = { opened = null }) { Text("Chiudi") } },
            title = { Text(day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN))) },
            text = {
                Column {
                    Text("${list.sumOf { it.pages }} pagine · ${list.sumOf { it.minutes }} minuti", style = MaterialTheme.typography.titleMedium)
                    if (list.isEmpty()) Text("Nessuna sessione registrata.", Modifier.padding(top = 8.dp))
                    list.forEach { Text("${it.title} · ${it.pages} pagine · ${it.minutes} min", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            })
    }
}

@Composable private fun DayCell(modifier: Modifier, day: LocalDate?, sessions: List<DaySession>?, palette: ThemePalette, onOpen: (LocalDate) -> Unit) {
    if (day == null) { Spacer(modifier.height(48.dp)); return }
    val today = day == LocalDate.now(); val active = !sessions.isNullOrEmpty()
    Column(modifier.height(48.dp).padding(2.dp).clip(CircleShape)
        .background(if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
        .clickable { onOpen(day) }
        .semantics { contentDescription = day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ITALIAN)) + if (active) ", con letture" else ", nessuna sessione" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("${day.dayOfMonth}", color = if (today) palette.warmColor else MaterialTheme.colorScheme.onSurface, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal)
        if (active) Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}
