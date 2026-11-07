---
name: atlante-performance
description: Use when the task is about speed, smoothness or memory in the Atlante Android app (Scopri covers, search, library scrolling, background jobs, network requests), or before claiming any speed-up.
---

# Prestazioni: misura, poi cambia

Mai dichiarare un miglioramento senza i numeri prima e dopo, nelle stesse condizioni.

## Strumenti esistenti
| Cosa | Come |
| --- | --- |
| Copertine di Scopri (rete reale) | `HARDCOVER_TOKEN=$(cat ~/.config/atlante-android/hardcover-token) gradle :app:testDebugUnitTest --tests '*DiscoverSpeedLiveTest' -i --rerun` |
| Ricerca su 10.000 libri | `SearchIndexTest.tenThousandBooks` (stampa i ms per tasto) |
| Identificazione offline 100/1.000 libri | `CatalogAssetTest` (stampa tempi e richieste evitate) |
| Sul dispositivo | workflow `Android UI preview` → artefatto `perf.txt` (`scripts/capture-perf.py`: Scopri a cache vuota/disco/memoria, frame persi, memoria; log `AtlantePerf`) |

## Dove guardare per primi
- Limiti per host in `OnlineCatalog.download`/`interval` (una richiesta al secondo per le API; immagini CDN in parallelo).
- Lavoro sul thread principale (Room, decodifica bitmap): spostalo in coroutine/worker.
- Stessa risorsa scaricata due volte: `CoverImages` (memoria), `CoverCache` (disco), `cache_entries` (risposte).
- Indici: FTS4 per il testo, indici Room per gli id; niente confronti tutti-contro-tutti (vedi `BibliographicEngine.duplicates`, a gruppi).

## Verifica
Riporta la tabella prima/dopo (stesso test, 2 esecuzioni) e il collo di bottiglia residuo; i test funzionali devono restare verdi.
