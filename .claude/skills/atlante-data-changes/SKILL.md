---
name: atlante-data-changes
description: Use when a change touches stored data in the Atlante Android app - the Book model or library.json fields, the Room bibliographic database (saga/db, migrations, schemas), the offline catalog (catalog/, scripts/catalog/build_catalog.py), merges and duplicate detection (BibliographicEngine, LibraryStore.merge), or anything that could lose personal reading data.
---

# Dati, database e identità delle opere

Riferimento: `docs/DATABASE.md`. Regola assoluta: nessun dato personale perso, nessuna migrazione distruttiva.

## Campi di `library.json` (`model/Book.java`)
- Aggiungere un campo: leggerlo con un valore di default (i libri salvati prima non lo hanno) e scriverlo in `json()`.
  I campi sconosciuti sono conservati da `retained`: non toglierlo.
- Dati personali nuovi → `journal`; dati bibliografici o di provenienza → `meta`. Mai cambiare `id`.
- Le scelte dell'utente non si sovrascrivono: `coverSource` gallery/online, `notSameAs`, `keepMine`, saghe manual/confirmed.

## Room (`saga/db/`, versione attuale in `AtlanteDatabase`)
1. Modifica l'entità, incrementa `version`.
2. `gradle -q :app:kspDebugKotlin` → esporta `app/schemas/.../<versione>.json`; copia da lì l'SQL esatto
   (`createSql`, indici) nella nuova `Migration` e aggiungila a `MIGRATIONS`.
3. Test in `SagaDatabaseTest`: database alla versione precedente **con dati**, `runMigrationsAndValidate`, dati ancora lì.
4. Mai `fallbackToDestructiveMigration` sul database bibliografico (il catalogo in sola lettura è l'unica eccezione).

## Catalogo offline
- Entità in `catalog/CatalogDatabase.kt`; se cambiano, esporta lo schema e rigenera con
  `python3 scripts/catalog/build_catalog.py` (usa la cache in `scripts/catalog/cache/`), poi `CatalogAssetTest`.
- Solo fonti ridistribuibili (Wikidata, Open Library). Il testo FTS si salva già normalizzato
  (`searchable()` nello script = `SearchIndex.normalize` nell'app): cambiarne uno significa cambiare l'altro.

## Stessa opera, doppioni, unioni
- Tutta la logica sta in `catalog/BibliographicEngine.kt`: aggiungi lì una nuova prova d'identità, non altrove.
  Mai unire per solo titolo; autori senza cognomi in comune = opere diverse; libri in ricerca possono avere `id` null.
- Le unioni passano solo da `LibraryStore.merge` / `addEdition` (un salvataggio, file spostati con rollback, storico).

## Verifica
`gradle :app:testDebugUnitTest --tests '*BibliographicRepairTest' --tests '*SagaDatabaseTest' --tests '*LibraryIndexTest' --tests '*SyncTest'`
più i test del campo o della tabella toccata. Per lo storage: `CatalogTest`, `BackupArchiveTest`.
