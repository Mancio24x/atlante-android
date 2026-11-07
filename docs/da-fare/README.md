# Da fare — punti rimasti (aggiornato al 7 novembre 2026)

Leggi prima `AGENTS.md`. Ogni punto dice lo **stato**, i **file** da aprire e come **verificare**. Ordine = priorità.
Riassume le specifiche date nella sessione del 7 novembre (stabilizzazione, Scopri, Biblioteca, generi, importazioni).

## Fatto in questa sessione (non rifare)

- Importazione CSV/ZIP: causa del crash trovata e corretta (conferma che confrontava ogni riga con ogni libro sul
  thread principale: 157 s → `catalog/ImportPlanner.kt` 76 ms); importazione in background come lavoro
  `RepairWorker` tipo `import` (notifica, barra, Interrompi, resoconto in «Operazioni in background»); idempotente;
  id con percorso (`..`) rifiutati. Test: `ImportWorkerTest`.
- Generi: tassonomia multi-genere (`saga/Genres.kt`: `all`, `of`, `mainOf`; nuove categorie Biografia, Filosofia,
  Religione, Storia, Scienza), indice in `meta.genres`, lavoro «Indicizza i generi» (`RepairWorker` tipo `genres`:
  catalogo → Hardcover → Dewey; mai soggetti liberi), Statistiche per appartenenza / genere principale e per stato.
  Test: `GenreIndexTest`.
- Prima ancora: catalogo offline 10.000 opere, indice FTS4, `BibliographicEngine`, riparazioni in background,
  mappa delle saghe, conflitti Hardcover, copertine, Scopri (crash), stati di lettura (vedi `docs/CHANGELOG.md`).

## 1. Biblioteca meno affollata (Priorità 4 della specifica di stabilizzazione)

**Stato: fatto** (design 3a: ricerca + «Filtri e ordine» + chip degli stati con il numero; «Filtri e ordine» è un foglio dal basso Material con i filtri attivi come chip rimovibili e «Azzera», `MainActivity.libraryMenu`). Resta: i filtri «Altri» ancora nel dialogo `libraryFilters`. Oggi in alto: riga [ricerca][Genere][Autore], suggerimento, striscia del catalogo, chip degli
stati + «Altri filtri e ordine» + «Azzera filtri» (`MainActivity.libraryHeader`, `libraryFilters`).
**Da fare:**
- Barra: ricerca + un solo pulsante «Filtri» (con contatore dei filtri attivi) + ordinamento.
- Chip rapidi solo: Tutti, Da leggere, In lettura, Letti (gli altri stati/scaffali nel foglio dei filtri).
- Foglio dal basso (BottomSheet Material 3, meglio in Compose: nuovo `ui/compose/LibraryFilters.kt`) con
  Genere (con sottogeneri), Autore (con ricerca), Saga/sotto-serie, Universo, Lingua, Anno, Formato/edizioni,
  Ordina per. Filtri attivi come chip rimovibili singolarmente nel foglio, più «Azzera».
- Mantenere Griglia/Elenco/Scaffale, il suggerimento singolo e la striscia «Dal catalogo offline».
**Attenzione:** gli script `scripts/capture-ui.py` toccano «Filtra per genere», «Filtra per autore», «Altri filtri»,
«Tutti gli autori»: aggiornarli. **Verifica:** `SearchIndexTest.filtersCombineOnNormalizedData`, workflow UI preview.

## 2. Scopri: scheda libro unica e trame sicure (Priorità 6 — la più importante rimasta)

**Stato: in gran parte fatto** (7 novembre, sera): `MainActivity.onlineSheet` è la scheda a tutta pagina per ogni libro non posseduto (Scopri, carousel e mappa delle saghe via `openWork`, catalogo, ricerca online via `openOnline`), si apre subito e si riempie dopo; risposte di un libro aperto prima ignorate (`sheetRequest`/`sheetCurrent`, `OnlineSheetTest`); trame solo da Wikipedia dell'opera o da record della stessa edizione (stesso ISBN), altrimenti «Trama non disponibile», HTML ed entità puliti (`PlotFinder`, `PlotFinderTest`). Restano: conferma dell'edizione con l'id dell'opera in `openDiscovered`, etichetta «I più letti», generi di Scopri allineati a `Genres`. Testo originale del punto: Oggi un libro non posseduto si apre con `MainActivity.openDiscovered` → `preview(...)`, che è un
**dialogo** (`AlertDialog`), mentre un libro posseduto si apre con `details(...)` (pagina intera). Sono due
componenti diversi: la specifica chiede una sola schermata a tutta pagina per tutti.
**Da fare:**
- Una scheda unica a tutta pagina per libri posseduti e non (o `details` esteso ai libri non posseduti, o un nuovo
  `ui/compose/BookDetailScreen.kt`), usata da: Scopri (`DiscoverScreen.PickCard`), ricerca online
  (`MainActivity.onlineSearch`, pulsante «Vedi la scheda completa»), carousel delle saghe e mappa
  (`BookSagaSection`, `SagaMapScreen` → `host.openWork`), catalogo (`CatalogStrip`), autori. Pulsante Indietro coerente.
- Trame (`online/PlotFinder.kt`): oggi usa Wikipedia it via id Wikidata del catalogo (sicuro) e poi
  `MetadataEngine.lookup` per titolo+autore (può dare la trama di un'altra edizione o opera). Va legata
  all'identificativo dell'opera: accettare una descrizione solo se la fonte è la stessa opera (id OL work /
  Hardcover / Wikidata verificati), altrimenti «Trama non disponibile». Pulire HTML ed entità; testo espandibile.
- `openDiscovered` sceglie l'edizione con `BookMatcher.score` tra SBN + Open Library: confermare con l'id
  dell'opera quando c'è (il Pick di Hardcover ha `workKey` `hc:<id>`; quello di Open Library `OL…W`).
- Risposte tardive: la scheda deve ignorare una risposta di un libro diverso da quello aperto (id stabile).
- Se l'opera è già in Biblioteca: aprire la scheda esistente (già fatto con `BibliographicEngine.sameWorkIn`).
- «I più letti»: etichetta corretta (popolarità di Hardcover/Open Library, non vendite); i link alle classifiche
  IBS/laFeltrinelli sono esterni e già etichettati.
- Generi di Scopri (`saga/Discover.kt` `GENRES`) allineati alla tassonomia `Genres` (stessi nomi).
**Verifica:** test nuovi in `DiscoverTest`/`DiscoverTapTest` (trama di un'altra opera mai mostrata, risposta fuori
ordine), workflow UI preview.

## 3. Revisione tecnica (Priorità 5)

**Stato: da fare** (alcune parti già coperte da test). Punti concreti noti:
- Scorrimento della Biblioteca: sull'emulatore CI 66% di frame lenti (p50 46 ms) — emulatore senza GPU, ma da
  misurare su telefono vero (`dumpsys gfxinfo`) e ottimizzare `BookAdapter` / decodifica copertine (`ui/Covers.java`).
- `MainActivity.java` ~3.100 righe: spostare in Compose una schermata alla volta (Impostazioni, Sync), senza
  cambiare comportamento.
- Transizioni degli stati: fatto, tutte le 16 combinazioni in `StatusMatrixTest` (pagina, percentuale, date).
- Statistiche: verificare riletture e distribuzioni temporali senza doppi conteggi dopo le unioni.

## 4. Test e verifica sull'emulatore (Priorità 7)

- Il workflow `Android UI preview` si fermava nel passaggio di «Ripara saghe» (navigazione dalle Impostazioni):
  corretto in `capture-ui.py` (aggiunto `back()` prima di «Biblioteca»); **rilanciare e controllare** che arrivi fino
  in fondo: `gh workflow run "Android UI preview" --ref main`, poi `gh run view <id> --log`.
- Aggiungere allo script: importazione di un CSV d'esempio dal menu Sync › Ripristina, «Indicizza i generi».

## 5. Esterni (servono azioni dell'utente)

- **Hardcover OAuth**: registrare l'app su hardcover.app (redirect `app.atlante.library:/oauth/hardcover`) e mettere
  il `client_id` in `sync/HardcoverAuth.kt` (`CLIENT_ID`); il codice è pronto.
- **Catalogo più ricco**: `scripts/catalog/build_catalog.py` usa solo Wikidata (opere con ≥ 5 voci Wikipedia);
  i libri di genere recenti mancano. Ampliare con le opere più lette di Open Library (CC0) e rigenerare
  (`CatalogAssetTest`). Allineare la lista `MAIN` dello script a `saga/Genres.kt` (ora ha 5 generi in più;
  l'app usa comunque i generi grezzi del catalogo, quindi non è urgente).

## Dopo ogni rilascio

`bash scripts/sync-crafting.sh`, aggiornare `../android-release/LEGGIMI.txt` e `docs/CHANGELOG.md`.
