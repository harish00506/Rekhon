# Contributing to Rekhon

Thanks for looking. Before you write anything, two things about this project that
are unusual enough to be worth knowing up front.

---

## 1. Read this first: the licence affects your contribution

Rekhon is **dual-licensed**: [AGPL-3.0 with a linking exception](LICENSE) for
everyone, and a [commercial licence](COMMERCIAL-LICENSE.md) for those who cannot
comply with copyleft. The AGPL option is open source in the OSI sense — you may
take it, modify it and ship it commercially, provided you publish your changes.

That has a direct consequence for you. Dual licensing only works while the
copyright holder can grant *both* licences. If each contributor kept exclusive
copyright over their patch, no commercial licence could be granted for any file
they touched, and the model would quietly break.

**By submitting a pull request you agree that:**

1. You wrote the contribution yourself, or have the right to submit it.
2. You grant the project owner a perpetual, worldwide, irrevocable, royalty-free
   licence to use, modify, sublicense and relicense your contribution, including
   under commercial terms, and including as part of a differently licensed
   version of Rekhon.
3. You retain your own copyright in what you wrote. This is a grant of rights,
   not an assignment — you keep the ability to use your own work elsewhere.
4. Your contribution is provided without warranty.

If that is not acceptable to you, please open an issue to discuss the idea rather
than sending code. An idea costs you nothing and is genuinely welcome.

This is deliberately a lightweight inbound grant rather than a signed CLA. If the
project ever takes contributions at scale, it will need a real CLA and this
section will be replaced.

## 2. This codebase has binding rules, and they are enforced by the build

[`CLAUDE.md`](CLAUDE.md) is not a style guide. It is a set of rules the build
actively fails on. A PR that violates one will not go green, and the failure
message will tell you which rule and why.

The ones that surprise people:

| Rule | What it means |
|---|---|
| **MNY-001** | Money is `Long` minor units (paise) end to end, in the `Money` value class. A `Double` or `Float` with a monetary name **fails the build** via a custom lint detector. |
| **TIM-001** | Domain code must never read the wall clock. Inject `Clock`. `System.currentTimeMillis()` in `:domain:*` or `:core:model` **fails the build**. |
| **ARC-006** | `GlobalScope` is banned and **fails the build**. |
| **P-01** | No user-visible string may be hardcoded in a `:feature:*` composable — **fails the build**. And no PII or amount may reach a log — **fails the build**. |
| **P-03** | Engines compute every number. A language model may only put existing numbers into sentences; it may never produce one. |
| **P-02** | Every figure shown to the user must be traceable to the rule and inputs that produced it. |
| **ARC-001** | Dependencies flow one way: `feature → domain → data/core`. Feature modules never depend on each other. |

Financial thresholds are **data rows** in [`ai/rules/rules-kb.json`](ai/rules/),
never numbers in Kotlin. If you need a threshold, add a row and mirror it — see
the `add-rulebook-rule` skill and the existing `RulebookDriftTest`s, which fail
the build if a mirror and the rulebook disagree.

## 3. Before you open a PR

```bash
./gradlew ktlintCheck detekt lintDebug     # static analysis — no new warnings
./gradlew unitTests koverVerify            # NOT testDebugUnitTest, see below
```

> **`unitTests`, not `testDebugUnitTest`.** The latter is an Android *variant*
> task: it does not exist on the pure-Kotlin modules and never reaches `:lint`.
> Using it once meant the fourteen tests guarding the money and time rules ran in
> no command at all. `unitTests` aggregates across every module.

Coverage gates are **engine ≥ 85%, money math 100%**.

**A green build does not mean it works.** This project's Definition of Done
requires the app to be run and the changed behaviour observed on a device or
emulator. Say in your PR what you actually exercised and what you saw.

**Every function you add or change needs a doc comment** stating why it exists,
what it does, what it results in, its changelog, inputs and outputs — and at
least one test covering the normal case plus the edges. If something is genuinely
too trivial to test, say so in one line rather than skipping silently.

## 4. Branches and commits

The model is `feature/<id-dashes>-<slug>` → `dev` → `stage` → `main`. **`main`
and `stage` are protected**; changes land by PR. Never commit to them directly.

Commits follow [Conventional Commits](https://www.conventionalcommits.org) and
**cite their requirement id**:

```
feat(investments): allocation and diversification (6.4, FR-INV-002, §11.2 AI-INV)
```

The requirement ids come from the SRS in [`docs/init/`](docs/init/) and the
backlog in [`docs/issues/`](docs/issues/). If you are changing behaviour and
cannot find the id, ask in the issue first — behaviour without a requirement
behind it is usually a discussion, not a patch.

## 5. Documentation is part of the change, not after it

- Adding, removing or swapping **any dependency** — including test-only ones —
  needs a row in [`DECISIONS.md`](DECISIONS.md) naming what it was chosen over.
- Changing a **runtime call path** needs [`FLOW.md`](FLOW.md) updated in the same
  commit.
- A decision that deviates from the SRS needs an **ADR** in [`docs/adr/`](docs/adr/).
- A changed engine needs its `ENGINE.md` updated.

## 6. Good first contributions

- **Bug reports** with steps to reproduce are worth more than most patches. Say
  what you expected, what happened, and on which Android version.
- **The stale backlog.** Many issue docs in `docs/issues/` still say `Todo` for
  work that shipped long ago. Correcting those is genuinely useful and needs no
  Kotlin.
- **Issue 12.4** — there is no end-to-end smoke test matching the one the
  workflow describes. That gap is documented and unclaimed.
- **Localisation.** Indian-language support (issue 10.8) is unstarted, and every
  user-visible string already lives in `strings.xml`.

## 7. Reporting a security issue

Please **do not** open a public issue for anything security-related — this app
holds people's financial data on their devices, under encryption.

Report privately through GitHub's security advisory feature on the repository, or
contact the owner directly. Include what you found and how to reproduce it, and
give a reasonable window for a fix before disclosing.

---

## Code of conduct

Be decent. Assume good faith, critique the code and not the person, and accept
that the maintainer may say no to a perfectly good patch because it does not fit
where the project is going.
