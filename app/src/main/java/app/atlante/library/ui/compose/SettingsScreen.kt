package app.atlante.library.ui.compose

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlante.library.R
import app.atlante.library.ui.ThemePalette

/** One row of the settings index: group, key, title, current value, icon, search words, problems to show. */
class SettingsRow(@JvmField val group: String, @JvmField val key: String, @JvmField val title: String, @JvmField val value: String,
                  @JvmField val icon: Int, @JvmField val words: String, @JvmField val badge: Int)

/** What the Compose settings pages show and change (MainActivity keeps the preferences). */
class SettingsState(@JvmField val rows: List<SettingsRow>, @JvmField val readerDark: Boolean, @JvmField val fontSize: Float,
                    @JvmField val languages: List<String>, @JvmField val language: Int, @JvmField val onlyLanguage: Boolean,
                    @JvmField val appLanguage: Int = 0)

interface SettingsHost {
    fun open(key: String)
    fun setReaderDark(dark: Boolean); fun setFontSize(size: Float)
    fun setLanguage(index: Int); fun setOnlyLanguage(only: Boolean)
    /** 0 as the phone, 1 Italian, 2 English. */
    fun setAppLanguage(index: Int)
    fun openProject()
}

/**
 * Impostazioni (design 6a) in Compose: the index with search and the simple pages (Lettura, Lingua dei libri,
 * Privacy e progetto). The other pages keep their own components (themes, goals, catalog, Hardcover).
 * No scrolling here: the page sits in MainActivity's scroll view.
 */
object SettingsView {
    @JvmStatic
    fun create(activity: ComponentActivity, palette: ThemePalette, page: String, state: SettingsState, host: SettingsHost): View = ComposeView(activity).apply {
        setContent { AtlanteTheme(palette) {
            when (page) {
                "lettura" -> Reading(state, host)
                "lingua" -> Language(state, host)
                "info" -> Info(host)
                else -> Index(state, host)
            }
        } }
    }

    @Composable private fun Index(s: SettingsState, host: SettingsHost) {
        val colors = MaterialTheme.colorScheme
        var query by remember { mutableStateOf("") }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainer)
                .line(colors.outlineVariant).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                Box(Modifier.padding(start = 10.dp).weight(1f)) {
                    if (query.isEmpty()) Text("Cerca nelle impostazioni", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    BasicTextField(query, { query = it }, Modifier.fillMaxWidth().semantics { contentDescription = "Cerca nelle impostazioni" }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary))
                }
            }
            val q = query.trim().lowercase()
            val shown = s.rows.filter { q.isEmpty() || "${it.title} ${it.value} ${it.words}".lowercase().contains(q) }
            shown.groupBy { it.group }.forEach { (group, rows) ->
                Text(group, Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = colors.primary)
                Card {
                    rows.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = colors.outlineVariant)
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { host.open(r.key) }.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
                            .semantics(mergeDescendants = true) { contentDescription = r.title + ", " + r.value + if (r.badge > 0) ", ${r.badge} da sistemare" else "" },
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(lerp(colors.surfaceContainer, colors.primary, .12f)), contentAlignment = Alignment.Center) {
                                Icon(painterResource(r.icon), null, Modifier.size(22.dp), tint = colors.primary)
                            }
                            Column(Modifier.padding(start = 14.dp, end = 8.dp).weight(1f)) {
                                Text(r.title, style = MaterialTheme.typography.titleSmall)
                                Text(r.value, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (r.badge > 0) Box(Modifier.padding(end = 6.dp).heightIn(min = 22.dp).widthIn(min = 22.dp).clip(RoundedCornerShape(11.dp)).background(colors.tertiary).padding(horizontal = 7.dp),
                                contentAlignment = Alignment.Center) { Text("${r.badge}", style = MaterialTheme.typography.labelMedium, color = colors.onPrimary) }
                            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            if (shown.isEmpty()) Text("Nessuna impostazione per «$query».", Modifier.fillMaxWidth().padding(top = 40.dp), textAlign = TextAlign.Center, color = colors.onSurfaceVariant)
            Text(if (app.atlante.library.ui.L.english) "Atlante · English interface" else "Atlante · interfaccia in italiano", Modifier.fillMaxWidth().padding(top = 24.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }

    @Composable private fun Reading(s: SettingsState, host: SettingsHost) {
        val colors = MaterialTheme.colorScheme
        var dark by remember { mutableStateOf(s.readerDark) }
        var size by remember { mutableFloatStateOf(s.fontSize) }
        Column(Modifier.fillMaxWidth()) {
            // The reader's page as it will look: its own paper and ink, not the app's theme.
            Column(Modifier.fillMaxWidth().heightIn(min = 150.dp).clip(RoundedCornerShape(20.dp)).background(if (dark) Color(0xFF1B1D1E) else Color(0xFFFBF8F1))
                .line(colors.outlineVariant, 20).padding(20.dp)) {
                Text("ANTEPRIMA DEL LETTORE", style = MaterialTheme.typography.labelSmall, color = (if (dark) Color(0xFFE9E4D8) else Color(0xFF2A2723)).copy(alpha = .6f))
                Text("Il piacere di leggere, una pagina alla volta.", Modifier.padding(top = 10.dp), fontFamily = FontFamily.Serif, fontSize = size.sp, lineHeight = (size * 1.5f).sp,
                    color = if (dark) Color(0xFFE9E4D8) else Color(0xFF2A2723))
            }
            Spacer(Modifier.height(12.dp))
            Card {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(dark, role = Role.Switch) { dark = it; host.setReaderDark(it) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lettore scuro", style = MaterialTheme.typography.titleSmall)
                        Text("Solo dentro il libro, non nell’app", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Switch(dark, null)
                }
                HorizontalDivider(color = colors.outlineVariant)
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
                    Row { Text("Dimensione del testo", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text("${size.toInt()}", style = MaterialTheme.typography.titleSmall, color = colors.primary) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("A", fontFamily = FontFamily.Serif, fontSize = 13.sp, color = colors.onSurfaceVariant)
                        Slider(size, { size = Math.round(it).toFloat() }, Modifier.weight(1f).padding(horizontal = 10.dp).semantics { contentDescription = "Dimensione del testo del lettore" },
                            valueRange = 8f..80f, onValueChangeFinished = { host.setFontSize(size) })
                        Text("A", fontFamily = FontFamily.Serif, fontSize = 22.sp, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }

    @Composable private fun Language(s: SettingsState, host: SettingsHost) {
        val colors = MaterialTheme.colorScheme
        var chosen by remember { mutableIntStateOf(s.language) }
        var only by remember { mutableStateOf(s.onlyLanguage) }
        Column(Modifier.fillMaxWidth()) {
            Text("Lingua dell’app", Modifier.padding(start = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
            Card {
                listOf("Come il telefono", "Italiano", "English").forEachIndexed { i, name ->
                    if (i > 0) HorizontalDivider(color = colors.outlineVariant)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(i == s.appLanguage, role = Role.RadioButton) { if (i != s.appLanguage) host.setAppLanguage(i) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(i == s.appLanguage, null); Text(name, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Lingua dei libri", Modifier.padding(start = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
            Text("Lingua preferita per cercare libri online. Si salva da sola.", Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Card {
                s.languages.forEachIndexed { i, name ->
                    if (i > 0) HorizontalDivider(color = colors.outlineVariant)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(i == chosen, role = Role.RadioButton) { chosen = i; host.setLanguage(i) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(i == chosen, null); Text(name, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(only, role = Role.Switch) { only = it; host.setOnlyLanguage(it) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Cerca solo libri nella lingua scelta", style = MaterialTheme.typography.titleSmall)
                        Text("Nasconde le edizioni in altre lingue", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Switch(only, null)
                }
            }
        }
    }

    @Composable private fun Info(host: SettingsHost) {
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth()) {
            Card {
                Column(Modifier.padding(18.dp)) {
                    Text("I tuoi libri restano tuoi.", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif))
                    Text("Le ricerche online (SBN, Open Library, Wikidata, Apple Books e Hardcover se hai inserito la chiave) inviano solo titolo, autore o ISBN. Libri e note personali restano sul dispositivo.",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Card {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = host::openProject).padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Progetto e aggiornamenti, apre GitHub" }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Progetto e aggiornamenti", style = MaterialTheme.typography.titleSmall)
                        Text("github.com/Mancio24x/atlante-android", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Icon(painterResource(R.drawable.ic_open_in_new), null, Modifier.size(20.dp), tint = colors.primary)
                }
            }
        }
    }

    private fun Modifier.line(c: Color, radius: Int = 24) = this.border(BorderStroke(1.dp, c), RoundedCornerShape(radius.dp))

    @Composable private fun Card(content: @Composable ColumnScope.() -> Unit) =
        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), content = content)
}
