<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR, and 13.3's AC2 asks for
        one explicitly.
  What: issue 13.3 — why the cover gap takes the larger of §39.1's two arms, why the endowment
        detector reads price per lakh and has two independent defences against one false positive,
        why there is no recommendation anywhere in the result, and why vehicle cover is absent.
  Result: a reader can see why an age outside the IRDAI bands is refused rather than estimated, and
          why the most alarming number this app can produce is behind a flag.
  Changelog: 2026-10-03 — Created.
-->

# ADR-0071 — The cover gap is the larger of two readings, and the engine never says "surrender"

**Status:** Accepted · **Date:** 2026-10-03 · **Issue:** 13.3 · **SRS:** §39.1, §39.3 · **Rules:** P-02, P-03, P-07, P-08, MNY-001, MNY-002

## Context

§14's financial health score has had a Protection pillar since issue 9.4, and it has been scored off
**self-declared booleans** — "do you have health insurance? yes/no". A household with a ₹3 lakh
health policy and a ₹2 crore home loan answers yes to both questions and is not protected at all.

§39.1 exists to replace those ticks with a number, and it is unusually specific for this SRS:

```
termCoverNeeded = max( 10–15 × annual income + outstanding liabilities,
                       HLV by IRDAI age multiplier: 25× (18–35), 20× (36–45),
                                                    15× (45–50), 10× (51–60) )
coverGap        = termCoverNeeded − existing term cover      // the headline number
healthCoverFloor = ₹10L base per metro family + super top-up, sized against ~14% healthcare inflation
```

plus INS-002, an endowment/ULIP detector that shows the buy-term-invest-the-rest comparison "as
math, decision stays with the user".

Two rulebook rows already existed and already named `AI-INS` in their `consumed_by`:
`RULE-TERM-10X` and `RULE-HEALTH-COVER`, both at 1.0 and both carrying only the coarse version of
the rule.

## Decision

### 1 · Extend the existing rows rather than mint new ones

`RULE-TERM-10X` → **1.1** gains the IRDAI bands, the liabilities flag and §39.3's single-income
nudge. `RULE-HEALTH-COVER` → **1.1** gains the healthcare inflation it is sized against. No existing
threshold changed value, which is why both are minor bumps rather than new rules: the ids are cited
in stored insights, and renaming one breaks traceability (AI-ARC-006). Only INS-002's detector is
genuinely new, as `RULE-TERM-VS-ENDOW`.

The id deliberately avoids the `RULE-INS-*` prefix: that is already taken by the **insight** domain
(`RULE-INS-RANK`, `RULE-INS-DEDUP`), and two meanings of INS in one rulebook is a trap for whoever
greps it next.

### 2 · The gap is the larger of two readings, and the engine says which

§39.1's `max` is the whole design. The two arms miss different households:

- the **income multiple** under-covers a young earner with decades of income ahead;
- the **HLV multiple** alone ignores the loan somebody would inherit.

Taking the larger means a household is covered against whichever reading of its own future is worse.
`CoverBasis` reports which arm won, because "fifteen times your income plus the home loan" and "the
IRDAI multiple for your age" lead to different conversations, and a bare rupee figure leads to
neither (P-02).

**An age outside the four bands is refused, not estimated.** §39.1 gives bands for 18–60 and no rule
beyond them. A 16- or 70-year-old would need a multiple the rulebook does not have, and an invented
one would look exactly as authoritative as a real one (P-03).

**No dependents means no assessment at all** — `null`, not a gap of zero. "You need none" and "you
need some and have it" are different statements, and a zero would say the second.

### 3 · The detector reads price per lakh, and has two independent defences

Comparing premiums tells you nothing, because a big policy costs more. **Premium per lakh of cover
per year** is the discriminator, and the threshold was set by measuring rather than guessing:

| Policy | ₹ per lakh of cover per year |
|---|---|
| Term, age 30, ₹1Cr | 120 |
| Term, age 45, ₹1Cr | 300 |
| Term, age 55, ₹1Cr | 850 |
| Term, age 60, ₹50L | 1,400 |
| Endowment, ₹10L | 8,000 |
| ULIP, ₹12L | 10,000 |

There is a wide gap, and **₹3,000 sits in it** — above any genuine term plan including an older
person's, and well below any investment-linked policy.

That false positive is the one that would cost the most trust, so there are **two** defences and
they are independent: the threshold above, and a filter that examines **only policies the user
labelled `OTHER`**. A term or health policy is never flagged, however dear. The engine never infers
a policy's kind.

A policy with **zero cover** returns `null` rather than a division by zero: flagging something as an
investment on the strength of an arithmetic accident is the kind of fabricated conclusion P-03
exists to prevent.

### 4 · Advisory is a property of the types, not a rule someone remembers

P-07 says the app recommends and the user decides. Here that is enforced structurally: there is
**nowhere in `ProtectionAssessment` to put a recommendation.** No verdict field, no "action"
enum, no product ranking, no insurer. The engine publishes a gap and the workings of a comparison.

That is not fastidiousness. Whether acting on a flagged endowment is sensible depends on its
surrender value, the tax already paid on it, and the holder's health since they bought it — three
facts the app does not hold and cannot get. An engine that said "surrender this" would be giving
advice on inputs it never saw.

The comparison itself is computed, never quoted: §39.1 says the difference "SIP-ed at ~12% compounds
to ₹1.5–2.7Cr over 30 years", and the engine's own arithmetic on a ₹66,000 difference gives
**₹1,59,27,957**, inside that band. Two independent routes to the same claim.

The future value is an **ordinary annuity** — payment at the end of each year. Stated, and tested,
because an annuity-due gives a figure about 12% higher at these rates, and silently choosing the
flattering convention is precisely what P-03 is for.

### 5 · The flag holds back a sentence, not just a screen

`ProtectionMode.IS_ENABLED` is `false`. The usual Epic 13 reason applies — no repository, no screen
— but there is a second one specific to this engine. **"You are ₹3.5 crore under-insured" is the
most alarming sentence this app can produce**, and §39.1 specifies a calculation while specifying
nothing about how the result is said. Shipping the number before someone has designed the wording
would be shipping the alarm without the explanation.

Before it goes on: a repository, a screen with wording somebody has thought about, and the decision
to let AI-FHS's Protection pillar read real figures instead of the booleans it scores today — which
is the change that makes this engine worth having.

## Consequences

- `rules-kb.json` moves 1.23.0 → **1.24.0**, and all 14 engine mirrors restate the version. None of
  their rows changed; the drift tests confirm it.
- The FHS Protection pillar still reads self-declared booleans. Replacing them is the point of §39.1
  and is **not** done here: it changes a shipped score's inputs, which deserves its own issue and
  its own look at what happens to a user's history when the score moves.

## Vehicle cover is absent, deliberately

The issue's description says "life/health/vehicle coverage adequacy". §39.1 specifies **life and
health**; nothing in the SRS says what adequate vehicle cover is. AI-VEH already tracks vehicle
insurance *renewal* (cadence, reminder, and a premium only where the user recorded one).

An IDV adequacy rule would need a threshold — "cover should be within X% of market value" — with no
source in the blueprint. §6 forbids hardcoding a financial number, and inventing a rulebook row has
the same problem one layer over: the row would carry a figure nobody can point at. Recorded here as
out of scope until the SRS or the user supplies the rule.

## Alternatives considered

- **Infer a policy's kind from its price.** Rejected, §3: the inference would be right most of the
  time, and the times it was wrong would be an older person's genuine term cover on a list headed
  "these are not insurance".
- **Report a negative gap when over-covered.** Rejected: it reads as credit, and invites netting a
  life-cover surplus against a health shortfall. A large term policy does not pay a hospital bill.
- **Mint `RULE-INS-001`/`RULE-INS-002` to match the SRS's own ids.** Rejected, §1: `RULE-INS-*`
  already means *insight* in this rulebook.
- **Include a recommendation field, defaulted to "none".** Rejected, §4: a field that exists gets
  filled in eventually, and the structural guarantee is worth more than the convenience.
