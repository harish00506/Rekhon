<!--
  Why:  CLAUDE.md §10 — one session file per working session, holding the full reasoning the root
        records only point at.
  What: issue 13.3 — AI-INS, why the gap takes the larger of two readings, how the endowment
        threshold was measured, and the reformatting mistake a test caught.
  Result: a reader can see why an age outside the IRDAI bands is refused, why there is nowhere in
          the result to put a recommendation, and why checked data is edited as text.
  Changelog: 2026-10-03 — Created.
-->

# 2026-10-03 — The Protection Suite (issue 13.3)

Branch `feature/13-3-insurance-protection-suite-ai-ins` off `dev` (`9cfe60d`). Version 0.13.1 →
**0.13.2** (versionCode 66). Rulebook **1.23.0 → 1.24.0**. Schema unchanged.

---

## 1 · Decisions this session

### 1.1 The gap is the larger of two readings, and the engine says which

§14's Protection pillar has been scored off **self-declared booleans** since issue 9.4 — "do you
have health insurance? yes/no". A household with a ₹3 lakh health policy and a ₹2 crore home loan
answers yes to both and is not protected at all. §39.1 exists to replace the ticks with a number.

```
termCoverNeeded = max( 10–15× income + outstanding liabilities,
                       IRDAI HLV multiple × income )       25× 18–35 | 20× 36–45 | 15× 46–50 | 10× 51–60
```

The `max` is the design. The two arms miss different households: the income multiple under-covers a
young earner with decades of income ahead; the HLV multiple alone ignores the loan somebody would
inherit. Taking the larger covers a household against whichever reading of its own future is worse,
and `CoverBasis` reports which arm won — because "fifteen times your income plus the home loan" and
"the IRDAI multiple for your age" lead to different conversations, and a bare rupee figure leads to
neither (P-02).

Two answers are deliberately **not numbers**: an age outside §39.1's four bands is **refused**, not
estimated (the rulebook has no multiple there, and an invented one would look exactly as
authoritative as a real one); and no dependants means **no assessment**, `null` rather than a gap of
zero, because "you need none" and "you need some and have it" are different statements.

### 1.2 The endowment threshold was measured, not guessed — and my first draft was wrong

INS-002 asks for insurance-cum-investment policies to be flagged. Comparing premiums tells you
nothing, because a big policy costs more; **premium per lakh of cover per year** is the
discriminator. My first draft put the threshold at ₹500/lakh. Then I measured:

| Policy | ₹ per lakh of cover per year |
|---|---|
| Term, age 30, ₹1Cr | 120 |
| Term, age 45, ₹1Cr | 300 |
| Term, age 55, ₹1Cr | 850 |
| Term, age 60, ₹50L | 1,400 |
| Endowment, ₹10L | 8,000 |
| ULIP, ₹12L | 10,000 |

₹500 would have flagged a 55-year-old's **genuine term plan** as "not insurance" — the one false
positive here that would cost trust in everything else the app says. The threshold moved to ₹3,000,
which sits in the gap.

That measurement is also why there are **two independent defences**, not one: the threshold, and a
filter that examines only policies the user labelled `OTHER`. The engine never infers a policy's
kind, so a term plan cannot be flagged even if pricing moves.

### 1.3 Advisory is a property of the types

P-07 says the app recommends and the user decides. Here it is structural: there is **nowhere in
`ProtectionAssessment` to put a recommendation.** No verdict field, no action enum, no product
ranking, no insurer.

That is not fastidiousness. Whether acting on a flagged endowment is sensible depends on its
surrender value, the tax already paid on it, and the holder's health since they bought it — three
facts the app does not hold and cannot get. An engine that said "surrender this" would be advising on
inputs it never saw. A field defaulted to "none" was considered and rejected: a field that exists
gets filled in eventually.

The comparison is **computed, never quoted**. §39.1 says the difference "SIP-ed at ~12% compounds to
₹1.5–2.7Cr over 30 years"; the engine's arithmetic on a ₹66,000 difference gives **₹1,59,27,957**.
Two independent routes to the same claim. The future value is an **ordinary** annuity — payment at
the end of each year — stated and tested, because an annuity-due reads about 12% higher and silently
choosing the flattering convention is exactly what P-03 exists to prevent.

### 1.4 The flag holds back a sentence, not just a screen

The usual Epic 13 reason applies — no repository, no screen. There is a second one specific to this
engine: **"you are ₹3.5 crore under-insured" is the most alarming sentence this app can produce**,
and §39.1 specifies a calculation while specifying nothing about how the result is said. Shipping
the number before someone has designed the wording would be shipping the alarm without the
explanation.

---

## 2 · The mistake, and what caught it

### 2.1 Never re-serialise a checked data file to edit it

I edited `ai/rules/rules-kb.json` by loading it with `json.load`, changing three rules, and writing
it back with `json.dumps(indent=2)`. That reformatted **all fifty untouched rules** — Python expands
inline arrays across lines — producing a diff of **570 insertions and 121 deletions to carry a
three-rule change**.

It also broke a test. `PurchaseRulebookDriftTest` asserts `"[5, 10]" in row`, reading the file's own
compact style, and after reformatting the array was three lines. The test was right to fail: the
file it checks had changed in a way nobody reviewed.

Reverted; redone as **textual edits** preserving the file's own formatting. The diff is now 21
insertions and 8 deletions.

**The rule: edit checked data as text.** A serialiser round-trip is not a no-op, and a huge diff
hides the small change inside it.

### 2.2 And my own drift parser had the mirror-image bug

Fixing the formatting then broke *my* new test, whose `arrayIn` helper assumed the **multi-line**
form — the same class of mistake one layer over. Rewritten to count brackets, so it reads numbers
rather than whitespace. A drift test that fails on formatting is a drift test that will be
"fixed" by reformatting.

### 2.3 A bug found by reading, not by a test

`coverOf` read a `householdPolicies` field that was **never assigned**. Existing term cover was
always zero, so every gap was overstated by whatever the household already held — and it compiled,
and the golden file had not been written yet. Caught by re-reading the engine before writing the
tests. The field is gone; cover is read from the policies passed in.

---

## 3 · Flow changed this session

**None.** AI-INS is pure, has no entity and no repository, and `ProtectionMode.IS_ENABLED` is false,
so nothing reaches it — not even the backup, which is what distinguished 13.1 and 13.2. `FLOW.md`
records the absence rather than leaving a reader to look for the box.

---

## 4 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/rules/rules-kb.json` | 1.24.0: `RULE-TERM-10X`→1.1 (IRDAI bands, liabilities, §39.3 nudge), `RULE-HEALTH-COVER`→1.1 (healthcare inflation), `RULE-TERM-VS-ENDOW` added |
| `ai/rules/rulebook.md` | The human table mirrors all three |
| `domain/engines/insurance/` | **New module.** `Protection.kt` (contract + `ProtectionMode`), `ProtectionRules.kt` (the mirror), `ProtectionMath.kt` (gaps, per-lakh, the annuity), `RuleProtectionEngine.kt` |
| `…/insurance/src/test/resources/golden/protection_oracle.py` | **New.** The independent Python oracle |
| `…/insurance/src/test/…/Protection{Golden,Math,Engine,RulebookDrift}Test.kt` | **New.** 45 tests |
| `domain/engines/insurance/ENGINE.md` | **New.** Contract, formula, assumptions, what it deliberately does not do |
| 14 × `*Rules.kt` | `RULEBOOK_VERSION` restated 1.23.0 → 1.24.0; no mirrored row changed |
| `ai/orchestrator/engine-registry.yaml` | AI-INS added |
| `docs/adr/0071-*.md` | The ADR |

---

## 5 · Quiz

1. **Why does the cover gap take the larger of two formulas?** Each misses a different household —
   the income multiple under-covers the young, HLV ignores inherited debt.
2. **Why is an age of 70 refused rather than assessed?** §39.1's bands stop at 60; a multiple
   invented beyond them would look exactly as authoritative as a real one.
3. **What are the two defences against flagging a genuine term plan?** The threshold sits above real
   term pricing at every age, and only policies the user labelled `OTHER` are examined at all.
4. **Why can the engine not recommend surrendering a policy?** Surrender value, tax already paid and
   health since purchase decide that, and the app holds none of them — so there is nowhere in the
   result type to put a recommendation.
5. **What is wrong with editing `rules-kb.json` through `json.dumps`?** It reformats every untouched
   rule, hides the real change in a 570-line diff, and breaks drift tests that read the file's own
   style. Edit checked data as text.
