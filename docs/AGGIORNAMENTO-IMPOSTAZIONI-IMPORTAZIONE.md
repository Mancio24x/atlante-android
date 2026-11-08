# Impostazioni, importazione eBook, obiettivi e timer

L’aggiornamento continua l’app esistente: Java/Views e Kotlin/Compose Material 3 convivono; Room bibliografico, catalogo personale JSON e preferenze esistenti restano al loro posto. Non ci sono migrazioni distruttive.

## Completato

- Impostazioni a sezioni: temi predefiniti richiudibili, temi personali a tre colori salvati, modalità chiara/scura/automatica, obiettivi, libreria e preferenze. Una sola azione di importazione nelle impostazioni: **Importa eBook**. Le funzioni preesistenti di backup restano accessibili dalla sezione Sync, senza una nuova schermata di backup.
- SAF con selezione multipla; copia privata dei file, rilascio dei permessi persistenti dopo la copia. Anteprima con titolo/autore modificabili e conferma prima dell’inserimento.
- EPUB: OPF, identificativi, copertina, indice EPUB 3 e NCX EPUB 2 con livelli dei sottocapitoli. TXT: UTF-8/BOM, UTF-16 e fallback Windows-1252; titolo dal nome del file modificabile, senza capitoli inventati. CBZ: immagini in ordine naturale, ComicInfo e copertina FrontCover.
- AZW3: parser PalmDB/MOBI/EXTH provato con un file KF8 generato da Calibre, metadati e copertina; decodifica testo non compresso/PalmDOC. File DRM rifiutati. HUFF/CDIC catalogabile ma lettura interna dichiarata non disponibile; indice Kindle e ricostruzione completa del layout KF8 non implementati. I backup e la riapertura del catalogo accettano AZW3.
- Metadati aggiuntivi su richiesta dall’anteprima: motore esistente Open Library/Wikidata e altri cataloghi; disambiguazione dell’edizione, provenienza e selezione dei campi prima della conferma. I file restano importabili anche senza rete.
- Duplicati per hash, ISBN, identificativi ASIN/UUID/DOI e titolo/autore. Collegamento a scheda senza file solo su conferma, conservando note, voti, progressi e ID. L’hash del file collegato è conservato separatamente dall’ID per permettere backup/ripristino senza perdere il file.
- Obiettivi minuti/pagine giornalieri, libri mensili/annuali: modifica, attivazione, disattivazione, reset e avanzamento da dati registrati. Le chiavi precedenti sono preservate. Storico mensile con obiettivi registrati: i mesi precedenti privi di un obiettivo noto non ricevono un valore inventato. Statistiche e Home usano gli stessi dati.
- Timer con cifre adattive più leggibili, pulsanti Pausa/Riprendi/Termina più grandi e colori attivi. Il tempo usa ancora l’orologio monotono; una sessione ha un identificativo persistente che impedisce di registrarla nuovamente dopo un’interruzione tra salvataggio e chiusura del timer.
- Rotelle orizzontali con aggancio centrale, evidenziazione, vibrazione disattivabile, inserimento numerico e conferma. Pagina/percentuale sincronizzate solo con totale noto; limiti controllati anche nella logica. La modalità aggiuntiva Pagine lette è preservata.

## File principali

- `MainActivity.java`: impostazioni, anteprime/importazione, collegamento dei duplicati, ricerca metadati, timer e conferma avanzamento.
- `ebook/EbookParser.java`, `ebook/Azw3.kt`: parser dei quattro formati.
- `model/Book.java`, `model/BookMatcher.java`, `storage/LibraryStore.java`, `storage/BackupArchive.java`: persistenza, identificativi, collegamento file e backup.
- `model/ReadingGoals.kt`, `model/ReadingJournal.java`: obiettivi, storico, conteggi e idempotenza del timer.
- `ui/ThemePreferences.java`, `ui/compose/GoalsSection.kt`, `ui/compose/ProgressWheels.kt`, `ui/compose/StatisticsScreen.kt`: temi, controlli e statistiche.
- `online/CoverSearch.kt`: correzioni già presenti nel lavoro recuperato, con test per evitare copertine di volumi diversi della stessa saga.
- Nuove icone timer e fixture sintetiche EPUB/TXT/CBZ/AZW3 in `app/src/test/resources/ebooks/`.

## Verifiche

Suite JVM/Robolectric: file reali di prova per ciascun formato, NCX e navigation annidata, codifiche TXT, rifiuto DRM e file falsi, copertine e metadati, duplicate linking e conservazione dati, backup AZW3 collegato, obiettivi storici e preesistenti, limiti/indipendenza pagina-percentuale, impostazioni senza voci duplicate, timer con pausa/ricreazione/salvataggio ripetuto. Build release, Android lint, firma e allineamento APK.

`SettingsTimerTest` usa l’Activity reale su Robolectric; `scripts/capture-update.py` verifica sull’emulatore le impostazioni, l’inserimento percentuale, lo scorrimento della rotella e il timer con rotazione. Il workflow Android UI preview esegue questo controllo; lo script precedente `capture-ui.py` è conservato per le verifiche delle altre schermate.

## Limiti e interventi ulteriori

- Nessun widget Android risultava implementato nel progetto/manifest recuperato: non c’è un widget esistente da aggiornare. Un widget nuovo richiede un intervento dedicato.
- AZW3: niente aggiramento DRM, HUFF/CDIC, indice Kindle o fedeltà del layout KF8 completo; per quei file usare un lettore compatibile o convertire legalmente in EPUB.
- Interfaccia in italiano e inglese (vedi `ui/L.kt` e `assets/i18n/en.tsv`; i testi nuovi vanno aggiunti al dizionario).
- Ricerche online dipendono dalla connessione e dalle fonti; nessun dato mancante viene inventato.
