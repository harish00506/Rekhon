# Terms and Conditions

**Rekhon** · Last updated: 2026-08-29

These terms cover your use of the Rekhon application. The separate
[LICENSE](LICENSE) covers what you may do with the *source code*; if you are
here about copying, modifying or redistributing the code, read that instead.

By installing or using Rekhon you agree to these terms. If you do not agree, do
not use it.

---

## 1. What Rekhon is, and what it is not

Rekhon is a personal-finance tool that records money you tell it about and
computes figures from that record — balances, budgets, returns, allocation.

**It is not financial advice.** Rekhon is not registered with the Securities and
Exchange Board of India (SEBI), the Reserve Bank of India, or any other financial
regulator, in India or elsewhere. Nothing it displays is investment, tax, legal or
accounting advice, and nothing in it is a recommendation to buy, sell or hold any
security, fund, commodity or currency.

It analyses and explains what you already own. Where it flags something — a
concentration above a published threshold, a budget heading over — that is an
observation about your own data measured against a rule you can read in
[`ai/rules/`](ai/rules/), not an instruction.

**It never moves money.** Rekhon has no payment capability and no connection to
any bank, broker or exchange. It cannot transact on your behalf, and no action it
suggests happens unless you go and do it yourself, elsewhere.

## 2. The figures are only as good as what you enter

Every number Rekhon shows is computed from data you supplied. It has no
independent source of truth about your finances, does not verify what you enter,
and cannot know about money it was never told about.

If you enter a wrong amount, a wrong date, or forget an account, the figures will
be wrong in ways that look exactly like figures that are right. You are
responsible for the accuracy of what you enter and for checking anything you rely
on against your actual bank, broker or lender statements.

Where a figure cannot be computed honestly, Rekhon shows you that it cannot,
rather than substituting zero. Treat those absences as real.

## 3. Your data, and where it lives

Rekhon stores your data **on your device**, in an encrypted database. There is no
account, no sign-up, and no server holding your financial information.

- Nothing about your money is transmitted anywhere unless you explicitly turn on
  a feature that does so. Every such feature is off by default, is listed
  individually in Settings, and can be switched back off at any time.
- Reading bank SMS, where you enable it, happens entirely on the device. Message
  contents are not transmitted.
- Because your data is on your device and only your device, **you are responsible
  for backing it up.** Use the export function. If you lose, wipe, reset or damage
  your device, or uninstall the app, your data is gone and cannot be recovered by
  anyone, including us. There is no copy elsewhere.
- Any export file you create leaves the app's protection. Once you save or share
  it, keeping it safe is your responsibility.

## 4. Security

Rekhon encrypts its database and can require a PIN or your device biometrics to
open. These protect against casual access to your device. They do not protect
against a compromised, rooted or malware-infected device, and no application can.

Keep your device updated and locked. If you enable the app lock and forget your
PIN, there is no recovery path — that is a deliberate consequence of the
encryption, not an oversight.

## 5. No warranty

Rekhon is provided **as is**, without warranty of any kind, express or implied,
including but not limited to warranties of merchantability, fitness for a
particular purpose, accuracy, or non-infringement.

It is under active development. Features may change or be removed, and defects
may exist.

## 6. Limitation of liability

To the maximum extent permitted by applicable law, the author is not liable for
any loss or damage arising from your use of Rekhon — including financial loss,
lost profits, lost opportunity, tax consequences, lost or corrupted data, or any
indirect or consequential damage — whether or not the possibility of such loss
was foreseeable.

**Financial decisions you make are yours.** Rekhon informs them; it does not make
them, and it does not share in their outcome.

Nothing here excludes liability that cannot lawfully be excluded.

## 7. Third-party components

Rekhon is built on third-party software, listed in [NOTICE](NOTICE), each under
its own terms. Some — notably Google's ML Kit, used for on-device receipt
scanning — carry the terms of their providers, which apply to you as a user of
this app.

## 8. Commercial use

Using the app for your own finances, including finances of a business you run, is
covered by these terms.

Using the *software itself* commercially — selling it, offering it as a service,
building a product on it, or distributing it as part of a commercial offering —
requires a separate written licence from the copyright holder. See
[LICENSE](LICENSE) and [CONTRIBUTING.md](CONTRIBUTING.md).

## 9. Changes to these terms

These terms may change as the app develops. The version in the repository at the
date above is the current one; material changes will be noted in
[CHANGELOG.md](CHANGELOG.md).

## 10. Governing law

These terms are governed by the laws of India, and the courts of India have
jurisdiction over any dispute arising from them.

---

**These terms have not been reviewed by a lawyer.** They are written to state the
author's intent plainly and in good faith. If Rekhon is distributed publicly, sold
commercially, or used by anyone other than its author, have them reviewed by a
qualified legal professional in the relevant jurisdiction before relying on them.

## Contact

Open an issue at <https://github.com/harish00506/Rekhon/issues>.
