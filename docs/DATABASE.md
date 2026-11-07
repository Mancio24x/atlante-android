# Dati e database

Tre archivi distinti. Solo il primo contiene dati personali.

## 1. Biblioteca personale — `files/library.json`

Scritto da `storage/LibraryStore.java` con `AtomicFile` (salvataggio intero, con ripristino in caso di errore).
Ogni salvataggio incrementa `LibraryStore.version()`, aggiorna i widget e l'indice FTS.
Il modello è `model/Book.java`; i campi sconosciuti sono conservati (`retained`).

- **Campi del libro**: `id` (64 hex; non va mai cambiato), `title`, `author`, `subtitle`, `series`, `number`, `genre`,
  `description`, `edition`, `isbn`, `publisher`, `language`, `year`, `coverId`, `source`, `notes`, `tags`,
  `location`, `medium`, `format` (`EPUB|TXT|CBZ|AZW3|CATALOGO`), `status` (Da leggere, In lettura, In pausa, Letto),
  `currentPage`, `totalPages`, `page`, `percent`, `started`, `finished`, `rating`, `addedAt`, `chapters`.
- **`journal`** (dati personali additivi, vedi `model/ReadingJournal.java`): `sessions` (data, `at`, pagine, secondi,
  `timerId`, `attempt`), `attempts` (letture completate precedenti), `attempt`, `rating` (mezze stelle), `review`,
  `notes`, `favorite`, `collections`, `chapter`, `sync` (collegamenti per servizio, es. `sync.hardcover` = `id`,
  `book`, `synced`, `missing`, `checked`).
- **`meta`** (bibliografico e provenienza): `ids` (isbn, asin, wikidata, openlibrary…), `sources`, `manual`,
  `originalTitle`, `translators`, `firstPublished`, `subjects`, `fileSha256`, `coverSource` (`gallery` e `online` =
  scelta dell'utente; `auto` = trovata dall'app), `coverOrigin`, `editions` (altre edizioni della stessa opera,
  anche con `file`), `notSameAs` ("Sono libri diversi"), `keepMine` (campi da non riproporre), `descriptionSource`.
- **File su disco**: `files/books/<id>/book.<formato>`, `cover`, `covers/alt-*` (copertine alternative),
  `editions/<id>.<formato>` (file di un'altra edizione).
- **Unioni**: `LibraryStore.merge` (un solo salvataggio); la scheda rimossa va in `files/merged/<id>`,
  lo storico in `files/merge-history.json` (ultime 100, usato da "Annulla l'ultima unione"); le preferenze `merges`
  reindirizzano l'id rimosso a quello rimasto (widget).
- **Backup**: ZIP di `BackupArchive` (catalogo + file); `CatalogImport` importa anche cataloghi di altre app.

## 2. Database bibliografico — `atlante-bibliography.db` (Room, `saga/db/`)

`AtlanteDatabase`, **versione 2**. Gli schemi sono esportati in `app/schemas/app.atlante.library.saga.db.AtlanteDatabase/`
e verificati da `SagaDatabaseTest` (anche con dati, 1→2). Le migrazioni non sono mai distruttive.

| Tabella | Contenuto |
| --- | --- |
| `works`, `authors`, `work_authors`, `editions` | Opere (titolo originale, italiano, alternativi, id Wikidata/Open Library), autori, edizioni |
| `series`, `series_entries`, `universes` | Saghe e sotto-serie (`parentId`), volumi con `ordinal`/`sortKey`/`membership`/`source`, universi narrativi |
| `work_relations` | Relazioni tra opere (precede/segue, prequel, spin-off…) |
| `genres`, `work_genres` | Generi |
| `metadata_sources` | Provenienza dei dati |
| `user_overrides` | Scelte dell'utente (es. `series/<id>/order` = ordine personale; `refused:<bookId>` = numero rifiutato) |
| `book_links` | Libro della biblioteca → opera e saga, con `status` (`auto`, `to_confirm`, `confirmed`, `manual`, `no_saga`, `unidentified`, `none`) |
| `cache_entries` | Risposte online temporanee con scadenza |
| `library_fts` | **FTS4** della biblioteca: titoli (anche del catalogo), autori, saghe, `hash` del testo, `catalogWork` |
| `repair_jobs` | Lavori in background: tipo, stato, fase, totale, analizzati, trovati, doppioni, proposte, applicati, errori, `checkpoint`, `result` (JSON delle proposte) |

Migrazione **1→2** (`AtlanteDatabase.MIGRATION_1_2`): crea solo `library_fts` e `repair_jobs`; i dati esistenti
restano. Per una nuova versione: aggiungi l'entità, incrementa `version`, esegui la build per esportare lo schema,
scrivi la `Migration` con l'SQL esportato e aggiungi un test in `SagaDatabaseTest`.

## 3. Catalogo offline — `assets/catalog/atlante-catalog.db` (Room, `catalog/`)

`CatalogDatabase`, versione 1, **in sola lettura**, copiato dall'APK al primo uso (`createFromAsset`). Contiene
10.000 opere reali da Wikidata e Open Library (CC0); gli id sono i numeri Wikidata (Q12345 → 12345), gli stessi usati
dal grafo delle saghe.

| Tabella | Contenuto |
| --- | --- |
| `catalog_works` | Titolo (italiano se c'è), originale, italiano, lingua, anno, autori, genere normalizzato, generi grezzi, id Open Library, URL copertina, popolarità |
| `catalog_authors`, `catalog_work_authors` | Autori |
| `catalog_series`, `catalog_entries` | Saghe (con `parentId`) e volumi con numero (P1545) |
| `catalog_relations` | `follows` / `followed_by` |
| `catalog_editions` | Edizioni italiane e inglesi con ISBN-13 |
| `catalog_fts` | **FTS4** (rowid = id opera): titoli, autori, saghe, già normalizzati (minuscole, senza accenti né punteggiatura) |

Rigenerazione: `python3 scripts/catalog/build_catalog.py` (risposte in `scripts/catalog/cache/`, non versionata).
Lo script crea le tabelle con l'SQL di `app/schemas/app.atlante.library.catalog.CatalogDatabase/1.json` e scrive
`room_master_table`: se cambi le entità, esporta lo schema prima di rigenerare. `CatalogAssetTest` lo verifica.

## Altri dati locali

- **Preferenze** `MainActivity` (tema, filtri, Hardcover: `hardcoverToken`/token OAuth, `hardcoverConflicts`,
  `coverPrefer`…), `atlante-cache` (limiti della cache), `merges`; stato dei widget nelle preferenze di Glance.
- **Cache** (cancellabili da Impostazioni › Cache): `cache/suggestion-covers` (copertine non della biblioteca,
  con limite di spazio), cartella di `OnlineCatalog` (ultime 300 risposte online), `cache_entries`.
