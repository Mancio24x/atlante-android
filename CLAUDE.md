# CLAUDE.md

Le regole del progetto sono in AGENTS.md (valgono per ogni agente): leggile, non sono ripetute qui.

@AGENTS.md

## Specifico di Claude Code

- Skill del progetto in `.claude/skills/` (si caricano da sole quando il compito è pertinente):
  `atlante-android-ui`, `atlante-data-changes`, `atlante-debugging`, `atlante-verify-release`, `atlante-performance`.
- Skill integrate da usare invece di rifarle: `/code-review` (revisione di un diff), `/security-review` (prima di
  toccare autenticazione, rete o segreti), `/simplify` (pulizia dopo una modifica grande).
- Comandi lunghi (test completi, lint, pipeline del catalogo) in background; non avviare due Gradle insieme
  (si bloccano a vicenda e il risultato non è affidabile).
