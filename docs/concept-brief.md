# Project: Rekhon — AI Personal CFO
Status: #building (Epics 1–6 built and verified; 7–13 open)

## Business

B2C. Single user, single device. No account, no server, no tenant.

## Problem

- People know what they spent, not what it means — categories without need / want / investment / asset / liability.
- No answer to "can I afford this?" before the money is gone.
- Budgets only report at month end, when it is too late to change the month.
- Net worth is scattered across bank, cash, card, loan, SIP, gold, crypto, property, vehicle, lending.
- Loans and investments are opaque — EMI principal vs interest, real return (XIRR) vs cost-to-value.
- Finance apps are verdict machines — a score with no working behind it.
- Privacy — existing apps need bank login, SMS upload, or a cloud account holding the whole ledger.
- No forecast of the next 30–90 days, so a shortfall arrives as a surprise.
- Nothing works offline; nothing works for Indian instruments (EPF, PPF, NPS, ELSS, SGB, UPI, lakh/crore).

## Solution

- Full balance sheet — 11 account types, daily net-worth snapshot.
- Add a transaction in 3 taps; transfers as one record; splits; future-dated entries that post themselves.
- On-device capture — ML Kit reads receipts; opt-in bank-SMS parsing; neither leaves the phone.
- Auto-categorisation plus need / want / investment / asset / liability classification.
- Budgets with pace and projection mid-month, alerts at 80% and 100%, once each.
- Wealth engines — card billing cycle and utilisation, EMI amortisation to the rupee, dated lots with XIRR, allocation and concentration caps.
- Deterministic engine stack (L1 data → L2 analytics → L3 rules → L4 prediction → L5 decision → L6 language) producing Safe-to-Spend, cash-flow forecast, health score, purchase advisor.
- Every output shows its inputs, the rule id and version that fired, and a plain-language reason.
- Financial thresholds live as versioned data rows in `ai/`, not in code — a rule change is a data edit.
- On-device chat assistant running a real local language model — Qwen2.5-0.5B-Instruct (Apache-2.0) through MediaPipe, weights installed by the user, never downloaded by the app; it only verbalises numbers the engines computed, and the deterministic verbaliser answers on any phone without the weights.
- Home-screen widget (Safe-to-Spend, net worth) and one-tap privacy blur across every screen.
- JSON export / import; SQLCipher database under an Android Keystore key; crypto-shred erase.

## Executive goal

- Make personal finance explainable — no number without its working.
- Ship v1 (Epics 7–10: goals, forecasting, advisor, chat) to Play Store.
- Target: 100 installs in 3 months of launch, 25 weekly-active.
- Showcase piece — a real, large, test-covered Android/Kotlin + on-device-AI codebase for freelance and hiring.
- Prove the privacy claim by publishing the source, since a store listing cannot be verified.

## Business model

- Free core app (AGPL-3.0 with a linking exception).
- Subscription for the optional market-data proxy (NAV, rates, fuel prices) and backup convenience — *deferred, not built in v1*.
- Commercial licence for anyone building on Rekhon without opening their own source.
- No commission model — the app never moves money and takes no product referrals (P-07).

## Revenue model

- Open source — dual-licensed, AGPL + commercial.
- Subscription — optional network features only; the offline app stays free. Deferred until the proxy ships.
- Licensing — commercial licence, white-label, engine stack as a library.
- Services — freelance and consulting pulled in by the codebase itself.

## Collaboration

- None. Solo, with a dual-licence CLA in `CONTRIBUTING.md` so contributions stay relicensable.

## Concept nature

- Android app (Kotlin, Jetpack Compose), offline-first.
- Optional stateless backend proxy for market data — holds no user financial data.
- iOS / KMP is design-for, deferred to Epic 13.

## Market

- Salaried Indians who want to understand their money, not just log it.
- Privacy-conscious users who refuse bank-login aggregators.
- SIP / mutual-fund investors who want real returns, allocation and concentration flagged.
- Borrowers servicing EMIs who want principal vs interest and payoff scenarios.
- Developers and reviewers who read the source before trusting a finance app.

## Working people

- Me — architecture, engines, data, UI, tests, docs.
- Designer — needed for the design system, screens and store assets.

## Budget

- ₹2K for 3 months. Largest known item: Play Console one-time ~₹2,000 ($25).

## Running cost

- ₹0 today — fully offline, no server, no AI API (the LLM runs on-device).
- Only if the optional proxy ships: small VPS, market-data feed, a domain, and mail only if accounts are ever added. No auth charges — there is no login.

## Concept brief

### Description

Rekhon is an Android app that acts as a full-time CFO for one person's money, entirely
on the phone. It records accounts and transactions, reads receipts and (with permission)
bank SMS on-device, classifies every rupee, runs budgets with mid-month pace and projection,
and computes wealth — card cycles, EMI amortisation, XIRR, allocation. A layered engine
stack turns that ledger into a Safe-to-Spend figure, a cash-flow forecast, a health score
and a purchase advisor; a local, open-weights language model only puts those numbers into
sentences, and is structurally unable to produce one of its own. Every
figure carries provenance — engine, version, inputs, the rule that fired — so an answer
given six months ago is still reproducible. Nothing leaves the device without explicit,
per-feature, revocable consent, and the source is public so that claim can be checked.

## Existing products

- Walnut, Money View, Axio — SMS-based expense trackers; no balance sheet, no explanation, ad/lending-driven.
- INDmoney, Kuvera, Zerodha Coin — portfolio and investing first; no budgeting, cloud-hosted, broker-aligned.
- Mint (dead), YNAB, Monarch — budgeting done well, US-centric, subscription, cloud, no Indian instruments.
- Spreadsheets — explainable and private, but no capture, no forecast, no nagging.
- ChatGPT-style assistants — fluent and wrong on arithmetic; no ledger, no provenance.

## Market position

Between the trackers and the portfolio apps, and against both on trust: the only one that
is a full balance sheet *and* a budget *and* an advisor, computes every number
deterministically, shows the working, and never sends the ledger anywhere. Built for Indian
instruments and Indian number formatting, not localised into them.

## My position

Solo builder, Android/Kotlin and on-device AI. The asset is the engine stack and the
discipline around it — integer paise end to end, injected clocks, golden-file and property
tests, versioned rulebook data, lint rules that fail the build on floating-point money,
wall-clock reads in domain code, and PII in logs. That is the part that is hard to copy and
the part worth licensing.

## Decisions taken

- Budget stands at ₹2K for 3 months; running cost stays ₹0 while the app is offline-only.
- Launch target committed: 100 installs and 25 weekly-active in the first 3 months.
- Subscription deferred — the free app needs no server, so there is nothing to charge for until the market-data proxy exists. Revisit when Epic 10 lands.
- No commission model, ever — P-07 rules out referral and transaction revenue.
