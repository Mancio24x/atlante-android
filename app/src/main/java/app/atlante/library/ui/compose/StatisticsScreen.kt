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
import androidx.compose.foundation.border
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

private val Serif = androidx.compose.ui.text.font.FontFamily.Serif
private val Round20 = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
private fun finishedOn(b: Book): LocalDate? = runCatching { LocalDate.parse(b.finished) }.getOrNull()?.takeIf { b.status == Book.READ }

/**
 * Statistiche (design 4b): pick the period (Settimana · Mese · Anno) and see its pages with the trend, then la tua
 * rotta month by month, a calendar coloured by intensity with the day's details inline, the library and its genres.
 */
@Composable
fun StatisticsScreen(data: StatsData, palette: ThemePalette, motion: Boolean, onGoals: () -> Unit) {
    var period by remember { mutableStateOf("Settimana") }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        // Period switch, a segmented pill under the title.
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp).height(40.dp).clip(Round20).background(colors.outlineVariant).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("Settimana", "Mese", "Anno").forEach { p ->
                val on = p == period
                Box(Modifier.weight(1f).fillMaxHeight().clip(androidx.compose.foundation.shape.RoundedCornerShape(17.dp)).background(if (on) colors.surfaceContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { period = p }.semantics { contentDescription = p + if (on) ", selezionato" else "" }, contentAlignment = Alignment.Center) {
                    Text(p, style = MaterialTheme.typography.labelLarge, color = if (on) colors.onSurface else colors.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PeriodCard(data, period, palette)
            RouteCard(data, palette, onGoals)
            HeatCalendar(data, palette, motion)
            LibraryCard(data, palette)
            GenreCard(data)
        }
    }
}

private fun pagesOn(data: StatsData, day: LocalDate) = data.sessions[day].orEmpty().sumOf { it.pages }
private fun minutesOn(data: StatsData, day: LocalDate) = data.sessions[day].orEmpty().sumOf { it.minutes }
private fun hours(minutes: Long) = if (minutes < 60) "$minutes min" else "${minutes / 60} h" + if (minutes % 60 > 0) " ${minutes % 60}′" else ""

@Composable private fun Card(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = Round20, colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), content = content)
    }
}
@Composable private fun Kicker(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

@Composable private fun PeriodCard(data: StatsData, period: String, palette: ThemePalette) {
    val colors = MaterialTheme.colorScheme; val today = LocalDate.now()
    val from: LocalDate; val to: LocalDate; val label: String; val bars: List<Triple<String, Int, Boolean>>; var compare: Pair<LocalDate, LocalDate>? = null; val against: String
    val it = Locale.ITALIAN
    when (period) {
        "Settimana" -> { from = today.minusDays(today.dayOfWeek.value - 1L); to = from.plusDays(6)
            label = "SETTIMANA · ${from.dayOfMonth} – ${to.format(DateTimeFormatter.ofPattern("d MMMM", it))}".uppercase(it)
            bars = (0..6).map { d -> val day = from.plusDays(d.toLong()); Triple(listOf("L", "M", "M", "G", "V", "S", "D")[d], pagesOn(data, day), day == today) }
            compare = from.minusDays(7) to today.minusDays(7); against = "la settimana scorsa" }
        "Mese" -> { from = today.withDayOfMonth(1); to = today.withDayOfMonth(today.lengthOfMonth())
            label = (today.format(DateTimeFormatter.ofPattern("MMMM yyyy", it)) + " · fino a oggi").uppercase(it)
            bars = (0 until today.lengthOfMonth()).map { d -> val day = from.plusDays(d.toLong()); Triple(if (d % 7 == 0) "${d + 1}" else "", pagesOn(data, day), day == today) }
            compare = from.minusMonths(1) to today.minusMonths(1); against = "gli stessi giorni del mese scorso" }
        else -> { from = today.withDayOfYear(1); to = today.withDayOfYear(today.lengthOfYear())
            label = "${today.year} · DA GENNAIO A OGGI"
            bars = (1..12).map { m -> val start = LocalDate.of(today.year, m, 1); val p = if (m > today.monthValue) 0 else (0 until start.lengthOfMonth()).sumOf { d -> pagesOn(data, start.plusDays(d.toLong())) }
                Triple(listOf("G", "F", "M", "A", "M", "G", "L", "A", "S", "O", "N", "D")[m - 1], p, m == today.monthValue) }
            against = "" }
    }
    val end = if (to.isAfter(today)) today else to
    val days = generateSequence(from) { d -> d.plusDays(1) }.takeWhile { d -> !d.isAfter(end) }.toList()
    val pages = days.sumOf { d -> pagesOn(data, d) }; val minutes = days.sumOf { d -> minutesOn(data, d) }; val active = days.count { d -> !data.sessions[d].isNullOrEmpty() }
    val finished = data.books.count { b -> finishedOn(b)?.let { d -> !d.isBefore(from) && !d.isAfter(end) } == true }
    val previous = compare?.let { (a, b) -> generateSequence(a) { d -> d.plusDays(1) }.takeWhile { d -> !d.isAfter(b) }.sumOf { d -> pagesOn(data, d) } } ?: 0
    Card {
        Kicker(label)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text("$pages", style = MaterialTheme.typography.displaySmall.copy(fontFamily = Serif))
            Text("pagine", Modifier.padding(start = 8.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        val trend = when {
            compare == null -> if (active > 0) "In media ${pages / active} pagine nei giorni in cui leggi" else "Nessuna lettura registrata quest’anno"
            previous == 0 -> if (pages > 0) "Nessuna pagina registrata ne${if (period == "Mese") "gli stessi giorni" else "lla settimana"} prima" else "Nessuna lettura ancora"
            else -> { val delta = (pages - previous) * 100 / previous; (if (delta >= 0) "↑ $delta%" else "↓ ${-delta}%") + " rispetto a $against" }
        }
        Text(trend, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (trend.startsWith("↓")) palette.warmColor else colors.primary)
        val max = bars.maxOf { b -> b.second }.coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(120.dp).padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(if (bars.size > 12) 2.dp else if (bars.size > 7) 5.dp else 10.dp),
            verticalAlignment = Alignment.Bottom) {
            bars.forEach { (l, p, now) ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(Modifier.widthIn(max = 28.dp).fillMaxWidth().height((4 + 76 * p / max).dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(if (bars.size > 12) 3.dp else 6.dp))
                        .background(if (now) palette.warmColor else if (p > 0) colors.primary else colors.outlineVariant))
                    Text(l, Modifier.padding(top = 6.dp).height(14.dp), style = MaterialTheme.typography.labelSmall, color = if (now) palette.warmColor else colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth()) {
            listOf(hours(minutes) to "di lettura", "$active / ${days.size}" to "giorni attivi", "$finished" to (if (finished == 1) "libro finito" else "libri finiti")).forEach { (v, l) ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
                    Text(v, style = MaterialTheme.typography.titleLarge.copy(fontFamily = Serif)); Text(l, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun RouteCard(data: StatsData, palette: ThemePalette, onGoals: () -> Unit) {
    val colors = MaterialTheme.colorScheme; val today = LocalDate.now()
    val goal = data.yearGoal; val done = data.yearDone
    val share = if (goal > 0) (done.toFloat() / goal).coerceAtMost(1f) else 0f
    val projected = done * today.lengthOfYear() / today.dayOfYear.coerceAtLeast(1)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { share }, modifier = Modifier.fillMaxSize(), strokeWidth = 8.dp, trackColor = colors.outlineVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                Text(if (goal > 0) "${(share * 100).toInt()}%" else "$done", style = MaterialTheme.typography.titleMedium.copy(fontFamily = Serif))
            }
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Kicker("LA TUA ROTTA · ${today.year}")
                Text(if (goal > 0) "$done di $goal libri" else "$done " + if (done == 1) "libro" else "libri", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleLarge.copy(fontFamily = Serif))
                if (goal > 0 && done < goal) {
                    val weeks = ((today.lengthOfYear() - today.dayOfYear) / 7.0).coerceAtLeast(1.0)
                    Text("Al tuo ritmo arrivi a $projected: servono " + String.format(Locale.ITALIAN, "%.1f", (goal - done) / weeks) + " libri a settimana.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else if (goal > 0) Text("★ Obiettivo annuale raggiunto!", style = MaterialTheme.typography.bodySmall, color = palette.warmColor)
            }
        }
        // Month by month: reached the monthly goal (or finished something, without a goal), this month, months to come.
        val month = data.monthGoal; var reached = 0
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..12).forEach { m ->
                val count = data.books.count { b -> finishedOn(b)?.let { d -> d.year == today.year && d.monthValue == m } == true }
                val hit = if (month > 0) count >= month else count > 0
                if (m < today.monthValue && hit) reached++
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)
                    Box(Modifier.fillMaxWidth().height(22.dp).clip(shape).background(when { m > today.monthValue -> androidx.compose.ui.graphics.Color.Transparent; m == today.monthValue -> palette.warmColor.copy(alpha = .18f); hit -> colors.primary; else -> colors.outlineVariant })
                        .then(if (m > today.monthValue) Modifier.border(1.dp, colors.outlineVariant, shape) else if (m == today.monthValue) Modifier.border(1.dp, palette.warmColor, shape) else Modifier))
                    Text(listOf("G", "F", "M", "A", "M", "G", "L", "A", "S", "O", "N", "D")[m - 1], Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (month > 0) "Obiettivo mensile ($month) raggiunto in $reached mesi su ${today.monthValue - 1}" else "Mesi con almeno un libro finito: $reached",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            TextButton(onClick = onGoals) { Text("Modifica") }
        }
    }
}

@Composable private fun HeatCalendar(data: StatsData, palette: ThemePalette, motion: Boolean) {
    val colors = MaterialTheme.colorScheme; val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(today) }
    val levels = listOf(colors.outlineVariant.copy(alpha = .5f), colors.primary.copy(alpha = .25f), colors.primary.copy(alpha = .5f), colors.primary.copy(alpha = .78f), colors.primary)
    fun level(p: Int) = when { p <= 0 -> 0; p < 15 -> 1; p < 28 -> 2; p < 42 -> 3; else -> 4 }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) { Text("‹", style = MaterialTheme.typography.titleLarge, color = colors.primary) }
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN)).replaceFirstChar { it.uppercase() }, Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = Serif))
            IconButton(onClick = { if (month < YearMonth.now()) month = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Text("›", style = MaterialTheme.typography.titleLarge, color = colors.primary) }
        }
        Row(Modifier.padding(top = 4.dp, bottom = 6.dp)) { listOf("L", "M", "M", "G", "V", "S", "D").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) } }
        val blanks = month.atDay(1).dayOfWeek.value - 1
        val cells = List(blanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { day ->
                        if (day == null) Spacer(Modifier.weight(1f)) else {
                            val future = day.isAfter(today); val p = pagesOn(data, day); val l = level(p)
                            val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                            Box(Modifier.weight(1f).aspectRatio(1f).clip(shape).background(if (future) androidx.compose.ui.graphics.Color.Transparent else levels[l])
                                .then(if (day == selected) Modifier.border(2.dp, palette.warmColor, shape) else Modifier)
                                .clickable(enabled = !future) { selected = day }
                                .semantics { contentDescription = day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ITALIAN)) + if (p > 0) ", $p pagine" else ", nessuna lettura" },
                                contentAlignment = Alignment.Center) {
                                Text("${day.dayOfMonth}", style = MaterialTheme.typography.bodySmall, fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                                    color = when { future -> colors.outline; l >= 3 -> colors.onPrimary; else -> colors.onSurface })
                            }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("meno ", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            levels.forEach { c -> Box(Modifier.padding(horizontal = 2.dp).size(12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(c)) }
            Text(" più", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        // The chosen day, inline under the calendar.
        val list = data.sessions[selected].orEmpty()
        HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 12.dp), color = colors.outlineVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text((if (selected == today) "Oggi, " else "") + selected.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ITALIAN)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontFamily = Serif))
            Text(if (list.isEmpty()) "Nessuna lettura" else "${list.sumOf { it.pages }} pagine · ${hours(list.sumOf { it.minutes })}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        list.forEach { s -> Column(Modifier.padding(top = 8.dp)) { Text(s.title, style = MaterialTheme.typography.titleSmall); Text("${s.pages} pagine · ${s.minutes} min", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) } }
    }
}

@Composable private fun LibraryCard(data: StatsData, palette: ThemePalette) {
    val colors = MaterialTheme.colorScheme
    val parts = listOf(Triple("In lettura", Book.READING, colors.primary), Triple("In pausa", Book.PAUSED, palette.warmColor),
        Triple("Da leggere", Book.TO_READ, colors.primary.copy(alpha = .3f)), Triple("Letti", Book.READ, colors.onSurface))
        .map { (l, s, c) -> Triple(l, data.byState.firstOrNull { it.first == s }?.second ?: 0, c) }
    Card {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("La tua biblioteca", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(fontFamily = Serif))
            Text("${data.total} " + if (data.total == 1) "libro" else "libri", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().height(14.dp).padding(top = 0.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            parts.filter { it.second > 0 }.forEach { (_, n, c) -> Box(Modifier.weight(n.toFloat()).fillMaxHeight().clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(c)) }
        }
        Spacer(Modifier.height(14.dp))
        parts.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (l, n, c) ->
                    Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$l: $n" }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(c))
                        Text(l, Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("$n", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable private fun GenreCard(data: StatsData) {
    val colors = MaterialTheme.colorScheme
    var read by remember { mutableStateOf(false) }
    val chosen = remember(read) { if (read) data.books.filter { it.status == Book.READ } else data.books }
    val genres = remember(chosen) { genreCounts(chosen, membership = false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Generi", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(fontFamily = Serif))
            listOf(false to "Biblioteca", true to "Letti").forEach { (v, l) ->
                Box(Modifier.padding(start = 4.dp).height(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(if (read == v) colors.onSurface else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { read = v }.padding(horizontal = 12.dp).semantics { contentDescription = l + if (read == v) ", selezionato" else "" }, contentAlignment = Alignment.Center) {
                    Text(l, style = MaterialTheme.typography.labelLarge, color = if (read == v) colors.surface else colors.primary)
                }
            }
        }
        if (genres.isEmpty()) Text("Nessun libro letto ancora.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
        val max = genres.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        genres.take(8).forEach { (g, n) ->
            val muted = g == OTHER_GENRES || g == NO_GENRE
            Column(Modifier.padding(top = 12.dp).semantics(mergeDescendants = true) { contentDescription = "$g: $n libri" }) {
                Row { Text(g, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (muted) colors.onSurfaceVariant else colors.onSurface)
                    Text("$n · ${if (chosen.isEmpty()) 0 else n * 100 / chosen.size}%", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(colors.outlineVariant.copy(alpha = .5f))) {
                    Box(Modifier.fillMaxWidth(n.toFloat() / max).fillMaxHeight().clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(if (muted) colors.outline else colors.primary))
                }
            }
        }
        if (genres.any { it.first == NO_GENRE }) Text("Per i libri senza genere: Impostazioni › Libreria e catalogo › Indicizza i generi.",
            Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
