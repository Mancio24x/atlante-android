# Funzioni

Solo ciò che è implementato. Per ognuna: dove sta e quale test la copre. La guida per chi usa l'app è il
[README](../README.md).

| Funzione | Codice | Test |
| --- | --- | --- |
| Biblioteca: griglia, elenco, scaffale; stati di lettura; scheda del libro | `MainActivity` (`showLibrary`, `details`), `ui/compose/ShelfView.kt` | `LibraryQueryTest`, `ShelfTest`, `StatusChangeTest` |
| Ricerca per parole (accenti/apostrofi ignorati, ranking, titoli originali) | `model/SearchIndex.kt` | `SearchIndexTest` (anche 10.000 libri) |
| Filtri: [Genere] [Autore] accanto alla barra, sottogenere, saga/sotto-serie, universo, lingua, decennio, formato, «Con più edizioni»; un suggerimento | `model/LibraryQuery.java`, `MainActivity.libraryHeader/libraryFilters`, `saga/SearchAliases.kt` (`SagaFilters`) | `SearchIndexTest.filtersCombineOnNormalizedData` |
| Ricerca unificata con il catalogo offline | `catalog/UnifiedSearch.kt`, `ui/compose/CatalogStrip.kt` | `LibraryIndexTest` |
| Catalogo offline di 10.000 opere | `catalog/CatalogDatabase.kt`, `scripts/catalog/build_catalog.py` | `CatalogAssetTest` (anche velocità) |
| Indice FTS4 della biblioteca, incrementale | `catalog/LibraryIndex.kt` | `LibraryIndexTest` |
| Importazione EPUB/TXT/CBZ/AZW3 (anteprima, file collegato a scheda o come altra edizione) | `storage/LibraryStore.importBook/attachFile/addEdition`, `ebook/`, `MainActivity.preview` | `EbookImportTest`, `EbookParserTest`, `BibliographicRepairTest.epubOfAnotherEditionJoinsTheRecord` |
| Ricerca online (SBN, Open Library) e scheda diretta da Scopri/saghe/catalogo, trama in italiano | `MainActivity.onlineSearch/openDiscovered`, `online/SbnCatalog.java`, `online/OnlineCatalog.java`, `online/PlotFinder.kt` | `SbnCatalogTest`, `DiscoverTapTest` |
| Completa i dati (più fonti, scelta dei campi) | `online/MetadataEngine.java`, `online/MetadataSources.java` | `MetadataEngineTest` |
| Copertine: motore, «Cambia copertina» con edizioni Hardcover e preferenza | `online/CoverSearch.kt`, `ui/compose/CoverPicker.kt`, `saga/Hardcover.coverChoices` | `CoverSearchTest`, `CoversTest`, `CoverCacheTest`, `CoverLoadingTest` |
| Una sola opera = una sola scheda (identità, doppioni, unione con annullamento) | `catalog/BibliographicEngine.kt`, `storage/LibraryStore.merge`, `storage/MergeHistory.kt` | `BibliographicRepairTest`, `BookMatcherTest` |
| Riparazioni in background: doppioni (biblioteca, Hardcover), saghe, mappe, metadati, copertine; notifica, barra, «Operazioni in background», checkpoint | `catalog/RepairWorker.kt`, `ui/compose/RepairPanel.kt` | `RepairWorkerTest`, `RepairResumeTest` |
| Qualità del catalogo | `MainActivity.catalogQuality` | — |
| Saghe, sotto-serie, universi (Hardcover → catalogo → Wikidata/Open Library) | `saga/SagaRecognizer.kt`, `saga/SagaRepository.kt`, `saga/Hardcover.kt`, `saga/Wikidata.kt` | `SagaRecognizerTest`, `SagaRepositoryTest`, `HardcoverSagaTest` |
| Mappa verticale della saga (numeri verificati, rami, «Posizione da verificare», ordine personale, stesso universo) | `ui/compose/SagaLayout.kt`, `ui/compose/SagaMapScreen.kt`, `saga/Suggestions.kt` | `SagaLayoutTest`, `SuggestionsTest` |
| Ripara saghe e mappe (proposte sì/no ricordate) | `saga/SagaRepair.kt` | `SagaRepairTest`, `SuggestionsTest.mapCheckProposesAndRemembersNo` |
| Mappa delle letture per autore | `ui/ReadingMap.java`, `ui/MapGroups.java` | `MapGroupsTest` |
| Scopri (Hardcover o Open Library per genere, pagina successiva precaricata) | `saga/Discover.kt`, `ui/compose/DiscoverScreen.kt` | `DiscoverTest`, `DiscoverSpeedLiveTest` (live) |
| Generi normalizzati e grafico delle statistiche | `saga/Genres.kt`, `ui/compose/StatisticsScreen.kt` | `GenreGroupsTest` |
| Compagno di lettura: timer, sessioni, rotelle di avanzamento, note, recensioni, riletture | `MainActivity`, `model/ReadingJournal.java`, `ui/compose/ProgressWheels.kt` | `ReadingJournalTest`, `SettingsTimerTest`, `GoalsWheelsImportTest` |
| Obiettivi giorno/mese/anno (anello grande in Impostazioni) | `model/ReadingGoals.kt`, `ui/compose/GoalsSection.kt` | `GoalsWheelsImportTest` |
| Temi predefiniti e personali | `ui/ThemePalette.java`, `ui/compose/ThemeEditor.kt`, `ui/compose/AtlanteTheme.kt` | `ThemePaletteTest`, `ThemeEditorTest` |
| Sync Hardcover (in background, senza doppioni, conflitti da scegliere, OAuth o chiave) | `sync/HardcoverSync.java`, `sync/HardcoverSyncWorker.kt`, `sync/HardcoverAuth.kt` | `SyncTest`, `HardcoverLiveTest` (live) |
| Sync Calibre, KOReader, evidenziazioni Kindle | `sync/CalibreSync.java`, `sync/KoreaderSync.java`, `sync/KindleClippings.java` | `SyncTest`, `CalibreLiveTest` (live) |
| Backup ZIP e importazione di cataloghi | `storage/BackupArchive.java`, `storage/CatalogImport.java` | `BackupArchiveTest`, `CatalogImportTest`, `CatalogTest` |
| Widget Home (2×2, 4×2, 4×4) con +pagine/+% e impostazioni per widget | `widget/` | `QuickProgressTest`; emulatore: `scripts/capture-widgets.py` |
| Cache e dati temporanei con limiti | `saga/Cache.kt`, `ui/compose/CacheSettingsSection.kt` | `CoverCacheTest` |

Limiti noti (non ancora implementati o dipendenti da terzi): OAuth Hardcover attivo solo con un `client_id`
registrato; ordini «cronologico» e «consigliato» delle saghe non disponibili nelle fonti; il catalogo copre meno
i libri di genere recenti (Wikidata).

## CrossPoint (lettore e-ink)

Pagina «CrossPoint» (menu laterale, o Sync › Importa ed esporta). Il lettore apre la schermata di trasferimento file e crea la
sua rete Wi-Fi aperta «CrossPoint-Reader» (senza internet), indirizzo 192.168.4.1: «Collegati al Wi-Fi del lettore»
la chiede ad Android (10+, `WifiNetworkSpecifier`; prima apre le impostazioni Wi-Fi). Tutti i socket sono legati alla
rete Wi-Fi (`CrossPointWifi`), altrimenti Android userebbe i dati mobili. Funziona anche con il lettore sul Wi-Fi di
casa (ricerca o indirizzo IP). API del firmware: `GET /api/status`, `GET /api/files?path=`,
`GET /download?path=`, `POST /upload?path=` (multipart), `POST /mkdir`, `/rename`, `/move`, `/delete`; scoperta con
«hello» in UDP sulla porta 8134 (risposta `crosspoint (on <nome>);81`). Codice: `crosspoint/CrossPointClient.kt`,
`ui/compose/CrossPointScreen.kt`, `MainActivity.crossPointView`. Indirizzo ricordato in `crosspointHost`.
