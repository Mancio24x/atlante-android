# Changelog

Le release sono numerate `1.0.<numero di esecuzione del workflow>` e nascono a ogni push su `main` che tocca l'app
(non ogni commit corrisponde a una release). Il dettaglio è in `git log`; qui le modifiche che cambiano il
funzionamento. Aggiungere una voce a ogni rilascio, in cima.

## 2026-11-08
- **Rapporti di crash ed errori** (`diagnostics/CrashLog.kt`, avviato da `AtlanteApp`): un crash ovunque (schermate, widget, lavori in background) scrive `crash-<ora>.txt` con errore e cause, log dell’app, versione, telefono e ultime azioni; gli errori mostrati e le riparazioni/Sync non riuscite vanno in `errors.txt`. Al riavvio i rapporti nuovi vanno in Download/Atlante/Diagnostica (Android 10+) e, dopo un crash, l’app lo dice con «Condividi». Impostazioni › Privacy e progetto › Diagnostica: quanti sono, condividi, svuota. Niente lascia il telefono se non lo condividi. `CrashLogTest`.
- **Libreria oltre 16 MB**: si salvava ma non si riapriva più («Libreria non disponibile · Contenuto troppo grande»); lo stesso limite impediva di ripristinare il backup di una libreria grande. Ora un solo limite di 256 MB (`LibraryStore.DATA_LIMIT`) per libreria, backup e catalogo Atlante. `BigLibraryTest` (2.600 libri, oltre 16 MB).
- **Modifiche perse durante Sync e riparazioni**: Sync, «Completa metadati» e «Indicizza i generi» copiano i libri all’inizio e li scrivono minuti dopo; pagine, stato o voto segnati nel frattempo venivano sovrascritti. Ora ogni copia si scrive solo se il libro è com’era (`SyncStore.replaceUnchanged`, controllo e scrittura sotto lo stesso blocco); la modifica tua resta e la prossima Sync la invia. `SyncTest.aChangeMadeDuringTheSyncIsKept`.
- Un errore che arriva dopo la chiusura della pagina non apre più un dialogo (su un telefono vero farebbe chiudere l’app).
- Verifica sull’emulatore: ogni passo stampato con il tempo, prove (screenshot, finestra, log) quando lo schermo non si ferma, cambio lingua dell’app, limite 60 minuti.
- **Tasti più riconoscibili**: i pulsanti di solo testo hanno un contorno leggero (View: stile `Atlante.Button.Text`; Compose: `ui/compose/TextButton.kt` al posto di quello di Material 3), i link come «‹ Tutte le impostazioni», «Mostra tutto» e le azioni delle intestazioni sono pillole con contorno; le sezioni a scomparsa hanno margine interno. Note dei sottomenu più leggibili: testo secondario un po’ più scuro (View e Compose) e note da 12 a 13 sp.
- **Interferenze tra funzioni**: un libro unito da «Ripara duplicati» mentre il suo timer era acceso o la sua scheda aperta non perde nulla. Il timer continua sul libro rimasto, «Salva la sessione» registra lì, la scheda aperta e i widget aprono il libro rimasto (prima la sessione veniva scartata, il salvataggio dava errore, la pagina tornava alla Biblioteca). `InterferenceTest`.
- **Ricerca dei doppioni bloccata (Hardcover)**: confrontava ogni copia creata da Sync con ogni libro e con ogni edizione del libro su Hardcover (spesso decine): con centinaia di copie erano ore, senza avanzamento né modo di interromperla; la sincronizzazione faceva lo stesso a ogni giro (`match`, `bestIdentity`) e «Ripara duplicati», che aspetta la fine di Sync, restava fermo. Ora si confrontano solo i libri che possono essere lo stesso (cognome dell’autore, ISBN, stesso libro Hardcover, autore mancante), con avanzamento e Interrompi: 400 copie in circa 2 secondi, 1.500 libri contro 800 record in meno di 2 (`HardcoverDuplicatesScaleTest`; risultati identici, i test di Sync passano).
- **Calibre sulla rete di casa funziona di nuovo**: Android (target API 35) blocca le connessioni `http://` in chiaro, e il server di contenuti di Calibre è quasi sempre `http://192.168…:8080`. Le richieste `http://` verso indirizzi privati, link-local o locali passano ora da un client su socket (`sync/LanHttp.java`), con Basic/Digest come prima; verso internet resta solo HTTPS. `LanHttpTest` con un finto server.
- Inglese: i titoli dei libri e i testi lunghi (trame, note) non vengono mai tradotti a pezzi.
- **Ripara libreria** (Impostazioni › Biblioteca): tutte le riparazioni in una pagina, ognuna con il suo stato letto dallo stesso record della notifica (in coda dopo…, in corso con la percentuale, completata con modifiche applicate e decisioni rimaste, non riuscita) e Avvia / Annulla / Decidi: duplicati, duplicati di Hardcover, saghe, mappe, metadati, copertine, generi; in fondo Qualità del catalogo, tutte le operazioni e «Annulla l’ultima unione». «Libreria e catalogo» tiene importazione, copertine preferite e cache.
- **Ripara duplicati** unisce davvero i doppioni più comuni: stesso titolo parola per parola (maiuscole e punteggiatura a parte) e stesso autore, anche senza ISBN, se non ci sono conflitti (voti, recensioni, file o date diversi restano da decidere). Prima finivano solo tra le proposte.
- Statistiche: «Indicizza i generi» è un pulsante sotto il grafico dei generi.
- **Versione inglese.** Impostazioni › Lingua › «Lingua dell’app»: come il telefono (predefinito), Italiano o English; su Android 13+ anche dalle impostazioni di sistema dell’app. Il codice resta in italiano: `ui/L.kt` traduce solo ciò che si vede, con il dizionario `assets/i18n/en.tsv` (frasi esatte, frasi con spazi «{}» per numeri e titoli, date, maiuscole); i titoli dei libri non vengono mai tradotti. Tradotti schermate View e Compose, dialoghi, fogli, toast, notifiche e widget. In inglese la lingua dei libri predefinita è English, Scopri e «Because you read» propongono le edizioni originali e la trama arriva dalla Wikipedia inglese. `HomeShot` con `SHOT_LANG=en` disegna le pagine in inglese e scrive i testi rimasti non tradotti; `TranslationTest` controlla il motore. L’emulatore resta in italiano (`cmd locale set-app-locales`).
- Aspetto: «Il mio tema» è un cerchio sopra i temi pronti e apre una pagina a tutto schermo (colori in alto, anteprima dal vivo in basso). Un solo tema personalizzato: niente più temi salvati con nome. Indietro da «Il mio tema» torna ad Aspetto; un test controlla il gesto indietro in ogni pagina delle impostazioni.

- **CrossPoint sul suo Wi-Fi**: «Collegati al Wi-Fi del lettore» si collega alla rete aperta «CrossPoint-Reader» (senza
  internet, 192.168.4.1) e il traffico verso il lettore passa dal Wi-Fi anche con i dati mobili accesi.
- **CrossPoint**: nuova pagina (menu laterale, e una riga in Sync) per il lettore e-ink CrossPoint sulla rete di
  casa: ricerca automatica (UDP «hello» sulla porta 8134) o indirizzo IP, stato del lettore, cartelle e file,
  «Invia libri» dalla biblioteca, «Scarica in Atlante» (con la solita importazione), nuova cartella, rinomina,
  sposta, elimina con conferma. Il client (`crosspoint/CrossPointClient.kt`) parla HTTP solo con indirizzi privati
  della rete di casa, così l'app non apre il traffico in chiaro verso internet (`CrossPointClientTest`).
- **Crash corretto**: «Ripara saghe» (e le altre riparazioni o la sincronizzazione Hardcover in background) chiudeva
  l'app con «Bad notification for startForeground»: l'icona della notifica prendeva il colore dal tema, che il
  sistema non può leggere. Nuova icona `ic_notification` senza attributi di tema (`NotificationIconTest`).
- **Obiettivi di lettura**: la pagina si apre subito sulle rotelle orizzontali (Material 3) per libri all'anno, libri
  al mese, pagine e minuti al giorno, ognuna con interruttore e avanzamento; da Home e Statistiche si arriva lì
  direttamente. **Scopri**: via le classifiche IBS e laFeltrinelli, al loro posto «Perché hai letto «…»» con tre
  libri vicini all'ultimo finito (stesso autore, poi stesso genere). **Indietro**: da ogni pagina principale torna
  alla Home; le pagine aperte dentro un'altra (libro, sottopagina delle Impostazioni, mappa di una saga) tornano a
  quella.

## 2026-11-07

- **Temi**: scegliendo un tema dalla pagina Aspetto l'app, dopo essersi ricaricata, resta su Aspetto invece di tornare
  all'indice delle Impostazioni.
- **Vista Elenco** a righe compatte (copertina piccola, titolo, autore, stato, formato) al posto delle vecchie schede
  grandi; «Altri filtri e ordine» scorre, così «Ordina per» non viene più schiacciato sui telefoni bassi.
- **Dall'emulatore**: niente «Aggiungi libro» sopra Saghe, Mappa e Universi; il tasto Biblioteca in basso riporta alla
  biblioteca intera da Saghe, Mappa, Universi o da una ricerca; Scopri scarta i titoli «italiani» che non lo sono
  (un'edizione cinese di Percy Jackson segnata come italiana su Open Library).
- **Ricerca online sempre vuota** all'apertura (dal menu e da Scopri); si riempie solo quando la apri per un libro
  preciso (volume mancante di una saga, «Cerca anche online»). **Trama**: da Wikipedia si prende la sezione «Trama»
  (o Sinossi, Riassunto) della voce, non l'introduzione che dice solo che libro è.
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
