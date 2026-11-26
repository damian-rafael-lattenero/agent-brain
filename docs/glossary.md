<!-- lang-ok: the contract lists the Spanish terms it maps to English -->
# Glossary and sweep contract — English everywhere

> Owner decision, 2026-11-26: every work product we own is English — code, comments, docs,
> identifiers, file and directory names, the study book, AGENTS.md, the skills, the memories,
> the bot copy, and the git commit-message history. This file is the single contract every
> translation batch follows. When a term is not here, keep the language of the code and translate
> the prose faithfully.

## The six non-translatables

Everything else is translated. These six are not prose, and each has a reason:

1. **Upstream / third-party**: `*/official/` mirrors, `sql-psi`, vendored licenses, ZCode upstream
   files. Never edit a file we do not own.
2. **Machine data**: WhatsApp `sidecar/auth` and `stanzas.jsonl`, database dumps and backups,
   `.xlsx` invoices, recordings, binaries, `git-diff-zcode*.json(.gz)`.
3. **Generated output**: `build/`, `dist/`, `node_modules/`, `evals/artifacts/`,
   `/Users/lattenero/evals/artifacts/`.
4. **Live identifiers**: SQL schema names (`mensajes`, `usuarios`, …) and applied migration
   filenames (`0001_archivo.sql`). They belong to a running database; renaming them is a data
   migration. The comments and CLI copy around them are translated.
5. **Detector inputs**: the Spanish regex and stopword lists in `english-law.nu`,
   `design-guard.nu`, `nushell-typing.nu` and their selftest strings. The gate needs them to
   recognize Spanish.
6. **Citations and verbatim records**: the original stays beside the English translation, marked
   `[translated]`. A quote is evidence, not prose.

## The glossary

| Today | English |
|---|---|
| ficha | card |
| hoja de ruta | route sheet |
| El ajá | The aha |
| parada (de una ficha) | stop |
| juez de rumbo / capa Rumbo | the course judge / Course |
| El Pedido | The Request |
| alineado · desvío menor · desvío | aligned · minor drift · drift |
| la escalera | the ladder |
| faro | lighthouse |
| hoja de papel · mis palabras | paper sheet · in my words |
| preguntas de recuperación · cierre | retrieval questions · close-out |
| cuaderno índice | paper index |
| notas curadas · espejo oficial | curated notes · official mirror |
| corrección → propuesta → promoción | correction → proposal → promotion |
| barrido | sweep |
| lote / tanda | batch |
| veredicto | verdict |
| puerta | gate |
| regla de la casa | house rule |
| hacer / no hacer | do / don't |
| cero busy-wait | zero busy-wait |
| cola · latencia | queue · latency |
| usuario · mensaje · archivo · salida · entrada | user · message · file · output · input |
| línea · columna · fila | line · column · row |

Book part titles: A The map and the base words · B The concepts · C Your system inside ·
D The harness as a layer of laws · E The numbers · F The method · G Answers · H The theory.

## Rename map (old → new)

| Old | New | Repo |
|---|---|---|
| `docs/plan-brujula.md` | `docs/plan-compass.md` | zcode-bot-kotlin |
| `docs/plan-brujula-v2.md` | `docs/plan-compass-v2.md` | zcode-bot-kotlin |
| `docs/book/00-hoja-de-ruta.md` | `docs/book/00-route-sheet.md` | agent-brain |
| `docs/ai-engineering-mapa-y-diccionario.md` | `docs/ai-engineering-map-and-dictionary.md` | agent-brain |
| `docs/concept-map-es.md` | `docs/concept-map.md` | agent-brain |
| `ECOSISTEMA.md` | `ECOSYSTEM.md` | ~/Projects (root, no git) |
| `nu/001-primer-script-deploy-learnings.md` | `001-first-deploy-script-learnings.md` | ~/Projects/nu |
| `nu/002-migracion-total-2026-11-02.md` | `002-full-migration-2026-11-02.md` | ~/Projects/nu |
| `tests/test_f27_incidente_red.py` | `tests/test_f27_network_incident.py` | bot |
| `tests/test_f79e_robustez.py` | `tests/test_f79e_robustness.py` | bot |
| `tests/test_higiene.py` | `tests/test_hygiene.py` | bot |
| `tests/test_f76_foco_y_reencarnacion.py` | `tests/test_f76_focus_and_reincarnation.py` | bot |
| `scripts/wipe-mensajes.nu` | `scripts/wipe-messages.nu` | pg-base |
| `scripts/vigilia-ingesta.nu` | `scripts/watch-ingest.nu` | pg-base |
| `scripts/vigilia-media.nu` | `scripts/watch-media.nu` | pg-base |
| `scripts/vigilia-corrida.nu` | `scripts/watch-run.nu` | pg-base |
| `scripts/log-vigilante.nu` | `scripts/watchdog-log.nu` | pg-base |
| `scripts/vector-huella.nu` | `scripts/vector-fingerprint.nu` | pg-base |
| `scripts/estado-huella.nu` | `scripts/state-fingerprint.nu` | pg-base |
| `scripts/estado-adjuntos.nu` | `scripts/state-attachments.nu` | pg-base |
| `scripts/estado-sidecar.nu` | `scripts/state-sidecar.nu` | pg-base |
| `scripts/estado-docs.nu` | `scripts/state-docs.nu` | pg-base |
| `scripts/estado-media.nu` | `scripts/state-media.nu` | pg-base |
| `scripts/sidecar-grupos.nu` | `scripts/sidecar-groups.nu` | pg-base |
| `scripts/verifica-ingesta.nu` | `scripts/verify-ingest.nu` | pg-base |
| `scripts/diag-ingesta.nu` | `scripts/diag-ingest.nu` | pg-base |
| `scripts/buscar-xlsx.nu` | `scripts/find-xlsx.nu` | pg-base |
| `scripts/las-6-filas.nu` | `scripts/the-6-rows.nu` | pg-base |
| `scripts/limpiar-huerfanas.nu` | `scripts/clean-orphans.nu` | pg-base |
| `scripts/archive/commit-huella.nu` | `scripts/archive/commit-fingerprint.nu` | pg-base |
| `scripts/archive/commit-ingesta.nu` | `scripts/archive/commit-ingest.nu` | pg-base |
| `scripts/archive/fix-ingesta.nu` | `scripts/archive/fix-ingest.nu` | pg-base |

Migration filenames keep their names (rule 4). Every rename updates all referrers in the same
commit; the final `rg` gate must find zero old basenames.

## Translation rules

1. Translate meaning, not style. Keep the document's structure: headings, order, code fences,
   tables, links, frontmatter, ids and cross-references.
2. Code stays code: identifiers are renamed only when the identifier itself is Spanish, and then
   every call site follows in the same commit. Behavior must not change.
3. Keep technical terms in English even when the Spanish text used a translation: functor,
   applicative, monad, monoid, fold, type class, callback, hook, gate, ledger.
4. Do not translate test expectations that are records of a past conversation; keep them and put
   the English rendering beside them `[translated]`.
5. The Spanish language detector inputs of rule 5 stay verbatim. Mark such a file with a first-line
   pragma `<!-- lang-ok: detector input -->` (or `# lang-ok: …` in code) so the gate can skip it.
6. One logical unit per commit, English message, conventional prefix: `docs: translate <scope> to
   English`, `refactor: rename <old> to <new>`, `chore: sync translated <artifact>`.
7. Never merge to master and never force-push a translated branch. The parent runs the gate
   (`~/Scripts/scripts/langcheck.nu`) and the suites before merging.

## Verification

- `rg -n '[áéíóúñ¿¡]' <your paths>` — the accent scan; report every remaining hit with its reason.
- The repo's own suite (see each unit).
- `nu ~/Scripts/scripts/langcheck.nu --all` once the parent has merged it: the mechanical gate.
