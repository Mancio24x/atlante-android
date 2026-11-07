# Changelog

Le release sono numerate `1.0.<numero di esecuzione del workflow>` e nascono a ogni push su `main` che tocca l'app
(non ogni commit corrisponde a una release). Il dettaglio è in `git log`; qui le modifiche che cambiano il
funzionamento. Aggiungere una voce a ogni rilascio, in cima.

## 2026-11-07

- **Correzioni dopo i test**: il segnalibro di Scopri non toglie più un libro che è già in biblioteca (prima, tornando
  in Scopri e toccandolo, il libro salvato veniva cancellato); le Impostazioni si aprono sempre dall'indice; ricerca
  della Biblioteca non tagliata sui telefoni stretti; numero di saga che non copre il titolo sulle copertine senza
  immagine; frase della rotta corretta a inizio anno.
- **Impostazioni in Compose** (`SettingsScreen.kt`): indice con ricerca; Lettura con anteprima del lettore e cursore
  del testo; Lingua dei libri come scelta che si salva da sola (via «Salva lingua»); Privacy e progetto.
- **Sync in Compose** (`SyncScreen.kt`), stesso aspetto (5a) e stesse azioni; schede bianche come nei design in
  Scopri, Statistiche e Universi.
- **Biblioteca più fluida**: le celle della griglia vengono riusate durante lo scorrimento invece di essere
  ricostruite. Barre di avanzamento piene, senza stacco né puntino finale. Test delle statistiche dopo unioni e
  riletture.
- **Correzioni**: anello di Home centrato con i numeri al centro; nello Scaffale torna il pulsante della vista.
  **Scopri**: «Popolari» (popolarità tra i lettori, non vendite), generi con gli stessi nomi della biblioteca
  (Giallo, Romanzo storico), edizione della scheda confermata con l'id dell'opera di Open Library.
- **Scheda unica a tutta pagina** per ogni libro non in biblioteca (Scopri, saghe, mappa, catalogo, ricerca online):
  si apre subito, poi edizione, copertina e trama; una risposta arrivata tardi per un altro libro è ignorata.
  **Trame sicure**: solo da Wikipedia dell'opera o da record della stessa edizione (stesso ISBN), mai per solo
  titolo e autore; testo pulito da HTML; «Mostra tutto». **Filtri e ordine** in un foglio dal basso con i filtri
  attivi rimovibili. Test per tutte le transizioni di stato.
- **Nuova grafica** (progetto Claude Design «Atlante», scelte 1b, 3a, 4b, 5a, 6a, 7a–7d): intestazione compatta con
  occhiello e titolo serif; barra in basso con voci non scelte in grigio e filetto in alto.
  **Home** (1b): una card «Oggi» con anello delle pagine, minuti, giorni di fila, settimana e rotta; il comodino come
  carosello con «Leggi» (timer) e «+10» pagine; barra della sessione in corso; attività recente in una card.
  **Biblioteca** (3a): ricerca in alto con «Filtri e ordine» e «+», stati come chip con il numero, griglia di sole
  copertine a 3·4·5 colonne con avanzamento, spunta e numero di saga sulla copertina; tieni premuto per le azioni.
  **Scopri** (4b): campo di ricerca, chip dei generi, segnalibro «Da leggere» sulla copertina (con Annulla),
  «Mostra altri 6», classifiche e collezioni come righe. **Statistiche** (4b): periodo Settimana/Mese/Anno con
  andamento, rotta mese per mese, calendario per intensità con il giorno sotto, biblioteca in una barra, generi.
  **Sync** (5a): stato in cima con «Sincronizza tutto» e conflitti, servizi come righe che si aprono, importa/esporta,
  fonti. **Impostazioni** (6a): indice con ricerca e pagine dedicate. **Menu** (7a): senza «Autori», con i numeri.
  **Saghe** (7b): card con una barra per volume e il prossimo da leggere, mappa verticale della saga. **Mappa** (7c):
  autori come chip e card dell'autore. **Universi** (7d): card con anello e una barra per saga.
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
