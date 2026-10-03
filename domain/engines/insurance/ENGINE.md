# AI-INS — protection: the cover gap, the health floor, and the policies that are investments

**Module:** `:domain:engines:insurance` · **Version:** 1.0 · **Layer:** L4 · **SRS:** §39.1, §39.3
**Issue:** 13.3 · **ADR:** [ADR-0071](../../../docs/adr/0071-the-cover-gap-is-the-larger-of-two-readings-and-the-engine-never-says-surrender.md)
**Rules:** `RULE-TERM-10X`@1.1 · `RULE-HEALTH-COVER`@1.1 · `RULE-TERM-VS-ENDOW`@1.0 (rules-kb 1.24.0)

## Contract

```
ProtectionInput(household, todayIsoDate, nowUtcMillis, policies)
    -> Result<ProtectionAssessment, AppError>

ProtectionAssessment(
    term              CoverGap?                 null when there are no dependents — an answer, not a zero
    health            CoverGap                  always assessed
    investmentLinked  List<InvestmentLinkedPolicy>   dearest per lakh first
    provenance        EngineProvenance          cites all three rules WITH their versions
)

CoverGap(needed, existing, gap, basis, citation)   gap floored at zero
```

Pure (P-08): `todayIsoDate` and `nowUtcMillis` are inputs. No clock, no I/O, no Android (ARC-002).

## Formula

### The term cover — the larger of two readings (§39.1)

```
multiple   = income_multiple_min, OR income_multiple_max when the household has one income (§39.3)
byIncome   = multiple × annualIncome + outstandingLiabilities
byHlv      = irdaiMultiple(age) × annualIncome        25× 18–35 | 20× 36–45 | 15× 46–50 | 10× 51–60
needed     = max(byIncome, byHlv)                     `basis` says which won
gap        = max(needed − existing term cover, 0)
```

The two arms miss different households, which is why §39.1 takes the higher: the income multiple
under-covers a young earner with decades of income ahead, and the HLV multiple alone ignores the
loan somebody would inherit.

**No dependents → no assessment.** `null`, not a gap of zero: "you need none" and "you need some and
have it" are different statements.

**An age outside the four bands is refused**, not guessed. The rulebook has no multiple there.

### The health floor

`₹10L metro / ₹5L otherwise`, a floor rather than a target, sized against ~14% healthcare inflation.
Assessed for every household, dependants or not — a hospital bill does not ask who depends on you.

### The endowment detector (INS-002)

```
perLakh = annualPremium × 10 000 000 ÷ cover        paise per lakh of cover per year, HALF_EVEN
flagged = kind == OTHER && perLakh >= flag_premium_per_lakh_paise_min
```

**Only `OTHER` is examined.** A term or health policy the user labelled as such is never flagged,
however dear. The threshold (₹3,000/lakh/yr) also sits above real term pricing, which was measured
rather than assumed: term runs about ₹120 per lakh at age 30, ₹850 at 55, ₹1,400 at 60; endowments
and ULIPs run ₹8,000–10,000. Two defences against the one false positive that would cost the most
trust.

A policy with **zero cover** has no price per lakh and is not flagged — reporting a division by zero
as "infinitely expensive" would flag it on an arithmetic accident.

### The comparison, shown as math (P-07)

```
termEquivalent = (cover ÷ 1 lakh) × term_premium_per_lakh_paise_max
difference     = max(annualPremium − termEquivalent, 0)
invested       = FV(difference, equity_sip_return_bps, horizon)
policyLow/High = FV(annualPremium, endowment_return_bps_low/high, horizon)

FV = PMT × ((1 + r)^n − 1) ÷ r        ORDINARY annuity: paid at the END of each year
```

The ordinary-annuity convention is stated because the alternative gives a figure about 12% higher at
these rates, and silently choosing the flattering one is exactly what P-03 exists to prevent.
`BigDecimal` at `DECIMAL128`, rounded `HALF_EVEN` once at the end.

## Assumptions

- **The user says what kind a policy is.** The engine never infers it. A kind guessed wrong would
  put a genuine term plan on a "this is not insurance" list.
- **One earner's age.** §39.1's HLV multiple is per life; the engine assesses the life the cover is
  on, and a second earner is a second assessment.
- **Nothing about health, surrender value or tax already paid.** This is why there is no
  recommendation: those three decide whether acting on a flagged policy is sensible, and the app
  holds none of them.

## What it deliberately does not do (P-07)

There is **no output that recommends an action.** No "buy", no "surrender", no product ranking, no
insurer. The engine publishes a gap and an arithmetic comparison; the decision stays with the person
whose money it is. This is a property of the result types, not of a policy someone remembered to
follow — there is nowhere in `ProtectionAssessment` to put a recommendation.

**Vehicle cover is not here.** The issue's description mentions it; the SRS specifies adequacy for
life and health only, and AI-VEH already tracks vehicle insurance *renewal*. An IDV adequacy rule
would be a financial threshold with no source, which §6 forbids. ADR-0071 records it.

## Data it reads

`ai/rules/rules-kb.json` 1.24.0 — `RULE-TERM-10X`, `RULE-HEALTH-COVER`, `RULE-TERM-VS-ENDOW`. The
engine is pure and cannot read the file, so it reads the typed mirror in `ProtectionRules`;
`ProtectionRulebookDriftTest` fails the build the moment the two disagree, and also checks the rows
are still `enabled` and that the cited versions match.

## Tests

| Test | What it holds |
|---|---|
| `ProtectionGoldenTest` | 8 households against **`protection_oracle.py`**, an independent Python re-implementation reading the same rulebook. Plus a coverage assertion, so the fixture cannot be trimmed |
| `ProtectionMathTest` | The annuity's identities (monotonic in rate and horizon, never below contributions), its boundaries (zero rate, zero horizon, one year), the per-lakh discriminator incl. divide-by-zero, and the gap floor |
| `ProtectionEngineTest` | Every IRDAI band edge, the arm switch, §39.3's nudge, the four refusals, what is never flagged, the flag's exact threshold, versioned citations, determinism |
| `ProtectionRulebookDriftTest` | Every parameter of all three rows against the file, both directions, plus `enabled`, the cited versions, and that the flag threshold still sits above real term pricing |

## Version log

| Version | Issue | What changed |
|---|---|---|
| 1.0 | 13.3 | Created. Term gap on both arms of §39.1, health floor, endowment detector with the buy-term-invest-the-rest comparison. |
