> **Nota sulla compilazione:** questo resoconto registra i controlli locali svolti
> da Craft Anything. La mancanza di Java/Gradle/SDK sul Mac non impedisce la
> compilazione remota: dopo il caricamento dei sorgenti, il workflow GitHub
> genera l’APK firmato nelle [release](https://github.com/Mancio24x/atlante-android/releases).
> L’esito Android aggiornato va letto nel workflow e nelle note della release,
> separatamente dai controlli dell’edizione Rust descritti qui sotto.

# Libreria personale Android — 6 novembre 2026

## Analisi prima delle modifiche

Esiste un progetto Android in `android/`: Java, Activity e widget Android nativi,
Gradle/Android Gradle Plugin, Android minimo 26 (Android 8), SDK 35. Adatto a
un APK installabile senza sito, PWA o server. Il progetto Rust/egui è un'edizione
precedente separata: in questo intervento non ne sono stati modificati codice o dati.

Esaminati Book.java, LibraryStore.java, MainActivity.java, LibraryQuery.java,
manifest, build.gradle, build.sh, test CatalogTest e workflow verify.yml.
Già presenti: persistenza privata in library.json con AtomicFile, copertine locali,
importazione EPUB/TXT/CBZ, lettori semplificati, ricerca Open Library con anteprima,
scheda modificabile, avanzamento/note, filtri, autori/saghe/mappa, temi.
Il catalogo locale è un archivio JSON, NON un database SQLite. Non viene migrato
in questa modifica: la migrazione richiederà copia di sicurezza e test su dati reali.

## Fonti effettivamente esaminate

- [Openshelf](https://github.com/Swappppy/Openshelf): letti README, LICENSE (GPL-3.0),
  `lib/main.dart` e inventario radice/lib. Nel codice esaminato: avvio Flutter,
  Riverpod, preferenze, localizzazione, tema e scelta onboarding/biblioteca.
  Griglia/elenco, scansione e statistiche sono descritte nel README: le loro
  implementazioni non sono state esaminate o eseguite.
- [bibliotrack](https://github.com/diegoperu/bibliotrack): letti README e
  `backend/models/book.py`, inventario radice/models. Il modello SQLAlchemy contiene
  ISBN facoltativo, autori multipli JSON, stato, note, valutazione e date di aggiunta.
  Il README dichiara MIT, ma nella radice elencata non c'è LICENSE: licenza completa
  non verificata. Nessun codice riutilizzato. Scanner e backup non esaminati nel codice.
- [librarium](https://github.com/FireBall1725/librarium): letti README e package.json,
  inventario radice/src. Questo repository è ora la pagina promozionale Astro/Tailwind,
  non il gestore libreria. Il README dichiara AGPL-3.0, senza LICENSE nella radice
  esaminata. Backend Go, React e SwiftUI sono in repository separati non analizzati.
  Nessuna implementazione di catalogo o scanner analizzata qui.

Implementazione originale Java: non copiato codice né risorse dai tre progetti.
Le funzioni descritte sono spunti; non si attribuisce loro una verifica operativa.

## Piano per fasi e criteri di avanzamento

1. Base Android e catalogazione manuale offline: completare scheda, copertine,
   griglia/elenco; verificare salvataggio, modifica, riavvio, modalità aereo,
   annullamento e compatibilità del catalogo precedente. Valutare poi SQLite con
   migrazione reversibile. Non dichiarare conclusa la fase finché test APK non passano.
2. Metadati e ISBN: conservare Open Library, correzione dei dati prima di conferma,
   fallback manuale; valutare Google Books e scanner locale con permesso fotocamera.
3. Saghe e filtri: entità saga, mancanti/posseduti, ordine decimale confermato,
   filtri combinati e ordinamenti aggiuntivi. Non dedurre ordini non documentati.
4. File: collegamenti tramite selettore Android e apertura esterna, PDF e altri
   formati compatibili; verifica permessi persistenti e file spostati. Restano i
   lettori EPUB/TXT testuali e CBZ già presenti; nessuna promessa su DRM/conversioni.
5. Backup e statistiche: ZIP con catalogo, copertine e note, modalità unisci/sostituisci
   con riepilogo e conferma; specificare esplicitamente inclusione o esclusione ebook.
   CSV, conteggi per stato, anno di fine lettura e genere. Attualmente backup Android
   e statistiche richieste non sono implementati.

Ogni fase dipende dai test della precedente; le funzioni anticipate già esistenti
vengono conservate, senza presentarle come nuove fasi verificate.

## Fonti metadati: verifica preliminare

[Open Library](https://openlibrary.org/developers/api) espone API pubbliche:
limite predefinito 1 richiesta/secondo, 3 per client identificati con contatto;
richiede cache, richieste legate all'utente e niente scraping o scarichi massivi.
La ricerca già esistente non è stata provata in rete in questo intervento.
[Google Books](https://developers.google.com/books/docs/v1/using) offre ricerca
volumi e richiede identificazione con chiave API o OAuth: integrazione non aggiunta.
Quote effettive del progetto e condizioni di caching/attribuzione vanno verificate
prima dell'integrazione; non si assume una quota universale.
Il tentativo di consultare la pagina ICCU sui servizi Z39.50 non è riuscito:
API, limiti e condizioni OPAC SBN non verificati. Nessuno scraping di SBN/IBS
aggiunto sulla sola base del README di bibliotrack.

## Modifiche della prima fase

Pulsante Aggiungi libro visibile nella biblioteca, inserimento senza ISBN o file,
modifica su copia e conferma atomica con ripristino in memoria in caso di errore.
Nuovi campi persistenti: sottotitolo, tag, formato fisico/ebook/audio, posizione,
valutazione facoltativa, inizio/fine lettura e data aggiunta. Pagine, note e stato
sono modificabili nel modulo. Date e pagine vengono validate; i dati sconosciuti
possono restare vuoti (solo il titolo è richiesto). Autori multipli per ora in
un campo testuale unico: non ancora una lista strutturata.
Ricerca locale anche per tag/sottotitolo. Griglia/elenco a colonna singola con scelta
salvata. Copertina dalla galleria tramite selettore Android: copia privata JPEG,
limite ingresso 12 MB e decodifica ridotta. Nessun nuovo permesso generale memoria.
Il solo permesso manifest resta Internet per la ricerca esplicita; nessun account,
tracking o pubblicità aggiunti. Queste modifiche sono ancora da provare su Android.

## Verifica e installazione

- `bash android/build.sh debug`: FALLITO prima della compilazione, manca Java 17.
  Mancano inoltre Gradle e Android SDK nel percorso disponibile. Nessun nuovo APK.
- Aggiunto test JVM di round trip della scheda manuale senza ISBN, campi personali,
  posizione del lettore e ricerca tag; NON ESEGUITO per mancanza toolchain.
- `rebuild_check`: passa build, avvio/screenshot e mechanics dell'edizione Rust
  configurata in Craft Anything. NON valida il Java né i flussi Android.
- Aperte tutte e quattro le immagini originali e confrontata la nuova schermata
  `checks/round-0/screenshot.png`: copertine e titoli serif restano coerenti, ma il
  tema scuro salvato differisce dai riferimenti crema e la somiglianza automatica
  con picture-1 è circa 26%. Non è una schermata Android; nessun emulatore disponibile.

Per compilare: aprire `app/android` in Android Studio con JDK 17, SDK platform 35,
build-tools 35.0.0 e Gradle 8.9; eseguire `bash build.sh debug`. In alternativa
usare il workflow GitHub già incluso dopo aver caricato i sorgenti aggiornati.
L'APK atteso è `android/dist/Atlante-Library-<versione>-debug.apk`; copiarlo sul
telefono Android 8+ e consentire l'installazione da quella fonte. La versione debug
è separata dalla release: non ne importa automaticamente i dati.
Prima del rilascio provare: aggiunta senza ISBN offline, modifica/annullamento,
riavvio, copertina, tag, griglia/elenco, date errate e catalogo precedente.
La disinstallazione cancella i dati privati: il backup resta da implementare.
