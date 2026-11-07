---
name: atlante-android-ui
description: Use when changing or adding any screen, dialog, button, list, theme colour or text in the Atlante Android app (View code in MainActivity.java or Jetpack Compose in ui/compose/), including accessibility and the emulator UI scripts that tap labels.
---

# UI di Atlante (View + Compose, Material 3)

Riferimento: `docs/UI_DESIGN.md`. Qui solo la procedura.

1. **Trova il punto giusto senza leggere tutto.** Schermata a View: `grep -n "private void <nome>" app/src/main/java/app/atlante/library/MainActivity.java`
   e leggi solo quell'intervallo. Schermata Compose: il file in `ui/compose/`.
2. **Nuova schermata o sezione complessa → Compose**, in un file suo in `ui/compose/`, con un oggetto
   `XxxView.create(activity, palette, …): View` (`ComposeView` + `AtlanteTheme(palette)`), inserito da `MainActivity`
   con `composePage(...)` o aggiungendo la View. Non far crescere `MainActivity` con logica che non è di navigazione.
3. **Colori**: in Compose solo `MaterialTheme.colorScheme.*`; nelle View solo `PAPER/WHITE/INK/MUTED/SAGE/COPPER/LINE`.
   Nessun colore esadecimale nuovo fuori da `ThemePalette`.
4. **Testi in italiano**, brevi. Se cambi un'etichetta, cercala negli script: `grep -n "<etichetta>" scripts/*.py`
   e aggiorna lo script (gli script toccano per testo o `content-desc`; i suggerimenti dei campi non sono testo visibile).
5. **Stabilità del layout**: spazio fisso per ciò che arriva dopo (copertine, risultati), azione principale vicino al
   contenuto, niente lavori lunghi sul thread principale (Room non lo consente, tranne il catalogo in sola lettura).
6. **Accessibilità**: `contentDescription` o `semantics(mergeDescendants = true) { contentDescription = … }` su
   immagini, anelli, carte; bersagli ≥ 48 dp.
7. **API Android**: minSdk 26. Ogni chiamata più recente va protetta con `Build.VERSION.SDK_INT` (lint della
   release la blocca: `gradle -q :app:lintRelease`).

## Verifica

- `gradle -q :app:compileDebugKotlin :app:compileDebugJavaWithJavac` senza righe `e:`/`error:`.
- Test della logica dietro la schermata (es. `SagaLayoutTest`, `SearchIndexTest`).
- Per cambi visibili: workflow `gh workflow run "Android UI preview" --ref main`, poi gli artefatti
  (`gh run download <id>`) per vedere schermate e albero UI.
