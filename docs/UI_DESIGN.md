# Interfaccia

Material Design 3, interfaccia **solo in italiano**. Due tecnologie convivono: le schermate storiche sono View
costruite in codice dentro `MainActivity` (helper `text`, `button`, `surface`, `column`, `row`, `gap`, `expander`,
`dialogBuilder`, `Picker`); le schermate nuove sono Compose (`ui/compose/`), incorporate con `ComposeView`
(`XxxView.create(activity, palette, …)` o `composePage(view)`).

## Navigazione

Barra in basso: **Home, Biblioteca, Scopri, Statistiche, Sync** (`MainActivity.addBottomNav`); menu laterale ☰ e
**Impostazioni** dall'icona in alto. Biblioteca ha schede (Tutti, Da leggere, In lettura, In pausa, Letti, Preferiti,
Mappa, Autori, Saghe, Universi, scaffali). La scheda del libro (`details`) è una pagina intera; i dialoghi pieno
schermo Compose (`Dialog` con `usePlatformDefaultWidth = false`) servono per copertine, saga completa, operazioni.
Sopra la barra in basso, quando c'è un lavoro in background, compare la barra di avanzamento (`RepairViews.bar`).

## Tema

- Palette in `ui/ThemePalette.java` (Atlante crema/petrolio/rame, Rosa petalo, Lavanda, Salvia, Blu polvere, Sabbia,
  Grafite, Notte viola, AMOLED nero) e temi personali a tre colori (`ui/compose/ThemeEditor.kt`); stili XML in
  `res/values/palettes.xml` e `styles.xml` (`Atlante.Base`, `Atlante.Button.*`, `Atlante.Field*`).
- Lato View: colori nelle variabili `PAPER`, `WHITE`, `INK`, `MUTED`, `SAGE`, `COPPER`, `LINE` di `MainActivity`.
- Lato Compose: `AtlanteTheme(palette)` traduce la palette in `MaterialTheme.colorScheme`; usare sempre i colori
  dello schema (`primary`, `onSurfaceVariant`…), mai colori fissi.
- Titoli in serif, copertine vere (o copertina di testo con il titolo in serif, `BookCover`).

## Convenzioni

- **Testi**: italiano naturale, frasi brevi; spiegazioni secondarie in `bodySmall` con `onSurfaceVariant`.
- **Azione principale vicina al contenuto** (es. «Aggiungi alla mia libreria» sotto la copertina), non solo nella
  barra del dialogo; spazi fissi per elementi caricati dopo, così niente si sposta.
- **Operazioni lunghe** sempre in background (WorkManager) con notifica e barra, mai finestre bloccanti.
- **Accessibilità**: `contentDescription` / `semantics(mergeDescendants = true)` su copertine, anelli, carte della
  mappa, pulsanti senza testo; bersagli di almeno 48 dp; TalkBack legge i valori (es. «23 di 30 minuti»).
- **Liste**: `LazyColumn`/`LazyRow`/`LazyVerticalGrid` con chiavi stabili; immagini da `CoverImages` (cache unica in
  memoria, decodifica alla dimensione mostrata, stessa copertina caricata una volta).
- **Proposte e conflitti**: mostrare entrambe le versioni e la provenienza; pulsanti espliciti (Unisci / Non unire /
  Dopo, Usa quello del catalogo / Tieni il mio); le risposte negative vengono ricordate.

## Verifica visiva

Workflow `Android UI preview` su emulatore API 35 (Pixel 2): `scripts/capture-update.py`, `capture-widgets.py`,
`capture-perf.py`, `capture-ui.py` toccano l'app per testo o `content-desc`, salvano schermate e albero UI come
artefatti. Se cambi un'etichetta usata dagli script, aggiorna anche lo script.
