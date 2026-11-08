package app.atlante.library.ui.compose

import android.content.SharedPreferences
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingGoals
import app.atlante.library.ui.ThemePalette
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object GoalsView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, prefs: SharedPreferences, books: List<Book>): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) { GoalsSection(ReadingGoals(prefs), books) } }
    }
}

private fun monthName(m: YearMonth) = m.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)

/** "Obiettivi di lettura" in the settings: daily, monthly and yearly goals, each with switch, value, reset and progress. */
@Composable
fun GoalsSection(goals: ReadingGoals, books: List<Book>) {
    var version by remember { mutableIntStateOf(0) }      // recomposes after every change
    var expanded by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    key(version) {
        // Today's goal in a big ring; a tap opens the goals of the day, the month and the year.
        GoalsSummary(goals, books, today, expanded) { expanded = !expanded }
        AnimatedVisibility(expanded) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GoalCard("Oggi") {
                GoalRow("Minuti di lettura al giorno", goals.dailyMinutesOn, { goals.dailyMinutesOn = it; version++ }, goals.dailyMinutes, 1..1440, 5, "min",
                    { goals.dailyMinutes = it; version++ }, { goals.resetDailyMinutes(); version++ }, goals.minutesToday(books, today), "Tempo letto oggi")
                HorizontalDivider()
                GoalRow("Pagine al giorno", goals.dailyPagesOn, { goals.dailyPagesOn = it; version++ }, goals.dailyPages, 1..2000, 5, "pagine",
                    { goals.dailyPages = it; version++ }, { goals.resetDailyPages(); version++ }, goals.pagesToday(books, today), "Pagine lette oggi")
            }
            val month = YearMonth.now()
            GoalCard("Obiettivo ${monthName(month)}") {
                GoalRow("Libri da completare nel mese", goals.monthOn, { goals.monthOn = it; version++ }, goals.monthBooks, 1..100, 1, "libri",
                    { goals.monthBooks = it; version++ }, { goals.resetMonth(); version++ }, goals.month(books, month), "Libri completati")
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = !open }) { Text(if (open) "Nascondi i mesi precedenti" else "Mesi precedenti") }
                AnimatedVisibility(open) {
                    Column {
                        goals.history(books).forEach { (m, p) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text("${monthName(m).replaceFirstChar { it.uppercase() }} ${m.year}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text((if(p.goal>0) "${p.done} / ${p.goal}" else "${p.done} completati · nessun obiettivo registrato") + if (p.reached) "  ✓" else "", style = MaterialTheme.typography.bodyMedium,
                                    color = if (p.reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            GoalCard("Obiettivo ${today.year}") {
                val p = goals.year(books, today)
                GoalRow("Libri da completare nell’anno", goals.yearOn, { goals.yearOn = it; version++ }, goals.yearBooks, 1..1000, 1, "libri",
                    { goals.yearBooks = it; version++ }, { goals.resetYear(); version++ }, p, "Libri completati")
                if (goals.yearOn) {
                    val left = ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31)).toInt()
                    val missing = (goals.yearBooks - p.done).coerceAtLeast(0)
                    Text(if (missing == 0L) "Obiettivo raggiunto: tutto quello che leggi ora è in più."
                        else "Mancano $left giorni alla fine dell’anno e $missing ${if (missing == 1L) "libro" else "libri"} all’obiettivo.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("I progressi usano solo le sessioni registrate e le date in cui hai finito i libri; una rilettura conta una volta per data.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
    }
}

/** The card on top: today's goal as a big ring with the numbers inside, month and year as two small rings. */
@Composable
private fun GoalsSummary(goals: ReadingGoals, books: List<Book>, today: LocalDate, expanded: Boolean, onTap: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val daily = when {
        goals.dailyMinutesOn -> Triple(goals.minutesToday(books, today), "min", "Minuti di oggi")
        goals.dailyPagesOn -> Triple(goals.pagesToday(books, today), "pagine", "Pagine di oggi")
        else -> null
    }
    val month = if (goals.monthOn) goals.month(books, YearMonth.now()) else null
    val year = if (goals.yearOn) goals.year(books, today) else null
    val description = (daily?.let { (p, unit, label) -> "$label: ${p.done} di ${p.goal} $unit, ${p.percent}%" } ?: "Nessun obiettivo per oggi") +
        (month?.let { ". Mese: ${it.done} di ${it.goal} libri" } ?: "") + (year?.let { ". Anno: ${it.done} di ${it.goal} libri" } ?: "") +
        if (expanded) ". Tocca per chiudere le impostazioni" else ". Tocca per impostare gli obiettivi"
    ElevatedCard(onClick = onTap, modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Obiettivo di oggi", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { daily?.first?.fraction ?: 0f }, modifier = Modifier.fillMaxSize(), strokeWidth = 14.dp,
                    trackColor = scheme.surfaceVariant, color = if (daily?.first?.reached == true) scheme.tertiary else scheme.primary)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (daily != null) {
                        Text("${daily.first.done}", style = MaterialTheme.typography.displaySmall)
                        Text("di ${daily.first.goal} ${daily.second}", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        if (daily.first.reached) Text("raggiunto ✓", style = MaterialTheme.typography.labelLarge, color = scheme.tertiary)
                    } else Text("Imposta\nun obiettivo", style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                SmallRing(monthName(YearMonth.now()).replaceFirstChar { it.uppercase() }, month)
                SmallRing("${today.year}", year)
            }
            Spacer(Modifier.height(8.dp))
            Text(if (expanded) "Chiudi le impostazioni ▲" else "Imposta giorno, mese e anno ▼", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
    }
}

@Composable
private fun SmallRing(label: String, p: ReadingGoals.Progress?) {
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { p?.fraction ?: 0f }, modifier = Modifier.fillMaxSize(), strokeWidth = 6.dp, trackColor = scheme.surfaceVariant,
                color = if (p?.reached == true) scheme.tertiary else scheme.secondary)
            Text(p?.let { "${it.done}/${it.goal}" } ?: "–", style = MaterialTheme.typography.labelLarge)
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun GoalCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun GoalRow(
    label: String, on: Boolean, onToggle: (Boolean) -> Unit, value: Int, range: IntRange, step: Int, unit: String,
    onValue: (Int) -> Unit, onReset: () -> Unit, progress: ReadingGoals.Progress, doneLabel: String,
) {
    var editing by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(on, onToggle, Modifier.semantics { contentDescription = "$label: " + if (on) "attivo" else "disattivato" })
        }
        if (on) {
            GoalWheel(value, range, step, unit, onValue)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { editing = true }) { Text("Scrivi il numero") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("Ripristina") }
            }
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text("$doneLabel: ${progress.done} di ${progress.goal} $unit · ${progress.percent}%" + if (progress.reached) " · raggiunto" else "",
                style = MaterialTheme.typography.bodySmall, color = if (progress.reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (editing) {
        // The current number is selected and focused: typing replaces it.
        var input by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value.toString(), androidx.compose.ui.text.TextRange(0, value.toString().length))) }
        val text = input.text
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        val number = text.toIntOrNull()
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(label) },
            text = { OutlinedTextField(input, { v -> input = v.copy(text = v.text.filter(Char::isDigit).take(4)) }, Modifier.focusRequester(focus), label = { Text(unit) }, singleLine = true,
                isError = number == null || number !in range, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Da ${range.first} a ${range.last}") }) },
            confirmButton = { TextButton(enabled = number != null && number in range, onClick = { onValue(number!!); editing = false }) { Text("Salva") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Annulla") } })
    }
}

/** Horizontal wheel: swipe left/right, the value under the centre mark is the goal; items fade and shrink away from it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GoalWheel(value: Int, range: IntRange, step: Int, unit: String, onValue: (Int) -> Unit) {
    // Multiples of the step, the range start and the current value (so a goal like 7 with step 5 is kept, not rounded).
    val values = remember(range, step) { ((range.first..range.last step step).toList() + value).filter { it in range }.distinct().sorted() }
    fun indexOf(v: Int) = values.indexOfLast { it <= v }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = indexOf(value))
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val current by rememberUpdatedState(value)
    val report by rememberUpdatedState(onValue)
    // Report the centred value only when a swipe ends, never on first composition or on programmatic moves.
    LaunchedEffect(state, values) {
        var wasMoving = false
        snapshotFlow { state.isScrollInProgress }.collect { moving ->
            if (wasMoving && !moving) values.getOrNull(state.firstVisibleItemIndex)?.let { if (it != current) report(it) }
            wasMoving = moving
        }
    }
    // A change from outside (reset, typed number) moves the wheel.
    LaunchedEffect(value) { val i = indexOf(value); if (!state.isScrollInProgress && state.firstVisibleItemIndex != i) state.animateScrollToItem(i) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = "Rotella: $value $unit. Scorri a destra o a sinistra per cambiare" }) {
        val itemWidth = 72.dp
        val side = (maxWidth - itemWidth) / 2
        Box(Modifier.align(Alignment.Center).size(itemWidth, 56.dp), contentAlignment = Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.medium, color = scheme.primaryContainer, modifier = Modifier.fillMaxSize()) {}
        }
        LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), contentPadding = PaddingValues(horizontal = side),
            verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
            items(values.size) { i ->
                // Distance from the centre in item widths (0 = centred), read per frame to animate the size.
                val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
                val centre = state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportSize.width / 2f
                val dist = if (info == null) 3f else kotlin.math.abs(info.offset + info.size / 2f - centre) / info.size
                val near = (1f - dist / 3f).coerceIn(0f, 1f)
                Box(Modifier.width(itemWidth).height(56.dp).graphicsLayer { alpha = 0.35f + 0.65f * near; val sc = 0.75f + 0.35f * near; scaleX = sc; scaleY = sc }
                    .clickable { scope.launch { state.animateScrollToItem(i) } }, contentAlignment = Alignment.Center) {
                    Text("${values[i]}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                        color = if (dist < 0.5f) scheme.onPrimaryContainer else scheme.onSurface)
                }
            }
        }
    }
    Text(unit, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
}
