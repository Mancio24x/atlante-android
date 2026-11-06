# Atlante Library per Android

Progetto Android separato dall’app desktop, per telefoni e tablet Android 8 o successivi.
Interfaccia nativa con i colori crema, petrolio, rame e salvia dei riferimenti, titoli
serif, copertine e mappa autore → saga → libro. Non contiene libri personali o dati
della biblioteca sul computer.

**APK disponibile nelle [release GitHub](https://github.com/Mancio24x/atlante-android/releases/latest).**
La compilazione automatica esegue test JVM, lint e verifica della firma; la prova su telefono resta necessaria.

## Navigazione e temi

L’interfaccia usa Material Design 3. In alto ci sono la barra di ricerca e i chip
**Tutti · Da leggere · In lettura · Letti**, più il chip **Filtri e ordine**; il pulsante
flottante **Aggiungi libro** crea una scheda manuale. Il menu **☰** porta a Biblioteca,
Mappa, Autori e Saghe, alla ricerca online, all’importazione e alle impostazioni; si
chiude toccando fuori o con Indietro. Indietro da Mappa, Autori o Saghe torna alla biblioteca.

Il tema predefinito è **Colori del telefono** (Material You, Android 12+): segue lo
sfondo e il tema chiaro/scuro del telefono. Restano disponibili nove palette, ricordate
al riavvio: **Atlante**, **Rosa petalo**, **Lavanda**, **Salvia**, **Blu polvere**,
**Sabbia**, **Grafite**, **Notte viola** e **AMOLED nero** (nero puro).

La **Mappa** occupa tutto lo schermo: in alto si cerca l’autore, sotto si scorre in
verticale autore → saghe → libri nell’ordine di lettura.
L’icona adattiva mostra un libro aperto con una rotta da mappa, un segnalibro rosso e una stella polare dorata su fondo petrolio con le linee di un mappamondo; su Android 13+ segue anche le icone a tema.

La ricerca lavora separatamente dalle importazioni e mostra una prima pagina
leggera di risultati. Risposte e miniature vengono riutilizzate in una cache locale
limitata: 15 minuti per la ricerca, 24 ore per schede e immagini. Le copertine
si caricano senza bloccare l’apertura dei metadati. La velocità del primo accesso
resta legata alla connessione e alla disponibilità di Open Library.
La griglia usa RecyclerView: tiene in memoria solo le schede visibili e decodifica le copertine in background, alla dimensione della scheda; la ricerca filtra
comunque l’intero catalogo. I controlli sul contrasto coprono tutte le palette.

Il workflow manuale **Android UI preview** esegue sull’emulatore Android 35 una
prova di menu, impostazioni, temi e riavvio; le schermate sono disponibili tra gli
artifact. Usa soltanto dati fittizi nell’app debug, separata dalla libreria personale.

## Catalogazione manuale, scheda e copertine

**Aggiungi libro** apre una scheda manuale utilizzabile anche offline, senza ISBN
né file. Solo il titolo è obbligatorio. Puoi registrare sottotitolo, autori, tag,
scaffale/posizione, cartaceo/ebook/audiolibro, stato, voto da 0 a 5, date di inizio
e fine lettura, pagine, trama e note. Le modifiche si applicano su una copia e
vengono confermate soltanto con **Salva**; **Annulla** conserva la scheda precedente.

Dalla scheda puoi scegliere una copertina dalla galleria tramite il selettore
Android: viene salvata una copia privata, senza cambiare l’originale. Il pulsante
L’icona **Elenco / Griglia** in alto cambia la visualizzazione e ricorda la scelta al riavvio.
Piano e limiti della modifica sono in [PIANO-E-VERIFICA.md](PIANO-E-VERIFICA.md).

Il Mac usato da Craft Anything è configurato con Java 17, Gradle 8.9 e Android
SDK 35 (Build Tools 35.0.0). La compilazione locale firmata è stata verificata
con test e lint. `build.sh` carica automaticamente la configurazione locale da
`~/.config/atlante-android/env.sh`, quando presente. Per compilare la versione
attuale: `VERSION_NAME=1.0.12 VERSION_CODE=100012 bash build.sh release`.
L’APK viene generato nella cartella `dist/`. La chiave di firma e le proprietà
locali restano escluse da Git. Il workflow **Android APK** su GitHub resta
disponibile per pubblicare le release.

## Backup e ripristino

Da **⚙ Impostazioni → Backup**, **Esporta backup** salva un file ZIP con catalogo,
note, avanzamento, valutazioni e copertine, scelto con il selettore Android (anche
su un servizio cloud). Puoi includere i file EPUB/TXT/CBZ oppure creare un backup
leggero senza file. **Ripristina da backup** mostra un riepilogo e, dopo conferma,
aggiunge solo i libri non presenti: le schede già sul telefono non vengono toccate.
I file ripristinati devono corrispondere alla loro impronta SHA-256; voci sconosciute
o percorsi non sicuri nello ZIP vengono ignorati. Un libro ripristinato senza file
conserva note e avanzamento: reimportando lo stesso file da **☰ › Importa ebook**
viene ricollegato alla sua scheda. Durante la lettura, la posizione viene salvata
poco dopo l’ultima pagina girata e all’uscita dal lettore.

## Dati bibliografici da più fonti

**Completa i dati online** (pagina del libro o menu ⋮) interroga insieme più cataloghi:
catalogo **SBN** (ICCU), **Open Library** (edizione e opera), **Internet Archive** e, se
inserisci la tua chiave in *Sync › Fonti dei dati bibliografici*, **Google Books** (chiave
gratuita) e **ISBNdb** (servizio a pagamento, facoltativo). Hardcover è usato solo da Sync.

Cerca prima per ISBN; senza ISBN cerca per titolo e autore e, se trova edizioni diverse,
chiede quale possiedi invece di sceglierne una. I dati dell’**edizione** (titolo, editore,
collana, anno, pagine, lingua, traduttore, curatore, illustratore, ISBN, copertina) vengono
solo dai record di quell’edizione; quelli dell’**opera** (titolo originale, prima
pubblicazione, saga, argomenti, classificazione Dewey) anche dalle altre edizioni. Per i
libri italiani l’edizione viene presa prima da SBN. Il genere viene dalla classificazione
Dewey o dalle categorie dell’editore, mai da parole chiave libere. Le trame nella lingua
scelta vengono prima; quelle in altre lingue sono indicate come tali.

Nulla cambia da solo: una pagina mostra, campo per campo, il valore attuale, quello nuovo
e la fonte. I campi vuoti sono proposti già selezionati; quelli che hai modificato a mano
restano deselezionati. Le copertine dell’edizione si scelgono tra quelle trovate (miniature
e segnaposto vengono scartati) e la precedente viene conservata. Ogni dato accettato
ricorda la sua fonte, visibile in *Dettagli della scheda*.

## Scopri

Scopri (in Compose) mostra una **griglia di 6 copertine**: senza filtro sono i libri più letti, cioè quelli che più lettori di Open Library vogliono leggere, scelti fra quelli che hanno un'edizione italiana. Con un genere scelto (Fantasy, Gialli, Fantascienza, Classici…) sono i 6 più letti di quel genere.

- Un solo libro per autore, niente cofanetti e niente libri che hai già.
- Titolo e copertina vengono dall'edizione italiana quando esiste davvero.
- *Altri 6* mostra il gruppo successivo; lo stesso filtro dà sempre lo stesso elenco. I dati restano in cache per 7 giorni.
- Toccando una copertina parte subito la ricerca online di quel libro: lo aggiungi tu, dopo l'anteprima.
- *Cerca un libro* apre la ricerca per titolo, autore o ISBN, vuota.

## Saghe: opere, edizioni e riconoscimento (in sviluppo)

Le saghe vengono ricostruite da **Wikidata** (serie P179, numero P1545, autore P50, titolo P1476, precedente/successivo P155/P156) e **Open Library** (opere ed edizioni), solo con le API pubbliche e gratuite.

- **Opera ed edizione sono distinte**: *L'ultimo impero* (edizione italiana) e *The Final Empire* sono la stessa opera, *Mistborn #1*.
- **Il punteggio decide cosa fare**: dall'85 in su l'opera viene collegata da sola; tra 60 e 84 l'app chiede conferma; sotto il 60 non collega nulla. Le soglie sono configurabili (`SagaRecognizer.reliable` e `uncertain`). Due prove dalla stessa fonte non contano come indipendenti.
- **Se l'opera è nota ma la saga no**, il libro risulta *Saga non identificata* e non viene considerato autoconclusivo.
- **Le voci della saga stessa e le singole edizioni** (per esempio un ebook o un audiolibro) non vengono scambiate per l'opera.
- **Gli universi** (Cosmere) non sono saghe; le sotto-serie (Wax and Wayne dentro Mistborn, la trilogia dentro Red Rising Saga) restano annidate.
- **Ricostruzione della saga** (`SagaRepository`): ogni saga viene salvata con tutti i volumi, anche quelli che non possiedi, in ordine, con titolo originale e italiano e le sotto-serie annidate. Le serie di audiolibri o di edizioni sono escluse. Se mancano numeri o ci sono buchi nella numerazione, la saga è segnata *Elenco potenzialmente incompleto*. Le saghe vengono riaggiornate ogni 7 giorni (configurabile). Le saghe create a mano e i volumi aggiunti a mano non vengono mai toccati, e un collegamento confermato o scelto dall'utente resta com'è.
- **Nella pagina del libro** (sezione in Compose) la saga viene cercata la prima volta che apri il libro. Poi ci sono tre caroselli che si caricano uno dopo l'altro:
  - **Altri libri della saga**, con *Visualizza saga completa*: tutti i volumi, quali possiedi, quali hai letto e quali mancano.
  - **Altri libri di [autore]**: prima i tuoi, poi le altre opere da Open Library, con *Visualizza tutti i libri dell'autore*.
  - **Potrebbero interessarti**: stesso genere normalizzato (Epic Fantasy → Fantasy) e libri usciti entro 30 anni dal tuo, i più pubblicati per primi, massimo due per autore, esclusi quelli che hai già. Lo stesso libro dà sempre lo stesso elenco.

  Toccando un libro che non hai si apre la ricerca online: nessun suggerimento entra in biblioteca da solo. Se la saga è incerta l'app chiede conferma. Se manca compare *Saga non identificata*, con le opzioni Riprova, Cerca per titolo originale, Scegli una saga, Crea una saga e Lascia senza saga. Le risposte online restano in cache per 30 giorni.
- **Mappa della saga** (Compose, Canvas), da *Visualizza saga completa* → scheda *Mappa*: una linea verticale con una tappa per volume. Pieni i libri letti (colore primario) e quelli in biblioteca (secondario), tratteggiati i mancanti, un anello attorno al libro aperto; le sotto-serie sono bande colorate a sinistra. Si può scegliere fra due ordini di lettura: *Ordine della saga* e *Ordine di pubblicazione*. Pizzica per lo zoom (0,5×–3×), trascina per spostarti, doppio tocco per tornare all'inizio, tocca una tappa per aprire il libro o cercarlo online. La scheda *Elenco* mostra gli stessi volumi in lista.
- **Filtri**: il genere usa il genere normalizzato (*Epic Fantasy* e *Fantasy epico* contano come Fantasy; i generi non riconosciuti finiscono in *Altri generi*). In più puoi filtrare per saga: solo libri di una saga, libri singoli o una saga precisa. Nel menu, *Tutti i libri*, *Autori*, *Saghe* e *Universi* sono viste separate. *Universi* (in Compose) elenca gli universi narrativi riconosciuti, per esempio il Cosmere, con le loro saghe e quanti libri possiedi; toccando una saga si apre la sua mappa.
- **Ripara saghe** (Impostazioni → Saghe e mappa): ricontrolla con Wikidata e Open Library i libri mai controllati, incerti, senza saga o non identificati; un interruttore permette di ricontrollare anche quelli già riconosciuti. Ricostruisce gli elenchi delle saghe, scrive la saga nella scheda solo se il campo è vuoto e toglie i collegamenti a libri rimossi. Le scelte fatte da te non vengono toccate. Mostra l'avanzamento, si può interrompere e alla fine riassume cosa è cambiato. Le proposte rapide offline di prima restano disponibili.
- **Cache e dati temporanei** (Impostazioni, in Compose): mostra lo spazio usato da copertine dei suggerimenti, risposte dei cataloghi, suggerimenti e database delle saghe. Si può regolare:
  - lo spazio massimo per le copertine (100 MB di default): oltre il limite vengono tolte per prime quelle usate meno di recente;
  - la durata dei suggerimenti (30 giorni);
  - l'intervallo di aggiornamento delle saghe (7 giorni): lo fa WorkManager in background, solo con rete e batteria non scarica, al massimo 20 saghe per volta.

  All'avvio l'app pulisce da sola i dati scaduti, e da qui puoi svuotare tutto a mano. Libri, note, voti e avanzamento non vengono mai toccati.
- **Il grafo bibliografico** è salvato in un database Room separato (`atlante-bibliography.db`, migrazioni versionate e non distruttive). La libreria personale resta in `library.json` ed è collegata per id del libro. Le correzioni manuali non vengono mai sovrascritte.

I test (`SagaRecognizerTest`, `SagaDatabaseTest`) usano risposte reali di Wikidata salvate in `app/src/test/resources/wikidata`.

## Copertine: motore di ricerca

`CoverSearch` raccoglie le copertine candidate da fonti pubbliche e gratuite, senza chiavi, e tiene la più nitida che appartiene davvero al libro.

**Fonti, dalla più precisa:**
1. Open Library per ISBN (sia a 10 che a 13 cifre).
2. Google Books per ISBN.
3. Apple Books per ISBN.
4. Le altre edizioni della stessa opera su Open Library, prima quelle italiane.
5. Titolo + autore su Apple Books, Google Books e Open Library.

**Come sceglie:**
- **Controllo del libro**: una copertina vale solo se ha lo stesso ISBN, oppure lo stesso titolo **e** lo stesso autore. Le etichette di volume (*Blackwater I*) non bastano. Un titolo di una sola parola deve coincidere esattamente, così *Dune* non diventa *I figli di Dune*.
- **Ordine di preferenza**: prima la stessa edizione, poi le edizioni italiane (ISBN 978-88 o 979-12), poi le più grandi, fino a circa 1000 px.
- **Scarti**: segnaposto, immagini minuscole e immagini orizzontali.

**Come si usa:**
- **Nella pagina del libro**, *Scegli copertina online* mostra tutte le copertine trovate, con fonte e dimensioni. Tocchi quella che vuoi.
- **Nelle Impostazioni**, *Cerca e migliora le copertine* scarica quelle mancanti e sostituisce quelle sotto i 300 px, ma solo se ne trova una almeno 1,5 volte più grande.
- **Le tue scelte restano**: le copertine scelte da te (dalla galleria o online) non vengono mai sostituite in automatico.

## Importare un catalogo da altre app

**☰ › Importa catalogo o backup** (oppure **Impostazioni › Backup › Ripristina o importa
un catalogo**) riconosce da solo il formato del file scelto:

- **backup di Atlante**: lo ZIP di *Esporta backup*, anche se è stato estratto (il solo
  `atlante-backup.json`), messo in una cartella o compresso dentro un altro ZIP;
- **elenchi CSV** con intestazione, separati da virgola, punto e virgola o tabulazione,
  anche esportati da **Goodreads** e **StoryGraph**: titolo, autore, saga e numero, ISBN,
  editore, anno, pagine, genere, tag, stato, voto, date di lettura, note e trama;
- **catalogo HTML di Atlante** (`Atlante_dei_libri.html`) con le copertine incorporate;
- **librerie Calibre**: uno ZIP con le cartelle dei libri e i loro `metadata.opf`,
  comprese copertine, serie e valutazioni.

Prima di aggiungere mostra il formato riconosciuto e quanti libri sono nuovi. Un libro
già presente (stesso identificativo, stesso ISBN o stesso titolo e autore) non viene
duplicato né modificato. I libri importati da altre app diventano schede senza file.

Dopo un’importazione o un ripristino Atlante cerca da solo, in background, le copertine
mancanti: Open Library per ISBN, poi il servizio immagini di Google Books per ISBN (utile
per le edizioni italiane recenti), poi le altre edizioni dell’opera e infine titolo e
autore su Open Library, accettando solo titoli corrispondenti. Le copertine già presenti
non vengono sostituite. **Impostazioni › Backup › Scarica copertine mancanti** ripete la
ricerca per tutta la biblioteca. Ai servizi vengono inviati solo ISBN, titolo e autore.

## Sync: Hardcover, Calibre e Kindle

La sezione **Sync** condivide stato di lettura, pagina e capitolo con altri servizi.
Libro per libro vince il lato cambiato dall’ultima sincronizzazione; se sono cambiati
entrambi vince il telefono; al primo collegamento vince la lettura più avanzata. I libri
si collegano tramite il collegamento salvato, l’ISBN oppure titolo e autore.

- **Hardcover** (hardcover.app): con una chiave personale creata in *Impostazioni ›
  Hardcover API* sincronizza in entrambe le direzioni stato (Da leggere, In lettura,
  In pausa, Letto), pagina e voto con mezze stelle. Hardcover non ha il capitolo. I libri
  presenti solo su Hardcover diventano schede; i libri iniziati in Atlante vengono aggiunti
  a Hardcover (anche quelli «Da leggere», se lo scegli). Usa l’API GraphQL ufficiale.
- **Calibre**: tramite il server di contenuti di Calibre sulla rete di casa sincronizza
  stato, pagina e capitolo in tre colonne personalizzate (`#stato` testo, `#pagina`
  numero intero, `#capitolo` testo; i nomi si possono cambiare). Per scrivere serve un
  utente Calibre con permesso di modifica; autenticazione Basic e Digest. I libri presenti
  solo in Calibre diventano schede con copertina. **Come preparare Calibre** spiega i passi.
- **Kindle**: Amazon non condivide stato e pagina con altre app. Si importano evidenziazioni
  e note dal file `documents/My Clippings.txt` del Kindle (lingua italiana o inglese):
  finiscono nel taccuino del libro, senza ripetizioni; i libri mancanti diventano schede.

Chiavi e password restano nelle preferenze private dell’app. Nella pagina **Aggiorna
avanzamento** una rotella sceglie il **capitolo** raggiunto, se il libro ha l’indice.

## Filtri e ordinamento della biblioteca

**Filtri e ordine**, accanto ai chip degli stati, combina genere e formato.
Le scelte restano al riavvio e valgono anche per autori, saghe e mappa; il contatore
mostra i risultati sul totale. **Azzera** permette di ritrovare tutti i libri.
L’ordine può essere per titolo, autore o saga e numero del volume (i libri senza
saga seguono gli altri). La ricerca trova anche gli ISBN salvati.

Questo miglioramento è un’implementazione nativa indipendente ispirata alle viste
con filtri di [BookLore](https://github.com/booklore-app/BookLore) e al catalogo
personale mobile con ISBN di [bibliotrack](https://github.com/diegoperu/bibliotrack).
Non integra i loro server, non copia il loro codice e non aggiunge sincronizzazione
né scansione dei codici a barre.

## Ricerca online, schede e avanzamento

Premi **Cerca libri online** e cerca un titolo, autore o ISBN su Open Library.
Scegli la **Lingua preferita** (Italiano predefinito, poi English, Français, Deutsch,
Español, Português o tutte): i libri con un’edizione in quella lingua compaiono per
primi, con titolo e copertina di quell’edizione, che viene poi usata per la scheda.
**Mostra solo libri in questa lingua** esclude gli altri. La scelta viene ricordata.

Con l’**italiano** la ricerca interroga anche il **catalogo SBN** (Servizio Bibliotecario
Nazionale, ICCU), che raccoglie i libri delle biblioteche italiane e conosce molte più
edizioni italiane di Open Library, comprese quelle recenti. I risultati SBN compaiono per
primi, solo libri a stampa e uno per opera (l’edizione più recente con ISBN); quelli già
presenti tra i risultati di Open Library non vengono ripetuti. Dalla scheda SBN arrivano
editore, anno, pagine e ISBN; copertina e trama vengono cercate su Open Library tramite
l’ISBN, quando disponibili. Le ricerche per ISBN usano il campo ISBN del catalogo.
Il servizio usato è quello JSON dell’app ufficiale OPAC SBN: non è un’API documentata
e potrebbe cambiare; se non risponde restano i risultati di Open Library.
**Vedi la scheda completa** apre un’anteprima con copertina, autore, trama e
metadati dell’edizione di riferimento (preferita italiana tra quelle restituite).
I campi mancanti sono indicati come non disponibili. Verifica l’edizione e premi
**Aggiungi alla mia libreria**: viene salvata una scheda senza file, disponibile
anche offline, con stato **Da leggere**. La ricerca non scarica ebook.
Lo stesso lavoro di Open Library viene riconosciuto per evitare schede duplicate.

Anche i file importati passano da una scheda di anteprima: ogni libro viene
aggiunto solo dopo conferma. I dati del file, inclusa la trama se presente,
restano modificabili in **Modifica scheda**. I cataloghi esistenti vengono conservati.

Gli stati di lettura sono quattro: **Da leggere**, **In lettura**, **In pausa** e
**Letto**. Le schede che nelle versioni precedenti erano «Abbandonato» tornano Da leggere.
Nella pagina del libro la sezione **La tua lettura** cambia lo stato con un tocco
(In lettura imposta la data di inizio, Letto quella di fine e l’ultima pagina) e mostra
un anello di avanzamento, pagine rimaste e, con abbastanza sessioni, il tempo stimato.
**Aggiorna avanzamento** apre una pagina con le rotelle: il piccolo selettore
**Pagina / % / Pagine lette** si ricorda l’ultima scelta (pagina e totale, percentuale,
oppure pagine lette in questa sessione) e una rotella facoltativa registra i minuti.
**Date, voto e note** raccoglie stato, date scelte dal calendario, voto da 0 a 5 stelle
con le mezze stelle e note; si salva solo con **Salva**.

Toccando un libro si apre la sua pagina a tutto schermo: stato e avanzamento, timer di
lettura, voto, recensione, taccuino, **Trama** e **Dettagli della scheda** chiusi finché
non li tocchi, **Capitoli** e storia della lettura.

Le ricerche HTTPS avvengono solo su richiesta e inviano a Open Library (e, per l’italiano,
a OPAC SBN) il testo cercato; l’apertura di una scheda richiede metadati e copertina della scheda scelta.
File, catalogo locale e note non vengono inviati. Timeout e dimensioni delle
risposte sono limitati e le ricerche recenti sono riutilizzate in memoria.
Riferimenti: [Search API](https://openlibrary.org/dev/docs/api/search),
[Works API](https://openlibrary.org/dev/docs/api/books),
[linee guida API](https://openlibrary.org/developers/api).

## Funzioni dell’edizione Android

- Importazione multipla dal selettore file Android: EPUB, TXT UTF-8 e CBZ.
- Copie private dei file, riconoscimento duplicati tramite SHA-256, catalogo scritto
  atomicamente. Gli originali scelti nel selettore restano al loro posto.
- Copertine incorporate e metadati EPUB/ComicInfo, ricerca, indici autori/saghe,
  modifica di titolo, autore, saga, numero e genere.
- Mappa scorrevole con numeri di lettura, fino a 250 opere per autore.
- Lettore testuale EPUB/TXT con caratteri serif, tema chiaro/scuro, dimensione
  regolabile e punto di lettura salvato. Il testo EPUB viene semplificato: non è
  un’impaginazione completa con immagini, stili e tabelle.
- Lettore CBZ a pagine con ordine numerico e direzione manga; avanzamento salvato.
- Stati Da leggere/In lettura/Letto; esportazione del file originale e rimozione
  della sola copia nella libreria Android.

Questa edizione non comprende conversioni AZW3/EPUB,
assistente AI, scaffali multipli, PDF/FB2/MOBI, sincronizzazione o importazione
automatica del catalogo desktop. I libri si importano dal telefono.
Il permesso Internet serve alla ricerca online; non è richiesto accesso generale alla memoria.
Non aggira DRM. Limiti: 512 MB per file, 32 MB per voce ZIP/testo e 5000 pagine CBZ.
I dati sono privati dell’app: disinstallandola vengono cancellati; un normale
aggiornamento firmato con la stessa chiave li conserva.

## Creare la release su GitHub, senza compilare sul computer

La cartella `android` è la **radice di un repository separato**: `.github`, `app`,
`build.gradle` e questo README devono trovarsi al primo livello. Puoi usare anche
`../android-release/Atlante-Android-source.zip`, che include i file nascosti.

1. Crea un repository GitHub pubblico, ad esempio `atlante-android`, con ramo
   principale `main`, e carica il contenuto di questa cartella. Per conservare
   anche `.github`, puoi usare GitHub Desktop: copia tutto nella cartella del
   repository, poi **Commit to main → Publish repository / Push origin**.
2. La firma è già stata generata **fuori dal repository**, in `../android-private/`.
   Conserva una copia sicura di tutta quella cartella. Non caricarla come file
   del repository o della release.
3. Su GitHub apri **Settings → Secrets and variables → Actions → New repository secret**.
   Crea questi due segreti, copiando il contenuto dei rispettivi file privati:

   | Nome del segreto | File da aprire sul computer |
   | --- | --- |
   | `APK_KEYSTORE_BASE64` | `android-private/APK_KEYSTORE_BASE64.txt` |
   | `APK_KEYSTORE_PASSWORD` | `android-private/APK_KEYSTORE_PASSWORD.txt` |

4. Apri **Actions → Android APK → Run workflow → main → Run workflow**.
   L’invio iniziale dei file può mostrare un errore se mancavano i segreti: dopo
   averli aggiunti, avvia una nuova esecuzione con **Run workflow**.
5. Quando il workflow è verde, apri **Releases**. Troverai
   `Atlante-Library-1.0.N.apk` e il suo controllo SHA-256. Questo è il file
   installabile; il file ZIP dei sorgenti non è un APK.

Ogni successivo aggiornamento su `main` crea una nuova release con versione
`1.0.N`, dove N è il contatore del workflow. Il `versionCode` è `100000 + N`.
Una riesecuzione della stessa build aggiorna gli stessi allegati. Conserva
nome del workflow, identificatore `app.atlante.library` e firma, per mantenere
gli aggiornamenti installabili. Non usare il pacchetto `.debug` per Obtainium.

Se si copia **solo lo ZIP sorgente** su un altro computer, la chiave non vi è
contenuta. I due segreti vanno configurati dal proprietario usando la cartella
privata conservata; il workflow non genera né sostituisce automaticamente firme.

## Aggiungere l’app a Obtainium

Dopo la prima release, apri **Obtainium → Aggiungi app** e incolla l’indirizzo
del tuo repository, per esempio `https://github.com/TUO-NOME/atlante-android`.
Scegli la sorgente GitHub e conferma. Viene distribuito un solo APK universale,
senza scelta del processore. Le future release saranno rilevate da Obtainium.

## Struttura del codice

Il codice è diviso per argomento in `app/src/main/java/app/atlante/library/`:

| Cartella | Contenuto |
| --- | --- |
| *(radice)* | `MainActivity`: navigazione e schermate non ancora migrate |
| `ui/compose/` | schermate in Jetpack Compose (tema condiviso `AtlanteTheme`, Statistiche) |
| `model/` | `Book`, diario di lettura (`ReadingJournal`), filtri (`LibraryQuery`), riconoscimento dei doppioni (`BookMatcher`) |
| `storage/` | catalogo sul telefono (`LibraryStore`), backup ZIP, importazione di cataloghi |
| `online/` | Open Library, catalogo SBN, saghe |
| `sync/` | Calibre, Hardcover, KOReader, evidenziazioni del Kindle e regole di sincronizzazione |
| `ebook/` | lettura di EPUB, TXT e CBZ |
| `ui/` | griglia, copertine, mappa delle letture, palette dei temi |

I test JVM sono in `app/src/test/java/app/atlante/library/`. `MainActivity` resta nella
radice perché il nome della sua classe identifica il file delle preferenze (tema, filtri,
collegamenti di Sync).

## Compilazione locale per chi sviluppa

Servono JDK 17, Gradle 8.9, piattaforma Android 35 e Build Tools 35.0.0.
Il progetto usa Android Gradle Plugin 8.7.3, Kotlin 2.0 e Jetpack Compose (Material 3) per le schermate già migrate, Material Components e AndroidX per le altre. Le build sono ottimizzate con R8: il codice dell’app resta intero, viene tolto solo quello inutilizzato delle librerie.
Non è incluso un finto wrapper Gradle: il workflow installa Gradle esplicitamente.

```sh
# Versione di prova, con identità separata app.atlante.library.debug:
bash build.sh debug

# Release, usando la firma privata già conservata (nessuna password nel comando):
export APK_KEYSTORE_PATH="/percorso/privato/atlante-release.p12"
read -rs APK_KEYSTORE_PASSWORD
export APK_KEYSTORE_PASSWORD
bash build.sh release
unset APK_KEYSTORE_PASSWORD
```

In alternativa, `signing.properties` locale, escluso da Git, può contenere
`storeFile=/percorso/atlante-release.p12` e `storePassword=...`.
La chiave deve avere alias `atlante` e formato PKCS12. Per una nuova installazione
del progetto senza firma precedente, `python3 scripts/create-signing.py
/cartella/privata/esterna/al/repository` la genera una volta sola.

`build.sh` esegue i test JVM, Android Lint, la compilazione, `apksigner verify`
e `zipalign -c`; salva APK e checksum in `dist/`. I test coprono percorsi EPUB,
limiti degli archivi, ordine dei fumetti e paginazione Unicode. La prova su
telefono/emulatore resta necessaria prima di considerare collaudata una release.

## Riferimenti

- [Compatibilità Android Gradle Plugin 8.7 / Gradle 8.9 / JDK 17](https://developer.android.com/build/releases/agp-8-7-0-release-notes)
- [Firma delle applicazioni Android](https://developer.android.com/studio/publish/app-signing)
- [Segreti per GitHub Actions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
- [Aggiungere un’app a Obtainium](https://wiki.obtainium.imranr.dev/ui_overview/)

Codice applicativo MIT, vedi LICENSE. Libri e copertine importati mantengono
i diritti dei rispettivi autori e non fanno parte di questo progetto.


## Compagno di lettura

La barra Material 3 in basso porta a cinque sezioni: **Home**, **Biblioteca**,
**Scopri**, **Statistiche** e **Sync** (la ricerca è nella barra in alto della Biblioteca); ognuna ricorda posizione e ricerca, e Indietro
riporta a Home. **Home** mostra i libri in lettura con pagine rimaste e aggiornamento
rapido, il riepilogo di oggi (pagine, minuti, giorni di fila, obiettivo quotidiano),
gli obiettivi annuale e mensile con un anello di avanzamento, l’attività recente e
alcuni suggerimenti presi dalla tua biblioteca (indicati come tali, non sono classifiche).

Nella pagina del libro **Inizia a leggere** avvia un timer che continua anche fuori
dall’app, con pausa, ripresa e chiusura: alla fine chiede la nuova posizione e salva
la sessione. Ci sono anche voto con mezze stelle, recensione (titolo, testo, data, voto),
taccuino ricercabile (note, pensieri, citazioni, domande, riassunti con pagina e tag),
cronologia con sessioni e riletture. Dal menu del libro: preferito, collezioni,
**Inizia una rilettura** (le letture precedenti restano nella cronologia) e
**Apri con KOReader**, che riceve il file con un permesso temporaneo; le posizioni dei
due lettori restano indipendenti.

**Statistiche** riassume pagine, minuti e giorni di fila, gli obiettivi, un calendario
mensile (tocca un giorno per vedere le sessioni), i libri per stato e per genere.
**Scopri** apre la ricerca online, l’esplorazione per genere, le classifiche italiane
(collegamenti alle pagine aggiornate di IBS e laFeltrinelli, senza posizioni inventate)
e le collezioni. In **Biblioteca** la vista può essere Scaffale, Griglia o Elenco, con i
chip Tutti, Da leggere, In lettura, In pausa, Letti e Preferiti.

I nuovi dati personali stanno in un oggetto `journal` facoltativo di ogni libro e passano
nei backup esistenti; obiettivi e timer restano nelle preferenze del telefono. Dettagli e
limiti: [REDESIGN-MIGRATION.md](REDESIGN-MIGRATION.md).


## KOReader, copertine e saghe

In **Sync**, KOReader è la prima voce. Configura lo stesso server HTTPS e account
usati nel plugin di sincronizzazione di KOReader, collega i libri e premi
**Ricevi avanzamento da KOReader**. Prima del salvataggio compare l’elenco delle
percentuali ricevute: puoi confermare o annullare. Per il collegamento tramite
nome file, in KOReader scegli il metodo di identificazione «nome file» e invia
l’avanzamento; in Atlante indica il nome esatto, estensione compresa. In alternativa
puoi indicare il `partial_md5_checksum` di KOReader. Funziona anche con schede
senza EPUB. Il servizio non offre un elenco dei libri: vanno collegati esplicitamente.

Questa integrazione riceve percentuale e stato, non sincronizza note o evidenziazioni
né invia posizioni ad altri dispositivi. La posizione EPUB di KOReader è un XPointer,
non una pagina di Atlante: convertirla arbitrariamente danneggerebbe il segnalibro.
Protocollo: [API ufficiale KOReader](https://github.com/koreader/koreader/blob/master/plugins/kosync.koplugin/api.json)
e [client ufficiale](https://github.com/koreader/koreader/blob/master/plugins/kosync.koplugin/KOSyncClient.lua).
Le credenziali restano nelle preferenze private e non vengono inoltrate sui redirect.

Le copertine vengono cercate anche nel catalogo **Google Books**, per ISBN oppure
per titolo e autore verificati, oltre ai tentativi su Open Library e Google per ISBN.
I guasti di una fonte non impediscono di provare le altre. Le ricerche dei metadati
inviano titolo/autore o ISBN ai fornitori, mai testi, note o file personali.
[Documentazione Google Books](https://developers.google.com/books/docs/v1/reference/volumes).

**Impostazioni** è una pagina a tutto schermo: anteprime dei temi con selezione
visibile, dimensione e anteprima del testo, lettore scuro, lingua delle ricerche,
backup, importazioni e download delle copertine. L’interfaccia resta in italiano.
Il tasto Indietro torna alla sezione da cui hai aperto le impostazioni.

Da Impostazioni o dalla Mappa usa **Riconosci saghe e ordine**. Le proposte mostrano
prima/dopo e puoi deselezionarle singolarmente prima di applicarle. Il riconoscimento
combina autore e titoli italiani/inglesi di dieci saghe, numerazione esplicita nei
titoli e nei metadati. Mistborn distingue le due ere; raccolte e omnibus non ricevono
un numero inventato. È un catalogo iniziale, non un riconoscimento universale.
Per le altre saghe puoi modificare saga e numero nella scheda. I numeri sconosciuti
vengono dopo quelli noti, sia nella mappa sia nell’ordinamento per saga.


## Impostazioni, eBook e obiettivi

La nuova organizzazione, i formati realmente supportati, la conservazione dei dati e i test sono descritti in [Aggiornamento impostazioni e importazione](docs/AGGIORNAMENTO-IMPOSTAZIONI-IMPORTAZIONE.md).
