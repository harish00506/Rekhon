<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 10.2 — AI-PA-INT, §13.3's buy list and the adaptive interview behind it.
  Result: a reader can see why the deltas are the numbers they are, why nothing is ever removed by
          the app, and why the answers are rows rather than a JSON blob.
  Changelog: 2026-09-26 — Created.
-->

# 2026-09-26 — The buy list and its interview (issue 10.2, ADR-0050)

**Branch:** `feature/10-2-buy-list-adaptive-interview-ai-pa-int` off `dev` (`20b4785`)
**Versions:**
- **VERSION** 0.10.0 → **0.10.1**
- **versionCode** 44 → 45
- **Schema** 25 → 26 (`wishlist_item`, `interview_answer`)
- **rules-kb.json** 1.21.0 → **1.22.0**
- `AI-PA-INT` 1.0 (new)

---

## 1 · Decisions this session

The full argument for each is in ADR-0050.

- **AI-PA-INT lives beside AI-PA.** The interview weighs a purchase the way the advisor does; two
  modules would have meant two definitions of what a purchase costs.
- **The ladder is basis points of monthly income.** An instalment is heavy whatever it costs, and so
  is a wish with **no income recorded** — with no share to take, the honest fallback is to ask.
- **Only the missing questions, one at a time.** A band is "the first N of the bank", so moving up a
  band asks what is new. The screen shows one question, because a wall of seven is the friction the
  ladder exists to ration.
- **The deltas are minted, and sized so no single answer decides an outcome.** From 50, no one
  answer may reach KEEP (70) or SUGGEST_REMOVE (40). **The first draft failed its own rule** — "need"
  at +20 reached KEEP and "I own one" at −20 reached SUGGEST_REMOVE — so they are +15 and −10, and
  two tests now enforce it, one of them against the rulebook's own numbers.
- **Nothing is ever removed by the app.** A low score changes what is said, not the list; the
  suggestion quotes the user with the points beside each answer, and both removing and keeping are
  taps.
- **Answers are typed rows, unique per question.** Changing your mind replaces an answer rather than
  adding a second — which is also what stops a score depending on write order. Each row keeps the
  points it was worth at the time (AI-ARC-006) while the live score uses today's rules.
- **The band is never stored**: it depends on income, and a stored band would go stale silently.
- **Deferred:** the alternative finder, §32's Worth-It history, the buy-timing watch, the automatic
  thirty-day re-ask, and the owning-cost amount input.

**What the device run caught, and nothing else would have:** the three answer buttons were laid out
in a row, and the third — "Rarely" — was clipped off the edge of a phone: invisible, and therefore
unanswerable. The tests all passed, because `performScrollTo()` finds a node the eye cannot. The
answers and the actions are stacked now, which also gives each a full-width target (§25).

## 2 · Flow changed this session

```
AdvisorScreen → BuyListSection
├─ AddWish   → BuyListRepository.add()                      wishlist_item, parked at 50
├─ AnswerWish → replace that question's row → AI-PA-INT reassesses → want_score written back
├─ AdviseWish → BuyListRepository.advise() → the §2.13 advisor, card kept
└─ MoveWish(REMOVED | BOUGHT) → a status change, never a delete
```

`FLOW.md` §2.14 holds the full chain.

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/rules/rules-kb.json`, `rulebook.md`, twelve `*Rules.kt` mirrors | 1.22.0: RULE-PAI-LADDER, RULE-PAI-SCORE |
| `domain/engines/purchase/` | AI-PA-INT: the engine, the bank, `AnswerKeys`, the mirror, `ENGINE.md`'s second half, and 34 new tests with a golden file and its oracle |
| `core/database/**` | schema 26: `wishlist_item`, `interview_answer`, `BuyListDao`, `MIGRATION_25_26`, round-trip test |
| `data/repository/BuyListRepository.kt` (new), `RepositoryFactory.kt`, archive/demo/drill files | the list, the answers, and both tables through backup, restore, wipe and drill |
| `feature/advisor/BuyListSection.kt` (new), state, ViewModel, strings | the list on screen: add, one question at a time, the evidence, and the three actions |
| `app/.../di/RepositoryModule.kt` | provides AI-PA-INT and the list |
| `docs/adr/0050-…`, `DECISIONS.md`, `FLOW.md` §2.14, `ai/orchestrator/engine-registry.yaml` | the records |
