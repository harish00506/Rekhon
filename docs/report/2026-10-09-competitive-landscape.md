<!--
  Why:  Asked for on 2026-10-09: how this app compares with what already exists, what is genuinely
        new in it, and which product category it belongs to.
  What: A competitive analysis of AI Personal CFO (Rekhon) against the Indian, global and
        open-source personal-finance landscape, with pros, cons, novelty and sources.
  Result: A reader can place the product, see what it has that others do not, and see honestly
          where it is behind.
  Changelog: 2026-10-09 — Created.
-->

# AI Personal CFO (Rekhon) — competitive landscape

**Version analysed:** `0.13.4` · **Schema:** v31 · **Date:** 2026-10-09
**Status:** pre-release. Everything below describes `dev`; **nothing has been promoted to `stage`
or `main`, and there are no release tags.** This is an analysis of a codebase, not of a shipped
product.

---

## 1 · Which category does this app lie in?

It sits in more than one taxonomy, and the honest answer differs by axis.

| Axis | Category | Notes |
|---|---|---|
| **App store** | **Finance** (Google Play) | Not "Business" — it manages a person's money, not a company's books |
| **Product class** | **PFM — Personal Financial Management** | The broad class containing Mint, YNAB, Monarch, Money View |
| **Sub-class** | **Local-first / privacy-first PFM with a deterministic advisory layer** | The distinguishing pair: data never leaves the device by default, and advice is computed, not generated |
| **Positioning** | **"Personal CFO" / financial co-pilot** | Advisory, not just record-keeping — it forecasts, scores, ranks and recommends |
| **Market** | **India-first** | Not a localised Western app: UPI/SMS ingest, GST, the two tax regimes, FOIR, SGB, 50/30/20 with a metro flex |

**What it is *not*:** a neobank (holds no money), a brokerage or investment platform (executes no
trades), a robo-advisor (manages no portfolio), a lender, or accounting software. It also does not
auto-execute anything — [`CLAUDE.md` §1, P-07](../../CLAUDE.md) states "advice, never orders",
and that is enforced structurally rather than by policy (see §4.2).

The nearest honest one-line description: **an offline, on-device personal CFO for India that
computes its advice and shows its working.**

---

## 2 · The comparison set

Four groups compete for the same user, in different ways.

### 2.1 Indian PFM and money apps

| App | What it is | Model |
|---|---|---|
| **Money View** | Expense tracking via bank/SMS sync, budgets, plus a lending business | Free app, revenue from loans |
| **ET Money** | Expense tracking bolted to mutual funds, insurance, gold, personal loans | Free app, revenue from distribution |
| **INDmoney** | Investment aggregator that added budgeting; pulls accounts via the Account Aggregator framework | Free app, revenue from brokerage/distribution |
| **Walnut** | The SMS-reading expense tracker most Indians remember | **Dead.** Acquired by Paytm in 2021 and shut down |
| **Jupiter / Fi** | Neobanks with PFM features attached to their own account | Banking revenue |

The pattern is unmistakable and matters: **in India, the PFM app is almost always a customer
acquisition channel for a financial product.** The tracker is free because the loan, the fund or
the brokerage account is the business. ([IIFL](https://www.iifl.com/index.php/blogs/personal-finance/money-management-aaps-in-india))

### 2.2 Global PFM

| App | Price (2026) | Note |
|---|---|---|
| **Mint** | — | **Shut down 23 March 2024**, pushing ~3.6M users to alternatives |
| **Monarch Money** | $99.99/yr (Core), $199/yr (Plus) | Founded by Mint's first PM; the mainstream successor |
| **YNAB** | $99/yr | Zero-based budgeting; a method more than a tracker |
| **Copilot Money** | ~$95/yr | iOS-first, design-led |
| **Rocket Money** | $7–14/mo | Subscription cancellation is the hook |

([waypointbudget](https://waypointbudget.com/blog/mint-alternative-2026), [getfinny](https://getfinny.app/blog/best-mint-alternatives-2026))

**None of these operate in India**, and all are subscription SaaS with server-side data.

### 2.3 Open-source / self-hosted

| Tool | Shape |
|---|---|
| **Firefly III** | Double-entry bookkeeping, self-hosted web app, deep reporting, rules-based categorisation |
| **Actual Budget** | Envelope budgeting, local-first with optional end-to-end encrypted sync |

These are the only group that shares this app's privacy stance — and both are **web apps you must
host yourself**, which is a materially different product from an Android app that works out of the
box. ([beancount.io](https://beancount.io/blog/2026/07/26/firefly-iii-vs-actual-budget-self-hosted-open-source-budgeting-guide))

### 2.4 AI financial assistants

| App | Approach |
|---|---|
| **Cleo** | Conversational AI money coach, Gen-Z tone; >1M paid subscribers, >$250M ARR |
| **Origin** | AI advisor; its model scored **98.3% on the CFP® exam** vs GPT-5's 93.8% |
| **PortfolioPilot / Wealthfront** | Portfolio-centric AI advice |

([useorigin](https://useorigin.com/resources/blog/best-ai-financial-advisor-apps-in-2026-xen56), [omidsaffari](https://omidsaffari.com/blog/best-ai-financial-advisors), [sacra](https://sacra.com/c/cleo))

All are **cloud LLM** products: your financial data goes to a server and a language model reasons
over it.

---

## 3 · Head-to-head

| | **This app** | Money View / ET Money / INDmoney | Monarch / YNAB / Copilot | Firefly III / Actual | Cleo / Origin |
|---|---|---|---|---|---|
| Works fully offline | **Yes** (P-04, enforced by test) | No | No | Self-hosted | No |
| Data leaves device by default | **No** (P-01) | Yes | Yes | Your server | Yes |
| Account/login required | **No** — device + biometric is the identity | Yes | Yes | Self-hosted | Yes |
| India-specific logic | **Deep** | Yes | No | No | No |
| Advice is *computed*, not generated | **Yes** (P-03) | Partial | Rules only | No advice | **No — LLM-generated** |
| Every figure shows its rule + inputs | **Yes** (P-02, provenance on every result) | No | No | No | Rarely |
| Business model conflict | **None** — sells nothing | **Yes — lending/distribution** | Subscription | None | Subscription |
| Install-and-go | **Yes** | Yes | Yes | **No** | Yes |
| Shipped to users | **No** | Yes | Yes | Yes | Yes |

---

## 4 · What is genuinely new here

These are the things I could not find a direct equivalent for in any of the four groups. Each is
cited to the code or decision record that implements it.

### 4.1 "Numbers from math, words from AI" — enforced, not promised

The binding rule **P-03** ([`CLAUDE.md` §1](../../CLAUDE.md)) says an LLM never computes a figure;
deterministic engines produce every number and the model only verbalises. What makes this more than
a slogan is **AI-ARC-004**, a numeric guardrail every generated sentence must pass before display
([`ai/chat/guardrail.md`](../../ai/chat/guardrail.md), implemented as
[`:domain:engines:guardrail`](../../domain/engines/guardrail)):

> an LLM will happily emit a confident wrong number. The guardrail is the gate that makes P-03
> enforceable rather than aspirational.

A reply either contains only figures traceable to an engine result, or it is refused.

**Why this is novel:** the entire AI-finance category (§2.4) is built the other way round — the
model reasons over your data and states the result. Origin's headline metric is a *CFP exam score*,
which is a measure of how often the model is right. This app's architecture makes "how often is the
model right about numbers?" an irrelevant question, because the model is never the source of one.

### 4.2 Guarantees that are structural rather than remembered

Repeatedly, a rule is enforced by making the wrong thing *impossible to express*:

| Guarantee | How it is enforced | Reference |
|---|---|---|
| Advice never becomes an order (P-07) | `ProtectionAssessment` and `TaxEstimate` have **no field** for a recommendation or an action | [ADR-0071 §4](../adr/0071-the-cover-gap-is-the-larger-of-two-readings-and-the-engine-never-says-surrender.md), [ADR-0072 §3](../adr/0072-the-tax-kb-had-no-slabs-and-an-estimate-must-say-what-it-left-out.md) |
| Tax alerts never name an instrument (TAX-001) | `TaxAlert` has no field for one; a test attaches real fund names and asserts none appears | [ADR-0072 §3](../adr/0072-the-tax-kb-had-no-slabs-and-an-estimate-must-say-what-it-left-out.md) |
| No query may cross profiles | `ProfileScopingTest` reads every `@Query` and fails the build unless it is profile-filtered, id-keyed, or carries a `// DEVICE-WIDE:` marker **with a written reason** | [ADR-0069 §2](../adr/0069-household-is-a-row-above-the-profile-and-aggregation-composes-scoped-reads.md) |
| Money is never a float | A custom lint rule (`CfoMoneyAsFloatingPoint`) fails the build | [ADR-0001](../adr/0001-custom-lint-module-and-money-heuristic.md) |
| PII never reaches a log | `CfoPiiInLogs` fails the build | [`CLAUDE.md` §5](../../CLAUDE.md) |

I am not aware of another consumer finance product that enforces its advisory posture at the level
of **type definitions and build-failing lint**.

### 4.3 An estimate that states what it left out

[ADR-0072 §2](../adr/0072-the-tax-kb-had-no-slabs-and-an-estimate-must-say-what-it-left-out.md)
introduces `TaxLimitation`, carried **on the result beside the figure**. Above ₹50L of taxable
income the tax estimate says it is understated, because surcharge and marginal relief are not
modelled.

> P-03 is usually read as "never invent a number". This is its other half: **never hide that a
> number is incomplete.**

Every competitor either models a thing or silently omits it. Shipping the omission *as part of the
answer* is, as far as I can tell, unique here.

### 4.4 Financial thresholds are versioned data, not code

51 rules live in [`ai/rules/rules-kb.json`](../../ai/rules/rules-kb.json) (v1.24.0), each with an
id, params, rationale, source and version; knowledge bases cover tax, vehicles, appliances,
classification and market signals. Every engine result cites the rule **and the version** it used,
so a stored insight stays reproducible after a Budget changes a rate (AI-ARC-006).

Firefly III has rules-based categorisation, but not a versioned, cited financial-policy layer.

### 4.5 Crypto-shredding erase

SQLCipher + Android Keystore, with erase implemented as **key destruction before file deletion**,
and a device test that scans every byte the app owns for canary values
([`EraseRepository`](../../data/repository/src/main/kotlin/com/aicfo/data/repository/EraseRepository.kt),
issue 11.4). Most apps' "delete my data" is a server-side request you must trust.

### 4.6 India-specific depth that Western apps structurally lack

- Both tax regimes with full slab tables, the §87A rebate **cliff**, 4% cess, and a break-even
  shown in rupees — plus the finding that at ₹18L income, ₹4.25L of maxed deductions **still loses**
  to the new regime by ₹67,600 ([ADR-0072 §4](../adr/0072-the-tax-kb-had-no-slabs-and-an-estimate-must-say-what-it-left-out.md))
- Term-cover gap on the **larger** of an income multiple and the IRDAI HLV band; an
  endowment/ULIP detector whose threshold was measured against real premium-per-lakh pricing
  ([ADR-0071](../adr/0071-the-cover-gap-is-the-larger-of-two-readings-and-the-engine-never-says-surrender.md))
- On-device SMS bank-alert parsing as the ingest path, with consent that is device-wide and revocable
- Indian digit grouping (₹1,23,456.78), four languages, 50/30/20 with a metro flex

### 4.7 A development process that produces evidence

72 ADRs, one session file per working session, mutation testing on every gate, and golden files
generated by **independent Python oracles** so the expected values never come from the code under
test ([ADR-0064](../adr/0064-the-golden-harness-refuses-to-be-vacuous-and-never-rewrites-its-own-fixture.md)).
This is not a user-facing feature, but it is why the claims above can be checked rather than taken
on trust.

---

## 5 · Pros

1. **No business-model conflict.** It sells nothing. Every Indian competitor in §2.1 monetises by
   distributing loans, funds or insurance — which is a standing incentive to advise in a direction.
   This app cannot, because there is nothing to sell.
2. **Privacy is the default, not a setting.** Against a market where
   [Incogni found 60% of 20 popular budgeting apps share data with third parties, and 1 in 4 share
   *financial* data](https://blog.incogni.com/?p=10157), "nothing leaves the device unless you turn
   it on per feature" is a strong and increasingly saleable position.
3. **Regulatory timing is favourable.** India's DPDP Act obligations — consent, retention, erasure,
   breach notice — bind in full by **May 2027**
   ([India Briefing](https://www.india-briefing.com/news/india-dpdp-compliance-gdpr-comparison-45702.html)).
   This app already has a DPDP traceability matrix, a per-feature consent ledger with grant/revoke
   timestamps, and a working erase ([`docs/compliance/dpdp-2023.md`](../compliance/dpdp-2023.md),
   issue 11.5). Most incumbents will be retrofitting.
4. **It cannot hallucinate a number.** See §4.1. In a category whose main risk is a confident wrong
   figure, this is the strongest single differentiator.
5. **It explains itself.** Every recommendation carries its inputs, the rule that fired and its
   version (P-02). Competitors show a number; this shows the derivation.
6. **Genuine India depth.** The tax, insurance and classification logic is not localisation — it is
   built from Indian rules first.
7. **Works with no connectivity and no account.** A real advantage in India, and it removes the
   signup step that kills activation funnels.
8. **Architecturally ready for iOS.** All 30 engines are pure Kotlin with no Android imports
   (ARC-002), so a KMP port moves UI only.

---

## 6 · Cons and risks

Stated plainly, because an analysis that only lists strengths is not useful.

1. **It has never shipped.** Version 0.13.4 on `dev`, ~34 issues ahead of `stage`, **zero release
   tags, nothing ever promoted**. Every claim here is about a codebase. Competitors have millions
   of users.
2. **The "AI" is engines and rules, not a language model — today.** The chat verbaliser that ships
   is the **template** one; the seam for `on-device` and `cloud` models exists but is unfilled
   ([`Chat.kt`](../../domain/engines/chat/src/main/kotlin/com/aicfo/domain/engines/chat/Chat.kt)).
   This is defensible (it is why nothing can hallucinate) but it means the conversational experience
   is not yet competitive with Cleo, and the product name promises something not fully delivered.
3. **No bank connectivity.** No Account Aggregator integration — issue 13.6 is still Todo. Ingest is
   SMS parsing and manual entry, while INDmoney pulls full account history through AA. **This is the
   single biggest functional gap**, and it directly undercuts the "complete picture" promise.
4. **Manual entry is a retention risk.** Every PFM product that relies on the user entering data
   loses to one that syncs. The offline-first stance and the sync-convenience expectation are in
   genuine tension, and the app currently resolves it in favour of privacy.
5. **Five Epic 13 features are built but switched off** — household, appliances, insurance, tax and
   business modes all have `IS_ENABLED = false`. They are real engines with real tests and no UI.
   Until they are wired, they are cost without user value.
6. **A known blocker to multi-profile.** 14 id-keyed DAO queries are safe only because one profile
   exists; they must constrain `profile_id` before household or business mode can be turned on
   ([ADR-0069 §5](../adr/0069-household-is-a-row-above-the-profile-and-aggregation-composes-scoped-reads.md)).
7. **Two SRS forward-compatibility promises were never kept**, one of which loses data daily: the
   OCR extracts a GST figure, the review screen shows it, and nothing stores it — and receipt images
   stay out of backups, so it is unrecoverable
   ([ADR-0073 §4](../adr/0073-the-business-book-is-a-second-profile-and-two-promises-were-broken.md)).
8. **Android-only.** iOS via KMP is issue 13.7, still Todo.
9. **On-device AI has a hardware floor.** Only ~42% of flagship phones ship an on-device LLM, and
   Gemini Nano targets recent high-end chipsets
   ([Android Developers](https://android-developers.googleblog.com/2026/07/android-on-device-inference.html)).
   The template fallback matters more than it looks.
10. **No network effect, no social proof, no distribution.** The Indian incumbents acquire users
    through lending funnels and brand spend. A privacy-first app with no referral mechanic has a
    harder path.
11. **Solo-maintainer concentration risk.** The rigour documented here is impressive and also
    expensive; it depends on one person sustaining it.

---

## 7 · Where it wins, and where it does not

| Segment | Verdict |
|---|---|
| **Privacy-conscious Indian professional** | **Strong win.** Nothing else combines India depth, offline operation and no data sharing |
| **GST-registered freelancer** | **Potential win**, blocked today — business mode is specified but off, and the GST figure is discarded |
| **Someone who wants automatic sync** | **Loses** to INDmoney/Money View until AA lands |
| **Someone who wants a chatty coach** | **Loses** to Cleo — the template verbaliser is deliberately plain |
| **Self-hoster** | **Wins on convenience** over Firefly III/Actual; loses on reporting depth and double-entry rigour |
| **Western user** | **Not a target.** The India-specific logic is the product |

---

## 8 · The strategic read

The product's defensible position is the intersection of three things no incumbent holds at once:
**India-specific financial logic**, **data that never leaves the device**, and **advice that is
computed and explained rather than generated**. The DPDP deadline in May 2027 and the documented
data-sharing norms in the category both push in its favour.

The two things that would most change its position, in order:

1. **Account Aggregator integration (13.6)** — closes the one functional gap that a user will
   notice immediately. AA is consent-based and India-native, so it can be done without abandoning
   P-01.
2. **Ship something.** The gap between a 0.13.4 `dev` branch with 72 ADRs and a product with users
   is the largest risk in this document.

---

## 9 · References

**External**

- Incogni — [60% of 20 popular budgeting apps share your data](https://blog.incogni.com/?p=10157)
- India Briefing — [India's DPDP compliance deadline: May 2027](https://www.india-briefing.com/news/india-dpdp-compliance-gdpr-comparison-45702.html)
- Waypoint — [Best Mint alternative in 2026](https://waypointbudget.com/blog/mint-alternative-2026)
- Finny — [Best Mint alternatives in 2026](https://getfinny.app/blog/best-mint-alternatives-2026)
- beancount.io — [Firefly III vs Actual Budget](https://beancount.io/blog/2026/07/26/firefly-iii-vs-actual-budget-self-hosted-open-source-budgeting-guide)
- IIFL — [Money management apps in India](https://www.iifl.com/index.php/blogs/personal-finance/money-management-aaps-in-india)
- Origin — [Best AI financial advisor apps in 2026](https://useorigin.com/resources/blog/best-ai-financial-advisor-apps-in-2026-xen56)
- Saffari — [Origin vs PortfolioPilot vs Cleo vs Wealthfront](https://omidsaffari.com/blog/best-ai-financial-advisors)
- Sacra — [Cleo revenue and subscribers](https://sacra.com/c/cleo)
- Android Developers — [On-device inference](https://android-developers.googleblog.com/2026/07/android-on-device-inference.html)

**Internal** — [`CLAUDE.md`](../../CLAUDE.md) · [SRS v1.7](../init/AI_Personal_CFO_SRS_v1.7.pdf) ·
[`DECISIONS.md`](../../DECISIONS.md) · [`FLOW.md`](../../FLOW.md) ·
[ADR-0069](../adr/0069-household-is-a-row-above-the-profile-and-aggregation-composes-scoped-reads.md) ·
[ADR-0071](../adr/0071-the-cover-gap-is-the-larger-of-two-readings-and-the-engine-never-says-surrender.md) ·
[ADR-0072](../adr/0072-the-tax-kb-had-no-slabs-and-an-estimate-must-say-what-it-left-out.md) ·
[ADR-0073](../adr/0073-the-business-book-is-a-second-profile-and-two-promises-were-broken.md) ·
[`ai/chat/guardrail.md`](../../ai/chat/guardrail.md) ·
[`ai/rules/rules-kb.json`](../../ai/rules/rules-kb.json) ·
[`docs/compliance/dpdp-2023.md`](../compliance/dpdp-2023.md)

**Measured from the repo at 0.13.4:** 59 Gradle modules · 30 engines · 828 Kotlin files ·
72 ADRs · 51 rulebook rules · schema v31 · 3,685 JVM/debug tests.
