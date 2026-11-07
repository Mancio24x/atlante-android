---
name: atlante-debugging
description: Use when the user reports a bug, crash, wrong result or a failing CI run in the Atlante Android app (release build, UI preview on the emulator, unit tests), before changing any code.
---

# Debugging sistematico

1. **Riproduci prima di correggere.** Scrivi il caso più piccolo come test JVM/Robolectric in
   `app/src/test/java/app/atlante/library/` (Room in memoria, `LibraryStore(context)`, finti `HardcoverSync.Api`,
   `TestListenableWorkerBuilder` per i worker) e verifica che fallisca per il motivo atteso.
   Per dati reali di rete: test con `assumeTrue(System.getenv("LIVE") != null)` da cancellare dopo l'uso.
2. **Crash da Kotlin chiamato da Java**: controlla i parametri non nulli (`String` vs `String?`) quando un `Book`
   o un valore arriva da Java (es. `Book.id` è null per i libri non ancora salvati).
3. **CI**: `gh run list -L 5`, poi `gh run view <id> --log-failed`; per la release cerca `e:` (Kotlin),
   `error:` (Java), `Lint found`. Per l'emulatore: `gh run download <id>` e guarda l'ultima schermata e il suo `.xml`
   (testi e `content-desc` reali) prima di decidere se sbaglia l'app o lo script.
4. **Errori di rete** (Wikidata 429/500, timeout): non sono "nessun risultato"; controlla che il codice li distingua
   (vedi `SagaRecognizer.Live.failures`).
5. **Correggi la causa**, non il sintomo; mantieni il test come regressione.

## Verifica
Il test di riproduzione passa, i test del modulo passano, e per la CI l'esito del run successivo è `success`.
