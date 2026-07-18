<!--
  TEMPLATE — copy to :domain:engines:<name>/ENGINE.md and fill in.
  §21.6 requires every engine module to carry this doc: contract, formula, assumptions,
  version log. It is the human-readable companion to the code and is reviewed with it.
  Delete this comment and the guidance in <angle brackets> when you fill it in.
-->

# <Name>Engine — <AI-XXX>

**SRS:** §<NN>  ·  **Pipeline layer:** L<?>  ·  **Module:** `:domain:engines:<name>`
**Version:** <x.y>  ·  **Status:** <draft | active>

## Why this engine exists
<The problem it solves, in one paragraph. What downstream engines/screens depend on it.>

## Contract
```
interface <Name>Engine {
    fun compute(input: <Name>Input): Result<<Name>Result, AppError>
}
```
- **Input** — `<Name>Input`: <each field, meaning, format/unit — money in paise, rates in bps, dates ISO>
- **Output** — `<Name>Result`: <each field, meaning, format> + `provenance`
  (`engineId, engineVersion, inputWindow, computedAt, confidence, evidence`).

## Formula / algorithm
<The exact math or decision logic, copied faithfully from the SRS section. Show the
thresholds and where they come from — e.g. "reads RULE-EMI-40 from ai/rules/rules-kb.json".>

## Assumptions & guardrails
- <e.g. money is Long paise; rates bps; clock injected; randomness seeded>
- <cold-start behaviour with insufficient data; how it is labelled to the user>
- <what it explicitly does NOT do (P-07: advice not orders; P-03: it produces numbers, not prose)>

## Rules / knowledge consumed
| ID / file | What it provides |
|-----------|------------------|
| RULE-XXX (`ai/rules/rules-kb.json`) | <threshold> |
| `ai/knowledge/<file>.json` | <priors / config> |

## Evidence shown to the user (P-02)
<What appears in the reasoning card: which inputs, which rule fired, the plain-language line.>

## Tests
- Golden-file cases: <list the snapshots — realistic, edge/cold-start, boundary>.
- Property tests: <identities that must always hold>.
- Determinism: <seed → identical output>.
- Coverage: engine ≥ 85%, money math 100%.

## Version log
| Version | Date | Change |
|---------|------|--------|
| 1.0 | <YYYY-MM-DD> | Initial implementation from SRS §<NN>. |
