<!--
  Why:  rules-kb.json is machine-readable but JSON cannot carry the narrative a
        reviewer or user needs to trust a threshold. This is the human face of the
        rulebook and the record of its governance.
  What: A readable catalogue of every RULE-* row, grouped by domain, plus the
        versioning/audit governance that keeps past insights reproducible.
  Result: Anyone can understand what each rule does and how to change it safely,
        without parsing JSON.
  Changelog:
    2026-07-17 — Created from SRS v1.7 §29 to accompany rules-kb.json.
-->

# Financial Rulebook & Heuristics Knowledge Base (RULE-KB)

The L3 Rules Engine ships a **named, transparent** rulebook of proven personal-finance
heuristics, adapted for India. **Every rule is a data row** (`rules-kb.json`), not
hardcoded logic. Engines evaluate rules generically, cite them by ID in evidence
("flagged by **RULE-EMI-40**"), and **every threshold is user-editable**. Defaults are
the floor; personal learned behaviour and user overrides refine them.

**Schema (`rules_knowledge_base`, §29.1)**

```
rule_id PK, domain, name, formula_id, params_json, severity, rationale,
source_note, user_override_json NULL, enabled INT, version, created_at, updated_at

evaluate: RuleEngine.evaluate(ruleId, FeatureSnapshot)
        -> RuleResult { pass|warn|fail, measuredValue, threshold, evidence, ruleId, version }
```

## §29.2 Saving & budgeting

| Rule ID | Definition (default) | Consumed by |
|---------|----------------------|-------------|
| RULE-50-30-20 | Needs ≤ 50%, wants ≤ 30%, savings ≥ 20% of income; auto-flexes to fixed load (metro 60/20/20) | Budget suggester, FHS |
| RULE-PAY-FIRST | Contributions scheduled on salary-credit day, not month-end | Goal engine, notifications |
| RULE-SAVE-RATE | Savings rate ≥ 20% good, ≥ 30% excellent, < 10% flag | FHS, monthly review |
| RULE-COOL-OFF | Discretionary buy > 1% of annual income → Purchase Advisor + optional 24h cool-off | Purchase Advisor |
| RULE-EMERG-FIRST | No goal below Emergency Fund funded while runway < 3 months | Goal waterfall, AI-FOO |
| RULE-LIFESTYLE | Income up ≥ 10% → suggest ≥ 50% of the raise to savings | Insight feed |

## §29.3 Investment

| Rule ID | Definition (default) | Consumed by |
|---------|----------------------|-------------|
| RULE-AGE-EQUITY | Equity band = (100 − age)% ±10pp (110 − age for Growth) | Allocation analysis |
| RULE-5-25 | Rebalance on 5pp absolute or 25% relative drift | Rebalancing signal |
| RULE-GOLD-CAP | Gold ≤ 10% of portfolio | Diversification |
| RULE-CRYPTO-CAP | Crypto ≤ 5% of portfolio | Diversification |
| RULE-HORIZON | < 3y → savings/FD/debt; 3–5y → hybrid; > 5y → equity-eligible | Goal funding buckets |
| RULE-SIP-STREAK | Missed SIP month → insight with long-term cost | SIP consistency |
| RULE-IDLE-CASH | Liquid > (STS needs + buffer) for 60 days → deploy prompt | Idle-cash detector, AI-MKT gate |
| RULE-CONC-15-70 | Single holding ≤ 15%, single class ≤ 70% | Diversification score |

## §29.4 Debt & big-purchase

| Rule ID | Definition (default) | Consumed by |
|---------|----------------------|-------------|
| RULE-EMI-40 | Obligations (EMIs + rent) ≤ 40% of income; hard fail at 50% | Purchase Advisor gate 3, FHS |
| RULE-20-4-10 | Vehicle: ≥ 20% down, loan ≤ 4y, transport cost ≤ 10% | Purchase Advisor (vehicle) |
| RULE-HOME-EMI | Home: EMI ≤ 35%; price ≤ 5× annual income; rent ≤ 30% | Purchase Advisor (property) |
| RULE-CC-UTIL | Card utilisation ≤ 30%; never revolve (severe) | FHS debt pillar, card alerts |
| RULE-PAYOFF-ORDER | Avalanche vs snowball with interest delta | Debt simulator |
| RULE-PREPAY-VS-INVEST | Loan rate vs after-tax expected return; show breakeven | Loan simulator, AI-FOO |

## §29.5 Protection

| Rule ID | Definition (default) | Consumed by |
|---------|----------------------|-------------|
| RULE-TERM-10X | Term cover ≈ 10–15× annual income if dependents | FHS protection pillar |
| RULE-HEALTH-COVER | Health cover ≥ ₹5–10L per family (metro 10L) | FHS protection pillar |
| RULE-RUNWAY-M | Emergency runway ≥ personal multiplier M (§10) | Emergency coach, AI-MKT gate |

## Governance (binding)

- Rules are **versioned**. Changing a threshold records **who / when / why** in
  `audit_log`.
- Engines always cite **`ruleId` + `version`**, so past insights stay reproducible after
  a threshold changes (AI-ARC-006).
- Defaults here are the **floor**; a user override in `user_override_json` beats the
  default and is itself a training signal.
- To retire a rule, set `enabled: false` and keep the row — **never delete or rename an
  ID** that has been cited in a stored insight.

## Related

- Machine-readable rows: [`rules-kb.json`](./rules-kb.json)
- Priority waterfall that gates these rules: [`financial-order-of-operations.json`](./financial-order-of-operations.json)
