---
name: add-rulebook-rule
description: Add or change a financial rule/threshold in the AI Personal CFO rulebook the correct way — as a versioned data row in ai/rules/rules-kb.json (never hardcoded in an engine), updated in rulebook.md, cited by ID, and covered by an evaluator test. Use when the user says "add a rule", "change a threshold", "update the rulebook", "RULE-", or wants to tune any financial heuristic (savings rate, EMI cap, allocation cap, cooling-off, etc.).
---

# Skill: Add / Change a Rulebook Rule

Financial thresholds are **data, not code** (§29). Engines evaluate rules generically and
cite them by ID in evidence ("flagged by RULE-EMI-40"). This skill keeps that invariant.

## Never do
- Never hardcode a financial number in an engine (`if (ratio > 0.40)`). The `0.40` lives in
  `params_json`.
- Never rename or delete a `rule_id` that may have been cited in a stored insight — deprecate
  with `enabled: false` instead (breaking traceability breaks AI-ARC-006).

## Steps
1. **Locate/choose the ID.** Reuse an existing `RULE-*` if changing it; else mint a new,
   descriptive, stable ID in the right `domain` (saving | investment | debt | protection | purchase).
2. **Edit `ai/rules/rules-kb.json`** — add/modify the row against the schema:
   `rule_id, domain, name, formula_id, params_json, severity, rationale, source_note,
   consumed_by, enabled, version`. Put *every* number in `params_json`. **Bump `version`** on
   any params change.
3. **Mirror it in `ai/rules/rulebook.md`** (the human table) so the doc and data agree.
4. **Wire the evaluator** if `formula_id` is new: implement `RuleEngine.evaluate(ruleId,
   FeatureSnapshot) -> RuleResult{pass|warn|fail, measuredValue, threshold, evidence, ruleId,
   version}`. Reuse an existing `formula_id` when the shape matches (e.g. `asset_class_cap`).
5. **Test:** a table-driven test that feeds a `FeatureSnapshot` and asserts pass/warn/fail at
   the boundary, just below, and just above the threshold — using the value from
   `params_json`, not a literal in the test.
6. **Governance:** note who/when/why in the commit body; the app records threshold changes in
   `audit_log` at runtime. Cite the rule ID in the commit: `feat(rules): tighten RULE-CC-UTIL to 30% (§29.4)`.

## Checklist
- [ ] Number lives in `params_json`, not in Kotlin.
- [ ] `version` bumped; ID stable (deprecate, don't rename).
- [ ] `rules-kb.json` (data) and `rulebook.md` (doc) agree.
- [ ] Boundary test passes; JSON still parses (`python -c "import json,glob; [json.load(open(f)) for f in glob.glob('ai/**/*.json',recursive=True)]"`).
- [ ] Every engine in `consumed_by` still cites `ruleId@version` in its evidence.
