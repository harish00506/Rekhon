# ADR-0050 — The buy list: a ladder in basis points of income, minted score deltas no single answer can decide, and nothing ever removed by the app

- **Status:** accepted
- **Date:** 2026-09-26
- **Deciders:** Harish G (solo)
- **SRS refs:** §13.3 (§13.3.1 escalation ladder, §13.3.2 interview logic), §13.2, §19.2
  (`add_to_buylist`, `review_buylist`), §32 (the personal value model), AI-ARC-003/006, P-02, P-07,
  P-08, MNY-001/002, DB-003, DB-005, CLAUDE.md §6; issue 10.2. Builds on ADR-0049 (the advisor it
  re-uses), ADR-0017 (typed mirrors)

## Context

§13.3 asks for a buy list with an interview whose depth scales with the purchase's weight: a table
of five bands with question counts, a WantScore starting at 50 that moves with the answers, three
outcomes, a thirty-day re-interview, and an honesty rule ("may challenge, never nag"). It names the
factors that move the score — need versus want, expected uses, a duplicate-ownership hit, goal-delay
tolerance, the Worth-It category score, urgency versus cooling behaviour — but **gives no numbers**
for any of them. It also sketches two tables, one of which stores answers as JSON.

## Decision

**1. AI-PA-INT lives beside AI-PA in `:domain:engines:purchase`.**
The interview weighs a purchase the same way the advisor does and hands off to it; two modules would
have meant two definitions of what a purchase costs. It is a second public interface in the module,
each with its own implementation, mirror and tests.

**2. The ladder is basis points of monthly income** (RULE-PAI-LADDER): 0.5%, 2%, 10%, 25%, and then
heavy. An instalment is heavy whatever it costs, because borrowing is the weight. **With no income
recorded, every wish is heavy** — with no share to take, the honest fallback is to ask rather than
wave it through.

**3. Only the missing questions are asked, one at a time.**
The bank is a fixed list and a band is "the first N of it", so moving up a band asks what is new
rather than everything again. The screen shows the next unanswered question and no more — a wall of
seven questions would be exactly the friction the ladder exists to ration.

**4. The score deltas are minted, and sized so no single answer decides an outcome** (RULE-PAI-SCORE).
§13.3.2 names the factors without numbering them, so the numbers are this ADR's. The constraint that
makes them defensible: from the opening 50, **no one answer can reach KEEP (70) or SUGGEST_REMOVE
(40)**. A score settled by one tap would make the rest of the interview theatre. Two tests enforce
it — one on the engine, one against the rulebook's own numbers — and the first draft failed both:
"need" at +20 reached KEEP, and "I own one already" at −20 reached SUGGEST_REMOVE. They are now +15
and −10.

**5. Nothing is ever removed by the app.** A low score changes what the app *says*, never the list.
The suggestion carries the user's own answers with their points, and there is a tap to remove and a
tap to keep (§13.3, P-07). The repository test asserts that a suggested-for-removal wish is still on
the list, and that a removal is a status change rather than a delete.

**6. Answers are typed rows, not `answers_json`.**
§13.3 sketches a blob; this stores one row per answer in `interview_answer` at schema 26, unique on
`(profile, item, question)`. So changing your mind **replaces** an answer rather than adding a
second — which is also what stops a score depending on the order rows were written in — and §32's
value model will be able to count answers rather than parse them. DB-005's blob-versioning question
never arises.

**Each row keeps the points the answer was worth when it was given** (AI-ARC-006), while the live
score is recomputed from today's rules. The two can then be compared rather than silently conflated.

**7. The band is recomputed on every read, never stored.**
The ladder depends on income, so a raise moves a wish down a band. A stored band would go stale
silently; the engine is pure and cheap, so the list recomputes. Income is the same median
`HealthSignals.obligations` gives the health score — one definition of what this household earns.

**8. The re-interview question sits outside the ladder.**
"Do you still want it?" is last in the bank, so no band ever asks it, and it is scored wherever it
is answered. That keeps §13.3.2's organic promotion without giving a casual wish a second question.

## Deferred, and why

- **The alternative finder** (§13.3: "you bought Sony WH-CH520 14 months ago…"). It needs a search
  over owned assets and past purchases by category and merchant, which is a retrieval problem worth
  its own issue rather than a half-built lookup here.
- **The Worth-It history** (§32's personal value model). §32 does not exist; the answers this issue
  stores are the input it will eventually read, which is why they are typed rows.
- **Buy-timing watch** — sale-season alerts and target-price notifications. The plumbing exists on
  both sides (9.6's notification gate, the advisor's verdict), and the missing piece is the
  seasonal calendar signal that ADR-0049 already deferred. `target_price_minor` is stored so the
  watch has somewhere to read from.
- **The thirty-day auto re-interview.** The window is a rulebook param and `last_interviewed_at` is
  stored, but nothing schedules it yet; that is a worker, and it should arrive with the watch above
  rather than on its own.
- **The total-cost-of-ownership answer's amount field.** The question is in the bank and scored at
  zero; it needs an amount input rather than a tap, and the screen offers taps today.
- **Chat's `add_to_buylist` tool** (§19.2) — epic 10.5 builds the tool layer.

## Consequences

- A wish can be added in two fields, and the app's questions are proportionate to what the purchase
  would actually cost this household.
- A removal suggestion is arguable: it quotes the user and shows the arithmetic, and the user
  decides.
- The numbers behind all of it are rulebook rows, and a drift test compares every delta by name —
  including the "no single answer decides it" property, checked against the rulebook rather than the
  engine, so weakening the rule fails the build.
- Schema 26 adds two tables to the archive, the demo wipe and the restore drill.
