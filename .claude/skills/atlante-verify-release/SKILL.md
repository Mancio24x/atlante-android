---
name: atlante-verify-release
description: Use before finishing any code change in the Atlante Android app and whenever publishing (commit, push to main, new release), to run tests proportionate to the change, release lint, a secrets check, and to follow the GitHub release and the crafting-folder sync.
---

# Verifica e rilascio

## Test proporzionati
- Modifica locale → test del modulo: `gradle :app:testDebugUnitTest --tests '*<Nome>Test'`.
- Prima di pubblicare → tutta la suite e lint, **un Gradle alla volta**:
  ```bash
  gradle -q :app:testDebugUnitTest > /tmp/t.log 2>&1; echo "exit $?"
  gradle -q :app:lintRelease > /tmp/l.log 2>&1; echo "exit $?"; grep -c 'severity="Error"' app/build/reports/lint-results-release.xml
  ```
  Considera riuscito solo `exit 0` e 0 errori; leggi i fallimenti in `app/build/test-results/testDebugUnitTest/*.xml`.
  Non fidarti di un `grep` che non trova righe: l'errore può avere un formato diverso.

## Sicurezza prima del commit
- `git diff --cached | grep -nE 'hc_pat_|hc_at_|hc_rt_|BEGIN (RSA|PRIVATE)|storePassword'` deve essere vuoto.
- Niente `signing.properties`, keystore, token o `local.properties` nel commit (sono in `.gitignore`).
- Per modifiche ad autenticazione, rete o permessi usa `/security-review`.

## Pubblicazione
1. Commit su `main` con messaggio che descrive il comportamento; chiudi con la riga `Co-Authored-By` richiesta.
2. `git push origin main` → workflow `Android APK` crea la release `1.0.<run>`; segui con `gh run watch <id> --exit-status`.
3. Cambi visibili → `gh workflow run "Android UI preview" --ref main`.
4. Release uscita → `bash scripts/sync-crafting.sh` e aggiorna le novità in `../android-release/LEGGIMI.txt`;
   aggiungi la voce in `docs/CHANGELOG.md` (e `docs/FEATURES.md` se cambia una funzione).
