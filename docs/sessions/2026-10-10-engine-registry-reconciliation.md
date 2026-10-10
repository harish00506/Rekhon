<!--
  Why:  CLAUDE.md §10 — one session file per session that changes code. This one is maintenance
        rather than an issue: the open finding ADR-0070 recorded, closed on request.
  What: the registry reconciliation — nine entries written, three stamped ids made resolvable, a
        drift test added, and a correction to the figure ADR-0070 published.
  Result: a reader can see why the ids came from the engines rather than being invented, and why
          the wrong count is corrected forward rather than edited away.
  Changelog: 2026-10-10 — Created.
-->

# 2026-10-10 — The engine registry reconciliation

Branch `fix/engine-registry-drift` off `dev` (`19a6511`). Version 0.13.6 → **0.13.7**
(versionCode 71). **Not an issue** — the finding ADR-0070 deferred, fixed on request.

---

## 1 · The missing rows were the symptom; the absent check was the defect

`ai/orchestrator/engine-registry.yaml` states its own contract in its header: *"One row per engine
(module `:domain:engines:*`)"*, and *"the orchestrator and provenance stamping resolve engine
metadata from here"*.

Nine modules had no row. But the thing worth fixing is that **nothing had ever verified the file**.
Issue 13.2 only found the gap because it happened to measure while adding a row; without that, the
count would still be growing.

---

## 2 · The obstacle looked like naming, and wasn't

ADR-0070 said the honest route to green was "ten accurate contract lines — one per engine, each
needing that engine's formula read and summarised". That was right about the contracts and wrong
about the hard part.

The hard part looked like **ids**. Most of the missing engines are deterministic feature engines the
SRS gives an `FR-` id rather than an `AI-*` name — net worth (FR-ACC-005), receipt (FR-OCR-003), SMS
(§18), recurring (FR-TXN-006), budget (FR-BUD-002/3/4), quick setup (FR-ONB-002), loan (FR-ACC-003),
card (FR-ACC-002), nature (§8.3). Minting nine ids would have been a real decision, because an
engine id is cited in stored provenance and must never be renamed.

Then I checked what each module actually stamps:

```
budget-planner   card-planner      loan-amortiser
nature-classifier  net-worth       quick-setup
receipt-parser   recurring-detector  sms-parser
```

**All nine had been writing a stable provenance id into real results the whole time.** Nothing
needed inventing. The ids existed in the data; the catalogue that is supposed to resolve them simply
did not know them — so a stored result citing `budget-planner` resolved to nothing, which is the
precise failure AI-ARC-006 exists to prevent.

*When a registry and the code disagree about names, check what the code writes before choosing new
ones.*

---

## 3 · `provenance_id`: two names, neither of which can move

Three **registered** engines stamp something other than their registry id:

| Registry `id` | Stamps |
|---|---|
| `AI-CLS` | `auto-categoriser` |
| `AI-INV` | `investment-xirr` |
| `AI-STS` | `safe-to-spend` |

Both names are load-bearing. The `AI-*` ids appear in `rules-kb.json`'s `consumed_by` fields, so
renaming them breaks those citations. The stamped ids are in results already written, so renaming
those orphans history — the rule the rulebook already applies to its own rule ids.

So `provenance_id` sits beside `id` and both resolve. Nothing moves, and the catalogue can answer
either question.

---

## 4 · The test is the fix

`EngineRegistryDriftTest` asserts four things:

1. every engine module has an entry;
2. no entry names a module that does not exist (the `:domain:engines:growth` ghost);
3. every id an engine stamps is resolvable — as an `id` or a `provenance_id`;
4. **the module count is pinned**.

The fourth matters most. Without it a new engine can be added and simply not listed, which is
exactly how nine accumulated. Now the count fails the build and the fix is to register the engine in
the same change that created it.

Three mutations: an entry deleted, a ghost module reintroduced, a `provenance_id` removed. All red.

---

## 5 · A correction, and what it is actually about

ADR-0070 reports **"10 of 28 engine modules have no entry"** and lists `chat`. **It was nine.**

`chat` was registered all along. Its `module:` field reads
`":domain:engines:chat + :ml:llm + :feature:chat"` — a compound string, because AI-CHAT spans three
modules — and the one-off regex I measured with looked for a module path alone in quotes.

ADR-0070 and issue 13.2's session file and tracker are **left as written**, with a forward-pointing
note on the ADR. They record what was believed on 2026-10-03, and this project keeps its history
rather than retouching it — the decision ADR-0068 made about the two versioning drifts it found.

The lesson is not the arithmetic. **A drift audit written as a one-off regex is itself unchecked
code.** Had the measurement been a test from the start, its blind spot would have surfaced the first
time somebody added an entry it could not parse — which is precisely the argument the audit was
making about the registry.

---

## 6 · Flow changed this session

**None.** The registry is data the orchestrator reads; no call path moved.

---

## 7 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/orchestrator/engine-registry.yaml` | Nine entries added with the ids their engines stamp; `provenance_id` on `AI-CLS`, `AI-INV`, `AI-STS`; header changelog |
| `core/common/src/test/.../EngineRegistryDriftTest.kt` | **New.** 5 tests pinning completeness, ghosts, id resolution and the module count |
| `docs/adr/0076-*.md` | **New.** The decision record |
| `docs/adr/0070-*.md` | A forward-pointing correction note; its text preserved |

---

## 8 · Quiz

1. **Why were nine engines never registered?** They have `FR-` ids rather than `AI-*` names, so they
   did not look like "AI engines" — though the registry's header says one row per module.
2. **Where did their ids come from?** They already existed: every one stamps a provenance id into
   real results, and had since it was built.
3. **Why `provenance_id` instead of renaming?** The `AI-*` ids are cited by rules-kb; the stamped
   ids are in written results. Neither can move.
4. **Which of the four assertions stops the drift recurring?** The pinned module count — a new
   engine must be registered in the same change that creates it.
5. **Why was ADR-0070's count wrong?** A compound `module:` field spanning three modules defeated
   the one-off regex. The audit was unchecked code.
