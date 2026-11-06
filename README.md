# Atlante Library per Android

Progetto Android separato dall’app desktop, per telefoni e tablet Android 8 o successivi.
Interfaccia nativa con i colori crema, petrolio, rame e salvia dei riferimenti, titoli
serif, copertine e mappa autore → saga → libro. Non contiene libri personali o dati
della biblioteca sul computer.

**APK disponibile nelle [release GitHub](https://github.com/Mancio24x/atlante-android/releases/latest).**
La compilazione automatica esegue test JVM, lint e verifica della firma; la prova su telefono resta necessaria.

## Navigazione e temi

Il pulsante **☰** in alto a sinistra apre il menu laterale: Tutti i libri, Desideri,
In lettura, Letti, Mappa, Autori e Saghe, più ricerca online, importazione e impostazioni.
Il menu si chiude con **☰**, toccando fuori o premendo Indietro.
La rotella **⚙** apre le impostazioni, con temi e preferenze di lettura.

Sono disponibili nove temi, ricordati al riavvio: **Atlante**, **Rosa petalo**,
**Lavanda**, **Salvia**, **Blu polvere**, **Sabbia**, **Grafite**, **Notte viola**
e **AMOLED nero**. L’AMOLED usa nero puro per lo sfondo della biblioteca.
Pulsanti, schede, campi, menu e nodi della mappa hanno bordi arrotondati.
La nuova icona usa sfondo nero, libro chiaro e bandiera pirata rossa.

La ricerca lavora separatamente dalle importazioni e mostra una prima pagina
leggera di risultati. Risposte e miniature vengono riutilizzate in una cache locale
limitata: 15 minuti per la ricerca, 24 ore per schede e immagini. Le copertine
si caricano senza bloccare l’apertura dei metadati. La velocità del primo accesso
resta legata alla connessione e alla disponibilità di Open Library.
La griglia si allunga da sola durante lo scorrimento e decodifica le copertine in background, alla dimensione della scheda; la ricerca filtra
comunque l’intero catalogo. I controlli sul contrasto coprono tutte le palette.

Il workflow manuale **Android UI preview** esegue sull’emulatore Android 35 una
prova di menu, impostazioni, temi e riavvio; le schermate sono disponibili tra gli
artifact. Usa soltanto dati fittizi nell’app debug, separata dalla libreria personale.

## Catalogazione manuale, scheda e copertine

**＋ Aggiungi libro** apre una scheda manuale utilizzabile anche offline, senza ISBN
né file. Solo il titolo è obbligatorio. Puoi registrare sottotitolo, autori, tag,
scaffale/posizione, cartaceo/ebook/audiolibro, stato, voto da 0 a 5, date di inizio
e fine lettura, pagine, trama e note. Le modifiche si applicano su una copia e
vengono confermate soltanto con **Salva**; **Annulla** conserva la scheda precedente.

Dalla scheda puoi scegliere una copertina dalla galleria tramite il selettore
Android: viene salvata una copia privata, senza cambiare l’originale. Il pulsante
**Elenco / Griglia** cambia la visualizzazione e ricorda la scelta al riavvio.
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
conserva note e avanzamento: reimportando lo stesso file da **☰ › Importa file**
viene ricollegato alla sua scheda. Durante la lettura, la posizione viene salvata
poco dopo l’ultima pagina girata e all’uscita dal lettore.

## Filtri e ordinamento della biblioteca

**Filtri e ordine**, sotto la ricerca, combina stato di lettura, genere e formato.
Le scelte restano al riavvio e valgono anche per autori, saghe e mappa; il contatore
mostra i risultati sul totale. **Azzera filtri** permette di ritrovare tutti i libri.
L’ordine può essere per titolo, autore o saga e numero del volume (i libri senza
saga seguono gli altri). La ricerca trova anche gli ISBN salvati.

Questo miglioramento è un’implementazione nativa indipendente ispirata alle viste
con filtri di [BookLore](https://github.com/booklore-app/BookLore) e al catalogo
personale mobile con ISBN di [bibliotrack](https://github.com/diegoperu/bibliotrack).
Non integra i loro server, non copia il loro codice e non aggiunge sincronizzazione
né scansione dei codici a barre.

## Ricerca online, schede e avanzamento

Premi **Cerca libri online** e cerca un titolo, autore o ISBN su Open Library.
**Vedi la scheda completa** apre un’anteprima con copertina, autore, trama e
metadati dell’edizione di riferimento (preferita italiana tra quelle restituite).
I campi mancanti sono indicati come non disponibili. Verifica l’edizione e premi
**Aggiungi alla mia libreria**: viene salvata una scheda senza file, disponibile
anche offline e nella sezione **Desideri**. La ricerca non scarica ebook.
Lo stesso lavoro di Open Library viene riconosciuto per evitare schede duplicate.

Anche i file importati passano da una scheda di anteprima: ogni libro viene
aggiunto solo dopo conferma. I dati del file, inclusa la trama se presente,
restano modificabili in **Modifica scheda**. I cataloghi esistenti vengono conservati.

Dalla scheda usa **Aggiorna avanzamento e note** per pagina attuale, totale e
note personali, anche per un cartaceo. La percentuale viene calcolata dal totale.
Lo stato comprende Da leggere, In lettura, Letto, In pausa e Abbandonato; le sezioni
**In lettura** e **Letti** aiutano a ritrovare i titoli. Il segnalibro del lettore
digitale resta separato dalle pagine dell’edizione cartacea inserite manualmente.
L’icona dell’app mostra un libro chiaro con bandiera pirata rossa su sfondo nero.

Le ricerche HTTPS avvengono solo su richiesta e inviano a Open Library il testo
cercato; l’apertura di una scheda richiede metadati e copertina della scheda scelta.
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
- Stato Da leggere/In lettura/Letto; esportazione del file originale e rimozione
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

## Compilazione locale per chi sviluppa

Servono JDK 17, Gradle 8.9, piattaforma Android 35 e Build Tools 35.0.0.
Il progetto usa Android Gradle Plugin 8.7.3 e le sole API della piattaforma.
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
