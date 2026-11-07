# AGENTS.md — istruzioni permanenti per agenti AI

Atlante Library per Android: libreria personale, compagno di lettura e catalogo bibliografico.
Kotlin + Java, Views e Jetpack Compose (Material 3), Room, WorkManager, Glance. Interfaccia in italiano.

## Regole di lavoro (obbligatorie)

1. **Orientati prima, leggi poco.** Parti dalla mappa qui sotto e da [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
   Leggi soltanto i file coinvolti dalla modifica; per i file grandi (soprattutto `MainActivity.java`, ~3.000 righe)
   cerca il metodo con `grep -n` e leggi solo quell'intervallo.
2. **Non ripetere analisi.** Se un fatto è già scritto in `docs/`, fidati del documento; rileggi il codice solo dove
   devi cambiarlo. Non rileggere un file appena modificato per controllarlo.
3. **Modifiche localizzate.** Modifica il metodo o il blocco interessato; non riscrivere file interi, non spostare
   codice senza motivo, non rinominare classi (il nome `MainActivity` identifica anche il file delle preferenze).
4. **Architettura modulare.** Nuova logica nel pacchetto del suo argomento (vedi tabella), non in `MainActivity`.
   Le regole bibliografiche (stessa opera, unioni, doppioni) stanno solo in `catalog/BibliographicEngine.kt`:
   non duplicarle altrove.
5. **Preserva funzionalità e dati.** Mai migrazioni Room distruttive; mai perdere dati personali (`library.json`:
   stato, pagine, `journal`, note, voti, file, copertine scelte). Le scelte dell'utente (`coverSource` gallery/online,
   saghe "manual"/"confirmed", "Tieni il mio", "Sono libri diversi") non vanno mai sovrascritte da processi automatici.
6. **Test pertinenti prima di concludere.** Esegui i test del modulo toccato (comandi sotto) e, prima di pubblicare,
   tutta la suite e `lintRelease`: la release CI si ferma su qualunque errore di lint (es. API sopra minSdk 26).
   Controlla l'esito vero (`BUILD SUCCESSFUL`, nessun `e:`), non solo l'assenza di righe filtrate.
7. **Aggiorna la documentazione** quando cambia il funzionamento: [docs/FEATURES.md](docs/FEATURES.md),
   [docs/DATABASE.md](docs/DATABASE.md) per schema e formati, [docs/CHANGELOG.md](docs/CHANGELOG.md) per ogni
   rilascio; il [README.md](README.md) è la guida per chi usa l'app.
8. **Contesto sintetico ma affidabile.** Riassumi ciò che hai verificato; non dichiarare un risultato (velocità,
   test, compatibilità) senza averlo misurato o eseguito.
9. **Niente dipendenze nuove** senza necessità reale; niente segreti nel repository (chiavi Hardcover, keystore:
   vedi [docs/ARCHITECTURE.md › Sicurezza](docs/ARCHITECTURE.md#sicurezza)).

## Mappa rapida: cosa leggere per…

| Modifica | File da aprire |
| --- | --- |
| Schermate a View, navigazione, dialoghi, impostazioni | `MainActivity.java` (cerca il metodo: `settingsPage`, `details`, `preview`, `syncPage`, `libraryHeader`…) |
| Schermata Compose | `ui/compose/<Schermata>.kt` + `AtlanteTheme.kt` |
| Modello del libro, stati, diario | `model/Book.java`, `model/ReadingJournal.java` |
| Ricerca e filtri della biblioteca | `model/SearchIndex.kt`, `model/LibraryQuery.java` |
| Stessa opera, doppioni, unioni | `catalog/BibliographicEngine.kt`, `storage/LibraryStore.java` (`merge`, `addEdition`) |
| Catalogo offline (10.000 opere) | `catalog/CatalogDatabase.kt`, `scripts/catalog/build_catalog.py` |
| Indice FTS della biblioteca, ricerca unificata | `catalog/LibraryIndex.kt`, `catalog/UnifiedSearch.kt` |
| Riparazioni in background | `catalog/RepairWorker.kt`, `ui/compose/RepairPanel.kt` |
| Saghe, mappe, universi | `saga/SagaRecognizer.kt`, `saga/SagaRepository.kt`, `ui/compose/SagaLayout.kt`, `SagaMapScreen.kt` |
| Hardcover (sync, saghe, copertine) | `sync/HardcoverSync.java`, `sync/HardcoverSyncWorker.kt`, `sync/HardcoverAuth.kt`, `saga/Hardcover.kt` |
| Metadati online, copertine | `online/MetadataEngine.java`, `online/MetadataSources.java`, `online/CoverSearch.kt`, `online/PlotFinder.kt` |
| Scopri | `saga/Discover.kt`, `ui/compose/DiscoverScreen.kt` |
| Widget | `widget/*.kt` |
| Database Room | `saga/db/*.kt` + [docs/DATABASE.md](docs/DATABASE.md) |

Percorsi relativi a `app/src/main/java/app/atlante/library/`. Dipendenze tra moduli:
[docs/ARCHITECTURE.md › Dipendenze](docs/ARCHITECTURE.md#dipendenze-tra-i-moduli).

## Comandi

```bash
gradle -q :app:compileDebugKotlin :app:compileDebugJavaWithJavac      # compila
gradle :app:testDebugUnitTest --tests '*SyncTest'                     # test di un modulo
gradle -q :app:testDebugUnitTest                                      # tutta la suite (JVM + Robolectric)
gradle -q :app:lintRelease                                            # lo stesso controllo della release
bash build.sh release                                                 # test + lint + APK (come la CI)
```

Test live (rete, opzionali): `HARDCOVER_TOKEN=… gradle :app:testDebugUnitTest --tests '*HardcoverLiveTest'`;
la chiave sta in `~/.config/atlante-android/hardcover-token`, mai nel repository.
Verifica sull'emulatore: workflow `Android UI preview` (`scripts/capture-*.py`).

## Rilascio

Ogni push su `main` che tocca l'app genera una release firmata `1.0.<run>` (workflow `release.yml`), che l'utente
installa con Obtainium. Dopo una release: `bash scripts/sync-crafting.sh` e aggiornare le novità in
`../android-release/LEGGIMI.txt`.

## Lavoro in sospeso

I punti ancora da fare, con file e verifiche: [docs/da-fare/README.md](docs/da-fare/README.md).

## Altri documenti

[ARCHITECTURE](docs/ARCHITECTURE.md) · [FEATURES](docs/FEATURES.md) · [DATABASE](docs/DATABASE.md) ·
[UI_DESIGN](docs/UI_DESIGN.md) · [CHANGELOG](docs/CHANGELOG.md) · storici: [PIANO-E-VERIFICA](PIANO-E-VERIFICA.md),
[REDESIGN-MIGRATION](REDESIGN-MIGRATION.md), [AGGIORNAMENTO-IMPOSTAZIONI-IMPORTAZIONE](docs/AGGIORNAMENTO-IMPOSTAZIONI-IMPORTAZIONE.md).
