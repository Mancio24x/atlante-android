# Changelog

Le release sono numerate `1.0.<numero di esecuzione del workflow>` e nascono a ogni push su `main` che tocca l'app
(non ogni commit corrisponde a una release). Il dettaglio è in `git log`; qui le modifiche che cambiano il
funzionamento. Aggiungere una voce a ogni rilascio, in cima.

## 2026-11-07

- **Scopri**: un tocco su un libro apre subito la sua scheda (crash corretto: il libro cercato non ha ancora un id);
  ricerca delle copertine in background con notifica; anello grande dell'obiettivo di oggi nelle Impostazioni.
- **Scheda di un libro online**: «Aggiungi alla mia libreria» sotto la copertina, spazio fisso per la copertina,
  trama in italiano (Wikipedia tramite il catalogo, poi Hardcover/Open Library/Internet Archive).
- **Stati**: «Letto» → «Da leggere» riporta l'avanzamento a 0 (il tocco sbagliato in giornata viene annullato, una
  lettura precedente resta nello storico).
- **1.0.63**: misure sull'emulatore (`capture-perf.py`, log `AtlantePerf`).
- **1.0.62**: catalogo offline di 10.000 opere nell'APK (Wikidata + Open Library, FTS4); indice FTS4 della
  biblioteca (database v2, migrazione 1→2); ricerca unificata; `BibliographicEngine` (una sola opera = una sola
  scheda), unione sicura con annullamento, importazioni come altra edizione; riparazioni in background
  (`RepairWorker`, tabella `repair_jobs`, «Operazioni in background»); scelta dell'edizione principale; differenze
  sui metadati come proposte; filtri per sottogenere, sotto-serie, universo, edizioni; saghe dello stesso universo
  nella mappa; correzione della build (API 31).
- Mappa delle saghe basata solo sui dati: numeri verificati sulla linea verticale, rami per 2.5 e racconti, numeri
  mancanti segnati, «Posizione da verificare», ordine personale; «Controlla le mappe».
- Hardcover: conflitti da scegliere invece di sovrascrivere; «Sono libri diversi» ricordato; doppioni certi uniti
  da soli; libri dello stesso autore cercati prima di creare una scheda; «Cambia copertina» con le copertine
  inglesi e originali; copertine di Scopri circa 50 volte più veloci (misurato); ricerca per parole e filtri
  [Genere] [Autore]; «Qualità del catalogo».
- Sync Hardcover in background e veloce; Hardcover prima fonte quando collegato; widget Home; Scopri per generi
  votati; tema personale salvato in un tocco; grafico dei generi.

## 2026-11-06

- Grafo bibliografico (Room) con riconoscimento delle saghe da Wikidata e Open Library, carousel della saga,
  mappa verticale, generi normalizzati, «Ripara saghe», cache con limiti.
- Motore dei metadati da più fonti (SBN, Open Library, Internet Archive, Hardcover); motore delle copertine.
- Compagno di lettura: navigazione in basso, timer, recensioni, note, obiettivi, statistiche; Jetpack Compose e
  tema Material 3 condiviso; rotelle di avanzamento; vista Scaffale; temi personali.
- Sync con Hardcover, Calibre, KOReader ed evidenziazioni Kindle; riconoscimento dei libri già presenti.
- Importazione eBook completa (EPUB, TXT, CBZ, AZW3) e di cataloghi da altre app; codice diviso in pacchetti.

## 2026-11-05

- Prima edizione Android pubblicata con release su GitHub e installazione con Obtainium.
