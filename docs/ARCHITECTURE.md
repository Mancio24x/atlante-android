# Architettura

App Android a singola Activity. Codice in `app/src/main/java/app/atlante/library/` (Kotlin e Java insieme),
test in `app/src/test/java/app/atlante/library/` (JUnit, Robolectric, Room in memoria, WorkManager testing).

- **minSdk 26** (Android 8), **target/compile 35**, Java 17 per la build, `jvmTarget 1.8`.
- Varianti: `release` (`app.atlante.library`) e `debug` (`app.atlante.library.debug`, con `WidgetHostActivity` in `src/debug/`).
- Librerie principali: Compose BOM 2024.10 + Material 3, Room 2.6.1 (KSP, schemi esportati in `app/schemas/`),
  WorkManager 2.9.1, Glance 1.1.1, Material Components 1.12, coroutines 1.8.

## Pacchetti

| Pacchetto | Responsabilità | File principali |
| --- | --- | --- |
| *(radice)* | Activity unica: navigazione (Home, Biblioteca, Scopri, Statistiche, Sync, Impostazioni), schermate a View, dialoghi, ponte verso Compose | `MainActivity.java` |
| `model/` | Dati del libro e regole pure | `Book`, `ReadingJournal`, `BookMatcher` (stesso libro per file/ISBN/titolo+autore), `LibraryQuery` (filtri), `SearchIndex` (ricerca per parole e ranking), `ReadingGoals` |
| `storage/` | Persistenza della biblioteca personale | `LibraryStore` (`library.json` con AtomicFile, file e copertine, `merge`/`addEdition`/`undoMerge`), `SyncStore`, `BackupArchive`, `CatalogImport`, `MergeHistory` |
| `catalog/` | Catalogo offline, indice della biblioteca, identità delle opere, riparazioni | `CatalogDatabase` (asset in sola lettura), `LibraryIndex` (FTS4), `UnifiedSearch`, `BibliographicEngine`, `RepairWorker` |
| `saga/` | Grafo bibliografico (opere, saghe, universi), riconoscimento, Scopri, suggerimenti | `SagaRecognizer`, `SagaRepository`, `SagaRepair`, `Hardcover`, `Wikidata`, `OpenLibraryWorks`, `Discover`, `Suggestions`, `Genres`, `SearchAliases`, worker `SagaScanWorker`/`SagaRefreshWorker`, `db/` (Room) |
| `online/` | Metadati e copertine da fonti esterne | `OnlineCatalog` (HTTP, cache, limiti per host), `SbnCatalog`, `MetadataEngine` + `MetadataSources`, `CoverSearch`, `PlotFinder`, `SeriesCatalog` |
| `sync/` | Servizi esterni | `HardcoverSync` + `HardcoverSyncWorker` + `HardcoverAuth` (OAuth PKCE / chiave), `CalibreSync`, `KoreaderSync`, `KindleClippings`, `SyncCore` (regole push/pull, HTTP) |
| `ebook/` | Lettura file | `EbookParser` (EPUB, TXT, CBZ), `Azw3` |
| `ui/` | Componenti a View e tema | `Covers` (cache bitmap), `BookAdapter`, `ReadingMap` + `MapGroups` (mappa delle letture), `ThemePalette`, `ThemePreferences` |
| `ui/compose/` | Schermate Compose incorporate con `ComposeView` | `AtlanteTheme`, `DiscoverScreen`, `StatisticsScreen`, `BookSagaSection`, `SagaMapScreen` + `SagaLayout`, `CoverPicker`, `RepairPanel`, `CatalogStrip`, `GoalsSection`, `ShelfView`, `ThemeEditor`, `ProgressWheels` |
| `widget/` | Widget Home (Glance) | `ReadingWidgets`, `Widgets`, `QuickProgress`, `WidgetConfigActivity` |

## Dipendenze tra i moduli

Ricavate dai riferimenti nel codice (`app.atlante.library.<pacchetto>`); i pacchetti non sono a strati rigidi.

| Pacchetto | Usa |
| --- | --- |
| `MainActivity` | tutti |
| `ebook` | `model` |
| `model` | `ebook`, `online`, `saga` (es. `Genres` per i filtri, `SeriesCatalog`) |
| `widget` | `model`, `storage`, `ui` |
| `ui` (+ `ui/compose`) | `catalog`, `model`, `online`, `saga` |
| `storage` | `catalog` (`BibliographicEngine`, `LibraryIndex` dopo ogni salvataggio), `ebook`, `model`, `online`, `ui`, `widget` (aggiornamento widget) |
| `online` | `catalog`, `ebook`, `model`, `saga` (`Hardcover`), `storage`, `sync`, `ui` |
| `saga` | `catalog`, `model`, `online`, `storage`, `sync`, `ui` |
| `sync` | `catalog`, `ebook`, `model`, `online`, `saga`, `storage` |
| `catalog` | `model`, `online`, `saga` (`db`), `storage`, `sync`, `ui`, `widget` |

Punti di accoppiamento da conoscere prima di modificare:
- **Ogni salvataggio** di `LibraryStore.save()` avvisa i widget (`Widgets.libraryChanged`) e l'indice FTS
  (`LibraryIndex.libraryChanged`, con attesa di 1,5 s).
- **Identità delle opere**: `BibliographicEngine` è usato da `HardcoverSync` (prima di creare schede),
  dall'anteprima di importazione in `MainActivity`, da `LibraryStore.addEdition` e da `RepairWorker`.
- **Fonti per saghe e metadati**: `Hardcover` (se collegato) → catalogo offline → Wikidata / Open Library;
  `SagaRepository.configureCatalog` e `HardcoverSync.catalog` ricevono il catalogo all'avvio e nei worker.
- **Preferenze** nel file `MainActivity` (`getPreferences`): rinominare la classe le perderebbe.

## Flussi principali

- **Avvio**: `MainActivity.onCreate` → `LibraryStore.shared`, `Hardcover.configure`, `SagaRepository.configureCatalog`,
  worker delle saghe; `onResume` → `LibraryIndex.syncBlocking` (indice e titoli alternativi) + `SagaFilters.load`
  su un thread di lavoro.
- **Ricerca nella biblioteca**: barra → `LibraryQuery.select` (filtri) + `SearchIndex.rank` (in memoria, con titoli
  alternativi da grafo e catalogo); sotto, `CatalogStrip` → `UnifiedSearch` (FTS4 di biblioteca e catalogo).
- **Libro trovato online / Scopri**: `openDiscovered` → SBN + Open Library in background → `PlotFinder` → anteprima
  `preview`, che usa `BibliographicEngine.sameWorkIn` per non creare doppioni.
- **Lavori in background** (WorkManager, foreground `dataSync` con notifica): `HardcoverSyncWorker` (nome unico
  `hardcover-sync`), `RepairWorker` (nome unico `atlante-repair`, in coda: doppioni, saghe, mappe, metadati, copertine),
  `SagaScanWorker`, `SagaRefreshWorker`. Lo stato delle riparazioni è nella tabella `repair_jobs`
  (barra e schermata "Operazioni in background").
- **HTTP**: `OnlineCatalog.download` applica un intervallo per host (circa 1 richiesta/s per le API; immagini su CDN
  e copertine Open Library per id in parallelo); Hardcover ha un proprio limite (1,1 s) in `HardcoverSync.request`.

## Sicurezza

- Chiavi Hardcover dell'utente solo nelle preferenze private del telefono; il `client_id` OAuth (pubblico) è
  `HardcoverAuth.CLIENT_ID` (vuoto finché l'app non è registrata).
- Keystore di firma solo nei segreti GitHub (`APK_KEYSTORE_BASE64`, `APK_KEYSTORE_PASSWORD`); `signing.properties`
  locale non contiene segreti da pubblicare.
- Il catalogo incluso contiene solo dati CC0 (Wikidata, Open Library); i dati di Hardcover non vengono ridistribuiti.

## Build e verifica

- `build.sh release`: `testReleaseUnitTest`, `lintRelease`, `assembleRelease`, poi il test degli schemi Room in debug.
- Workflow: `verify.yml` (debug), `release.yml` (APK firmato `1.0.<run>` a ogni push su `main`),
  `ui-preview.yml` (emulatore API 35: `capture-update.py`, `capture-widgets.py`, `capture-perf.py`, `capture-ui.py`).
- Script: `scripts/catalog/build_catalog.py` (rigenera il catalogo), `sync-crafting.sh`, `package-source.py`,
  `create-signing.py`, `validate-cached.py`.
