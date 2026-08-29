# Rekhon

**A full-time CFO for your money, that runs entirely on your phone.**

Most finance apps tell you a number. Rekhon tells you the number *and the working
behind it* — which rule fired, what it measured, and what it measured against. A
figure you cannot check is a verdict, and this app does not issue verdicts.

*Rekha* (रेखा) is the ruled line of a ledger. The name is that line, and the
reckoning drawn under it.

---

## What it does

**Sees your whole balance sheet.** Eleven account types — bank, cash, credit
card, loan, investment, gold, crypto, property, vehicle, money owed to you and
money you owe. Net worth is assets minus liabilities, snapshotted daily.

**Records money in three taps.** Amount, category, save. Transfers are one
logical record rather than two transactions that can drift apart. Split a bill
across any number of categories. Date something in the future and it posts
itself when the day comes.

**Reads receipts and bank alerts, on the device.** ML Kit reads the total, the
date and the merchant off a photo. With your explicit permission — off by
default, revocable at any time — it reads bank transaction SMS and proposes
entries for you to confirm. Neither ever leaves the phone.

**Learns what your spending is.** Merchants map to categories automatically, and
every rupee is classified as a need, a want, an investment, an asset or a
liability, so "where did it go" has an answer beyond a pie chart.

**Budgets that tell you the truth mid-month.** Not just spent-versus-planned, but
pace — what even spending would have been by today — and a projection of where
the month actually lands. Alerts at 80% and 100%, once each, because an app that
notifies you about every coffee is one you learn to mute.

**Wealth, properly.** A credit card's real billing cycle, utilisation and due
date. A loan's EMI split into principal and interest, amortised to the rupee.
Investment holdings with dated lots and a money-weighted return (XIRR) — because
a SIP's cost-to-value ratio understates its return by roughly half. Portfolio
allocation by asset class, with concentration flagged against a published
rulebook.

**A home-screen widget** with Safe-to-Spend and net worth, and a privacy blur
that masks every amount on every screen — including the widget — with one tap.

**Your data is yours.** Export the whole thing to a JSON archive and import it
back. No account, no sign-up, no server.

---

## The rules it will not break

These are binding on the code, not aspirations.

| | |
|---|---|
| **Privacy first** | Nothing about your money leaves the device without explicit, per-feature, revocable consent. Default is fully offline. |
| **Offline first** | Every core feature works in airplane mode. There is no server to be down. |
| **Numbers from math, words from AI** | Deterministic engines compute every figure. A language model may only put them into sentences — it never produces a number. |
| **Show the work** | Every recommendation shows its inputs, the rule that fired, and a plain-language reason. |
| **Advice, never orders** | It recommends and simulates. It never moves money. |
| **Exact money** | Rupees are integer paise end to end. No floating point ever touches an amount — the build fails if it does. |

The database is encrypted with SQLCipher under a key held in the Android
Keystore. Financial thresholds are versioned data rows in [`ai/rules/`](ai/rules/),
not numbers buried in code, and every insight cites the rule id and version that
produced it — so an answer given six months ago stays reproducible.

---

## Built for India

Amounts in lakh and crore with Indian digit grouping (₹1,23,456.78). SIPs, EPF,
PPF, NPS, ELSS, sovereign gold bonds. UPI and Indian bank SMS formats. Gold and
crypto as first-class asset classes with their own concentration caps.

---

## Status

Under active development. Epics 1–6 are built and verified: foundation,
onboarding and accounts, transactions and capture, categorisation and budgets,
dashboard and export, and wealth through investment allocation. Goals, forecasting,
the advisor and the on-device chat assistant are not built yet.

See [`docs/issues/`](docs/issues/) for the backlog, [`DECISIONS.md`](DECISIONS.md)
for why things are the way they are, and [`FLOW.md`](FLOW.md) for how execution
actually travels through the app.

---

## Licence

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Apache 2.0 was chosen over a copyleft licence deliberately: it is compatible with
every dependency here, it carries an explicit patent grant, and it is the licence
Android developers can adopt without asking anyone. A privacy-first finance app
asks people to believe a claim about what it does with their data — publishing
the source is how that claim becomes checkable rather than marketed.

**Rekhon is not registered with SEBI or any financial regulator, and nothing it
produces is investment, tax or legal advice.** See [NOTICE](NOTICE).
