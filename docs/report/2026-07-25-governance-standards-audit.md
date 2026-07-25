<!--
  Why:  The governance layer (CLAUDE.md, .claude/ skills + commands, the issue/branch/PR workflow,
        the ai/ data layer, the 85-issue backlog) was written before most of the code. This report
        answers whether that layer meets industry standards and what is missing, so the gaps are
        closed before ~85 issues of code are built on top of them.
  What: A maturity assessment of the project's rules, skills, workflows, enforcement surface,
        ai/ runtime data governance, and backlog hygiene, graded against named external benchmarks.
  Result: A reader knows which rules are genuinely enforced, which are honour-system, and the
        ranked, evidence-cited set of fixes that close the gap.
  Changelog:
    2026-07-25 — Created. Audit of commit 5a39e1f (branch feature/1-1-1-gradle-skeleton).
    2026-07-25 — Remediation pass: G-02/G-03/G-04 applied. G-01 attempted, reverted, and the
                 recommendation corrected after testing disproved it (see §3.1).
    2026-07-25 — G-01 closed with issue 1.2: the coverage gate is wired against the real Money
                 code and proved to fail twice before merging (see §3.1).
-->

# Governance & Standards Audit — AI Personal CFO

**Audit date:** 2026-07-25 · **Commit audited:** `5a39e1f` · **Branch:** `feature/1-1-1-gradle-skeleton`
**Scope:** binding rules, project skills, slash commands, dev workflow, the enforcement surface
(CI + Gradle quality gates + hooks), the `ai/` runtime data layer, and backlog/traceability hygiene.
**Type:** assessment only — this audit changed no rule, build, CI, `ai/`, or backlog file.

---

## 1. Verdict

**Design governance is well above the industry norm for a greenfield project. Enforcement
governance lags it by roughly two maturity levels.** The rules are unusually well-written —
ID-cited, precedence-ordered, and binding on agents as well as humans. The problem is that several
of them are *asserted as enforced* while nothing in the build actually blocks a violation.

That gap is the single systemic finding of this audit, and it is worse than a plain omission.
Both human reviewers and AI agents treat [`CLAUDE.md`](../../CLAUDE.md) as ground truth. A rule
documented as a hard gate but implemented as nothing produces false confidence: the PR template is
ticked, `/pre-merge` reports green, CI passes — and the invariant was never checked.

### Maturity by domain

Levels: **1 Absent** · **2 Ad hoc** (documented only) · **3 Defined** (documented + partly
automated) · **4 Enforced** (an automated gate blocks violations) · **5 Optimised** (enforced,
measured, self-correcting).

| # | Domain | Benchmark used | Level | Basis |
|---|--------|----------------|:-----:|-------|
| 1 | Module architecture & build | Google Android modularization guidance / Now in Android | **4** | `build-logic` composite build, version catalog, six convention plugins; the ARC-002 guard genuinely fails the build and is TestKit-tested |
| 2 | Written rules & agent governance | Emerging `CLAUDE.md`/agent-instruction practice | **4** | Binding, ID-cited, explicit precedence, PR self-check; docked for asserting gates that do not exist |
| 3 | Code style & static analysis | ktlint + detekt conventional practice | **3** | Both applied repo-wide and run in CI; detekt config is an 11-line overlay with no baseline and no `FunctionLength` |
| 4 | Test strategy & coverage | Coverage threshold enforced in CI | **2** | Strategy is excellent on paper; `koverVerify` has **zero rules** so it is a no-op; Paparazzi is entirely absent |
| 5 | CI/CD pipeline | GitHub Actions conventional practice | **3** | Correct steps, offline-safe, artifacts uploaded; single job, no matrix, no scheduled run, no release workflow, two steps commented out |
| 6 | Supply-chain security | OpenSSF Scorecard / SLSA | **2** | No Dependabot/Renovate, actions pinned by mutable tag, no OSV scan, no `CODEOWNERS`, no `SECURITY.md` |
| 7 | App security & privacy | OWASP MASVS v2 (STORAGE, CRYPTO, AUTH, CODE, PRIVACY) | **3** | Strong written rules (Tink/Keystore, SQLCipher, biometric, consent ledger, P-01) and secret deny-reads; no MASVS mapping, R8 off, no secret scanning |
| 8 | Requirements traceability | ISO/IEC/IEEE 29148 | **2** | The DoD mandates citing FR IDs that exist only inside a binary PDF; no index, no validator |
| 9 | Architecture decision records | ADR practice (Nygard) | **2** | Template present, **zero ADRs**, while the rules require them |
| 10 | Local developer enforcement | pre-commit / lefthook conventional practice | **1** | Zero active git hooks; Conventional Commits mandated but never validated |
| 11 | Versioning & changelog | SemVer · Keep a Changelog · Conventional Commits | **4** | All three adopted; `VERSION` is read by the build. Docked only because commit format is unchecked |
| 12 | AI / knowledge data governance | Data-contract & schema-validation practice | **3** | Exceptional design (versioned rows, `_meta` envelope, deprecate-don't-rename); validation is parse-only, YAML unchecked, no schema |
| 13 | Accessibility | WCAG 2.2 AA / Android accessibility | **2** | Present in the DoD and skills; no automated check in CI |
| 14 | Repo docs & onboarding | Standard repository hygiene | **3** | Exceptionally rich internal docs; no root `README`/`LICENSE`/`CONTRIBUTING`; several stale docs |

**Weighted picture:** design & documentation ≈ **4.0**; enforcement & supply chain ≈ **2.0**.

---

## 2. What is genuinely strong

This is not padding — it is a substantial part of the answer, and several items here are better
than what most funded teams ship.

**[`CLAUDE.md`](../../CLAUDE.md) is a genuinely good binding rulebook.** It states precedence
explicitly ("when this file and a chat instruction disagree, ask — do not silently override"),
gives every rule a stable ID, separates golden rules that override feature requests from ordinary
standards, and names the two classic bug factories (money and time) as review-blocking. The
rule-of-thumb closer — "prefer the boring, testable, explainable option; cleverness is a cost" —
gives an agent something to reason from when a case is not covered.

**The ARC-002 guard is real enforcement, not documentation.** `Arc002.kt` holds the decision logic
as a pure, testable unit; `enforceNoAndroidPlugins()` in
[`ProjectExtensions.kt:69`](../../build-logic/convention/src/main/kotlin/ProjectExtensions.kt)
throws a `GradleException` if a pure-Kotlin module applies an Android plugin; two tests cover it
(one via Gradle TestKit); and CI runs it as the **first** verification step, before build or lint.
Taking one architecture invariant and making it fail the build with a tested guard is above-average
rigor and is the pattern the other rules should copy.

**The build layer follows current Google guidance.** A `build-logic` **included composite build**
(correctly chosen over the legacy `buildSrc`), a single version catalog with a header comment
forbidding un-ADR'd library swaps, `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, and six convention
plugins that keep 19 module build files nearly empty.

**The agent-governance surface is thoughtful.** The four slash commands carry explicit
anti-fabrication guards (`/run`: "do not fabricate a run"; `/verify` requires a real
airplane-mode leg via `adb shell cmd connectivity airplane-mode`), and
[`money-time-audit`](../../.claude/skills/money-time-audit/SKILL.md) tells the agent "if clean, say
so explicitly — do not invent findings." Instructing an agent on how to *not* hallucinate
compliance is a control most projects miss entirely.
[`.claude/settings.json`](../../.claude/settings.json) also denies reads on keystores, `*.jks`,
`google-services.json`, and `local.properties`, keeping signing material out of agent context.

**The [`ai/`](../../ai/) layer is the strongest artefact in the repository** — see §8.

**Release hygiene is adopted, not just mentioned:** Keep a Changelog + SemVer + Conventional
Commits, with a `VERSION` file that `app/build.gradle.kts` actually reads for `versionName`.

---

## 3. P0 — Rules documented as enforced that are not enforced

Each row below was verified directly against the file, not inferred.

| Rule as documented | Where it is asserted | Actual state | Evidence |
|---|---|---|---|
| "Coverage ≥ 85%; money math **100%**" | `CLAUDE.md` §4 and §8, PR template, `/pre-merge`, all 85 issue files | Kover is applied but **no verification rule exists anywhere in the repo**. `koverVerify` passes trivially on any coverage, including 0%. **See §3.1 — this cannot be fixed until issue 1.2.** | [`ProjectExtensions.kt:46-58`](../../build-logic/convention/src/main/kotlin/ProjectExtensions.kt) applies the plugin and nothing else; a repo-wide search for `minBound` / `verify {` across every `*.kts` and `*.toml` returns only plugin declarations |
| Paparazzi screenshot tests gate merges | `CLAUDE.md` §4, PR template, `/pre-merge`, `.claude/settings.json` | **Absent from the entire build** — not in `libs.versions.toml`, applied by no convention plugin, declared by no module; the CI step is commented out | [`ci.yml:78-79`](../../.github/workflows/ci.yml); zero Paparazzi hits in the version catalog |
| `GlobalScope` (ARC-006), `System.currentTimeMillis()` in domain (TIM-001), and PII/amount logging are "lint-banned" / "lint strips it" | `CLAUDE.md` §3 and §5, [`docs/Rules.md`](../Rules.md) | **No `lint.xml` exists anywhere**, there is no custom-lint module, and no convention plugin configures a `lint { }` block. `lintDebug` runs with AGP defaults, which know nothing about these project rules. | Glob for `**/lint.xml` returns no files |
| "functions ≈ ≤ 40 lines (detekt)" | `CLAUDE.md` §5 | [`config/detekt/detekt.yml`](../../config/detekt/detekt.yml) is 11 lines containing only two `@Composable` naming exemptions. No `FunctionLength` rule is set, so detekt's default of **60** applies. | The file in full |

**A fifth, compounding defect:** `/pre-merge` executes `./gradlew verifyPaparazziDebug` and
`.claude/settings.json:10` pre-authorises `./gradlew verifyPaparazzi:*` — but that task does not
exist. Running the project's own Definition-of-Done command therefore fails on an unknown task, or
the step gets skipped and the checklist ticked anyway. **The DoD command is currently unrunnable
as written.**

### 3.1 Correction — the coverage gate cannot be wired yet (tested 2026-07-25)

This report's first edition recommended wiring the Kover threshold *before* issue 1.2 lands `Money`.
**That recommendation was tested and is wrong.** Recording it here because it changes the fix.

A minimum line-coverage bound was added to the pure-Kotlin convention plugin (`:core:model`,
`:core:common`, `:domain:*` — the modules the "≥ 85% engine coverage" rule actually targets) and
deliberately set to an **impossible `minBound(101)`**. With the Gradle cache disabled
(`./gradlew koverVerify --rerun-tasks`, 11m full rebuild), the build **still succeeded**.

Two causes are consistent with that result and cannot be told apart on the current skeleton:

1. The Kover 0.9.1 DSL path used (`kover { reports { verify { rule { minBound(n) } } } }`) may not
   feed the `koverVerify` task, or
2. the modules contain no measurable coverage units, so any bound passes vacuously. Every
   pure-Kotlin module holds only `internal object ModulePlaceholder { const val PATH }` — and a
   Kotlin `const val` is **inlined at the call site**, so `ModulePlaceholderTest` never loads the
   class it appears to test.

Either way the gate protects nothing, so the change was reverted rather than committed. **Shipping
an unverifiable gate would have reproduced the exact defect this report was written to flag** — a
rule that reads as enforced, passes CI, and checks nothing. A gate that cannot be demonstrated to
fail is not a gate.

**Corrected guidance:** wire the coverage bound **as part of issue 1.2**, against the real `Money`
code, and in the same change prove it bites by temporarily setting an impossible bound and watching
the build go red. Until coverable code exists, no threshold is meaningful.

**Resolved 2026-07-25 (issue 1.2).** Cause 2 above was the real one. With `Money` in place,
`configureCoverage()` measured `:core:model` at 100.000000% and a forced bound of 101 failed the
build — so the DSL path was never broken, there was simply nothing to measure. A second proof
(deleting `MoneyFormatterTest`, watching coverage fall to 77.5% and the build go red) confirms the
gate tracks real tests rather than reporting a constant. Bounds now in force: **85%** on pure-Kotlin
modules, **100%** on `:core:model`. The 85% floor is wired everywhere but still only *proved* on
`:core:model`; the other pure-Kotlin modules remain vacuous until they hold real code.

Worth noting independently: the placeholder tests across all 19 modules assert a `const val` and so
may be exercising nothing. That is harmless for a skeleton, but they should not be mistaken for
evidence that the test wiring works.

### How to read this finding

Three of the four gaps are already scheduled work — custom lint rules are task **1.1.5**
([`docs/features/1.1-project-skeleton/tasks/1.1.5-custom-lint-rules.md`](../features/1.1-project-skeleton/tasks/1.1.5-custom-lint-rules.md)),
Paparazzi is issue **1.8**, and OSV + R8 are issue **11.6**. The engineering is planned; the defect
is that the *documentation states them in the present tense as active gates*.

So the remedy splits cleanly:

- **Correct the tense** (cheap, do now): mark lint bans, Paparazzi, and OSV as *planned — issue N*
  in `CLAUDE.md`, `docs/Rules.md`, the PR template, and `/pre-merge`, and drop the dead
  `verifyPaparazzi` entries from `/pre-merge` and `.claude/settings.json` until 1.8 lands.
- **Wire the two that need no new issue** (also cheap): real Kover verification rules in
  `configureQuality()`, and a `FunctionLength` threshold in `detekt.yml`.

Leaving the coverage gate as a no-op is the most expensive of the four to defer, because engines
start landing with issue 1.2 (`Money`) — the exact code the "100% money math" rule exists to
protect. A gate wired *after* the money code is written has to retro-fit coverage; wired before, it
never lets the debt accrue.

---

## 4. P1 — Supply-chain and release security

Graded against OpenSSF Scorecard's checks. This matters more than usual here: the product handles
financial data, and `CLAUDE.md` §1 makes privacy a golden rule (P-01).

| Gap | Why it matters | Status |
|---|---|---|
| **No Dependabot or Renovate** | The version catalog pins ~40 libraries and forbids un-ADR'd swaps — an excellent policy that, with no update automation, converts directly into silent CVE accumulation. Nothing will ever tell you a pinned library has a published advisory. | Absent |
| **GitHub Actions pinned by mutable tag** (`actions/checkout@v4`, `setup-java@v4`, `setup-gradle@v4`, `setup-android@v3`, `upload-artifact@v4`) | A tag can be repointed at new code by whoever controls the action repo, so CI executes unreviewed third-party code with repo context. Scorecard's Pinned-Dependencies check wants full commit SHAs. | Present but unpinned |
| **OSV dependency scan disabled** | Commented out in [`ci.yml:80-81`](../../.github/workflows/ci.yml), deferred to issue 11.6 (SEC-007). Reasonable as a deferral — flagged so it is not forgotten. | Tracked (11.6) |
| **R8/minify disabled for release** | `isMinifyEnabled = false`, deferred to 11.6. Ships an unobfuscated finance app if a release were cut today. | Tracked (11.6) |
| **No `CODEOWNERS`** | `CLAUDE.md` §7 states `main` and `stage` are protected and land changes only via reviewed PRs. Nothing in the repo encodes who reviews. Branch protection also lives only in GitHub settings — invisible to this audit and to any new contributor. | Absent |
| **No `SECURITY.md`** | No vulnerability disclosure path for a financial application. | Absent |
| **No secret scanning** | The `.claude/settings.json` deny-list stops an *agent reading* a keystore; it cannot stop anyone *committing* one, because there is no hook (§5) and no scanner. `.gitignore` covers `local.properties` but not a stray `*.jks` placed elsewhere. | Absent |

---

## 5. P1 — Nothing is enforced locally

**Every quality gate in this project is CI-only.** Verified: `.git/hooks/` contains exactly 14
files, all `.sample`; `core.hooksPath` is unset; there is no `lefthook.yml`, no
`.pre-commit-config.yaml`, no `.husky/`; and `.claude/settings.json` has no `hooks` block.

Consequences:

- A developer or agent can commit anything. Feedback on a ktlint violation arrives minutes later
  in CI instead of instantly, which is the expensive ordering.
- **Conventional Commits are mandated by `CLAUDE.md` §7 and never validated** — no `commit-msg`
  hook, no commitlint, no CI check. The same applies to the rule that every commit references a
  requirement ID.
- The `money-time-audit` skill — precisely the check that should run on every diff touching money
  or time — only fires when a human remembers to invoke it.

Two complementary fixes, both cheap:

1. **A commit-time hook framework.** `lefthook` or `pre-commit` fits; Python is already a
   dependency of this repo (`scripts/*.py`, and CI's `ai/**` validator). Husky is not an option —
   there is no `package.json`. Run `ktlintFormat` on staged Kotlin plus a Conventional-Commit
   regex on the message.
2. **Claude Code hooks in `.claude/settings.json`.** A `PostToolUse` hook on `Write`/`Edit` of
   `*.kt` can auto-run ktlint or grep for banned constructs, closing the loop for agent-authored
   code specifically — the majority of code in this project.

---

## 6. P1 — Requirements traceability

Graded against ISO/IEC/IEEE 29148, which requires requirements to be uniquely identified and
bidirectionally traceable to their implementation.

The project has the *convention* right — well-designed ID families (`FR-TXN-004`, `AI-ARC-004`,
`SEC-003`, `DB-003`, `NFR-011`, `P-03`, `MNY-001`) cited in commits, code comments, and the PR
template. The mechanism is what is missing:

> **`FR-*`, `SEC-*`, `DB-*` and `NFR-*` exist only inside the 58-page binary
> [`docs/init/AI_Personal_CFO_SRS_v1.7.pdf`](../init/AI_Personal_CFO_SRS_v1.7.pdf).**

Nothing in the repository enumerates them. A repo-wide search finds only ~21 distinct `FR-*` IDs
mentioned in any text file, most of them as unexpanded ranges (`FR-ACC-001..007`) in
[`docs/features/README.md`](../features/README.md). There is no traceability matrix, no
requirements index, and no validator.

Two consequences follow:

- The Definition of Done requires every commit to cite an FR/AI ID, but obtaining one means opening
  a PDF by hand, and **nothing can detect a typo'd or invented ID**. That is a live hazard when an
  agent writes the commit message — a plausible-looking `FR-TXN-011` that does not exist will pass
  every check in the project.
- Reverse traceability (which requirements are implemented? which are orphaned?) cannot be computed
  at all. The 85-issue CSV has no requirement-ID column, so there is not even an issue → FR mapping.

**The fix already has a model inside this repo.** `ai/` solved exactly this problem for its own IDs:
[`engine-registry.yaml`](../../ai/orchestrator/engine-registry.yaml) enumerates every `AI-*` engine,
[`rules-kb.json`](../../ai/rules/rules-kb.json) every `RULE-*`, and
[`tool-registry.json`](../../ai/skills/tool-registry.json) every chat tool. Extracting the SRS
requirement table into `docs/traceability/requirements.csv` (`id, title, srs_section, epic, status`)
plus a CI check that every ID cited in a commit or issue resolves would raise this domain from
level 2 to level 4 in a day, and would let `check_issue_docs.py` validate FR references too.

---

## 7. P1 — Zero architecture decision records

[`docs/adr/`](../adr/) contains exactly one file: `0000-adr-template.md`. The template is good —
it even requires each ADR to confirm the golden rules P-01/P-03/P-04/P-07 are not weakened.

But `CLAUDE.md` §5 requires an ADR for any SRS deviation, `docs/Rules.md` requires one for any
library swap, and issue 13.7's acceptance criteria reference a KMP feasibility ADR. Meanwhile
`docs/memory.md` lists "ENGINE/ADR templates" under **Completed** — the templates are delivered;
zero instances exist.

Several consequential decisions have already been made and are recorded nowhere:

- `build-logic` included composite build instead of `buildSrc`
- the pinned stack in `libs.versions.toml` (AGP 8.11.1 / Kotlin 2.1.20 / compileSdk 36 / minSdk 26)
- GitFlow-lite (`feature → dev → stage → main`) rather than trunk-based development
- `Money` as `Long` minor units with `HALF_EVEN` (MNY-001) — arguably the highest-consequence
  technical decision in the product
- `org.gradle.configuration-cache=false`, deliberately disabled "for the skeleton"

Writing ADR-0001 through ADR-0005 retroactively is a few hours of work and is the difference
between rules a newcomer must accept on faith and rules whose reasoning they can inspect.

---

## 8. P2 — The `ai/` runtime data layer

**Assessed first as a strength.** All 15 files carry real content — no placeholders. Every JSON
file uses the same `_meta` envelope (`why`, `what`, `result`, `source_section`, `version`,
`changelog`), `rules-kb.json` documents its own 11-field row schema inline, and the 23 rules are
mirrored in machine (`rules-kb.json`) and human (`rulebook.md`) form that match exactly. The
governance rule — *"renaming an ID breaks traceability — deprecate, don't rename"*, enforced by an
`enabled: false` convention — is precisely the right design for AI-ARC-006 reproducibility.
[`guardrail.md`](../../ai/chat/guardrail.md) specifies the numeric check as a deterministic L3
algorithm that "never uses the LLM to judge the LLM," with six rules including an explicit ban on
LLM arithmetic. Treating financial thresholds as versioned data rather than code is a design most
teams reach only after a painful incident.

Three gaps keep this at level 3 rather than 4:

1. **CI validates JSON only; the YAML is never parsed.** The validator at
   [`ci.yml:45-55`](../../.github/workflows/ci.yml) globs `ai/**/*.json`. Both
   `engine-registry.yaml` and `insight-orchestrator.yaml` — the engine catalogue and the pipeline
   definition, i.e. the files that decide *which engines run in what order* — are unchecked. A YAML
   syntax error ships.

2. **Validation is parse-only, so the binding invariant is unprotected.** `json.load()` confirms a
   file is syntactically valid JSON. It cannot catch a missing required field, a `severity` outside
   `info|warn|fail`, a **duplicate `rule_id`**, or a **renamed `rule_id`** — and that last one is
   exactly what `ai/README.md` calls out as breaking traceability. The repository's most explicitly
   binding data rule has no automated protection. JSON Schema files under `ai/schema/` plus schema
   validation in the existing CI step would close this.

3. **No cross-file referential integrity.** Nothing verifies that the `consumed_by` engine IDs in
   `rules-kb.json` resolve against `engine-registry.yaml`, or that tool names in
   `tool-registry.json` match what the chat layer expects. These files reference each other by ID
   across a boundary no check crosses; drift will surface at runtime rather than in CI.

---

## 9. P2 — Backlog and documentation hygiene

**Status tracking has already drifted, at 1 of 85 issues.** 84 of 85 trackers are untouched stubs
(`0 / 9 phases`, empty verification log). The one worked issue disagrees with itself: the tracker
for 1.1 reads "In progress — 6/9 phases" with seven real verification rows, while
`1.1-gradle-multi-module-skeleton-version-catalog-ci.md` still reads `Status: Todo`. Every issue
file says `Status: Todo`, so the field carries no information.

**A validator exists but is never run.** `scripts/check_issue_docs.py` asserts that every skill
path referenced by an issue resolves. CI does not invoke it, nor `gen_issue_docs.py --check`.
Adding both to the existing pipeline is a two-line change.

**A generator bug is corrupting shipped files.**
[`scripts/gen_issue_docs.py:1070`](../../scripts/gen_issue_docs.py) does:

```python
text = text.replace("Rs ", "₹")
```

This is an unanchored substring replacement, so the literal `"ADRs in v1"` becomes **`"AD₹in v1"`**.
The corruption is present in all seven Epic 13 issue files (verified in `13.1` … `13.7`, each at
line 22). The fix belongs in the generator's substitution — it needs a word-boundary guard, e.g.
`re.sub(r"\bRs\s+", "₹", text)` — **not** in the generated `.md` files, which the workflow doc
explicitly says never to hand-edit.

**Stale or conflicting documents:**

- [`docs/memory.md`](../memory.md) — the file whose stated purpose is telling any session where the
  project stands — says *"Currently working file: none — no Kotlin/Gradle code exists yet"* and
  *"In progress: nothing"*. Commit `5a39e1f` landed 19 modules. The one document designed to
  prevent state re-derivation is the one giving a wrong answer.
- [`docs/features/README.md`](../features/README.md) carries a full **6-epic** model that conflicts
  with the canonical **13-epic** CSV. It self-acknowledges being superseded but keeps the stale
  table, and it is the only place the `E.F.T` task numbering (`1.1.1`) is defined — so a reader
  needs it and gets contradictory epic numbers from it.
- [`docs/ProjectStructure.md`](../ProjectStructure.md) is 332 lines of accurate, high-value
  documentation sitting **untracked** in git. One `git add` from being lost.

**Missing standard repository files:** no root `README.md` (a newcomer's entry point is a 155-line
binding rulebook), no `LICENSE`, no `CONTRIBUTING.md`, no `.github/ISSUE_TEMPLATE/`.
Minor: `.kotlin/` exists at repo root and matches no `.gitignore` rule — currently invisible only
because the directory is empty.

---

## 10. Remediation backlog

Severity: **P0** blocks correctness of the gates themselves · **P1** material risk · **P2** hygiene.
"Existing issue" means the work is already tracked — do not duplicate it.

Status column: **DONE** = applied 2026-07-25 · **DEFERRED** = tested, cannot be done yet (see §3.1)
· blank = open.

| ID | Sev | Finding | Fix | Effort | Status | Existing issue |
|----|:---:|---------|-----|:------:|:------:|----------------|
| G-01 | P0 | `koverVerify` has no rules — the coverage gate is a no-op | Wired with issue 1.2 in `configureCoverage()`: 85% on pure-Kotlin modules, 100% on `:core:model`. Proved to fail twice (bound 101 → red; test deleted → 77.5%, red) before merging | S | **DONE** | 1.2 |
| G-02 | P0 | `/pre-merge` and `settings.json` invoke `verifyPaparazzi*`, which does not exist | Removed both references until 1.8 lands | XS | **DONE** | 1.8 |
| G-03 | P0 | Lint bans documented in present tense as enforced | `CLAUDE.md` now marks the `GlobalScope`, `System.currentTimeMillis()` and PII-logging bans as review-blocking with the lint rule landing in 1.1.5 | S | **DONE** | 1.1.5 |
| G-04 | P0 | detekt has no length rule; documented 40-line limit unenforced (default 60) | Added `complexity.LongMethod.threshold: 40` to `config/detekt/detekt.yml`; `./gradlew detekt` green | XS | **DONE** | — |
| G-05 | P1 | No dependency update automation | Add `.github/dependabot.yml` for gradle + github-actions | S | — |
| G-06 | P1 | Actions pinned by mutable tag, not SHA | Pin all five actions to full commit SHAs with a version comment | S | — |
| G-07 | P1 | No `CODEOWNERS`; branch protection unrecorded in-repo | Add `.github/CODEOWNERS`; document protection rules in `CONTRIBUTING.md` | XS | — |
| G-08 | P1 | No `SECURITY.md` for a financial app | Add disclosure policy | XS | — |
| G-09 | P1 | Zero local enforcement; Conventional Commits unvalidated | Add lefthook or pre-commit: ktlint on staged Kotlin + commit-msg regex | M | — |
| G-10 | P1 | No agent-side automated checks | Add `PostToolUse` hooks in `.claude/settings.json` for `*.kt` writes | S | — |
| G-11 | P1 | `FR-*`/`SEC-*`/`DB-*`/`NFR-*` exist only in a binary PDF; DoD cites unverifiable IDs | Extract `docs/traceability/requirements.csv`; add a CI validator for cited IDs | M | — |
| G-12 | P1 | Zero ADRs despite ADR-required rules | Write ADR-0001..0005 for the decisions listed in §7 | M | — |
| G-13 | P2 | CI never parses `ai/**/*.yaml` | Extend the existing validator step to YAML | XS | — |
| G-14 | P2 | `ai/` validation is parse-only; duplicate/renamed `rule_id` passes CI | Add JSON Schema under `ai/schema/` + schema validation in CI | M | — |
| G-15 | P2 | No cross-file ID integrity between `rules-kb.json` and `engine-registry.yaml` | Add a referential-integrity check to the CI validator | S | — |
| G-16 | P2 | `check_issue_docs.py` exists but CI never runs it | Add it as a CI step | XS | — |
| G-17 | P2 | Generator corrupts `"ADRs"` → `"AD₹"` in 7 files | Word-boundary guard in `gen_issue_docs.py:1070`, then regenerate | XS | — |
| G-18 | P2 | `docs/memory.md` contradicts the actual repo state | Update to reflect the 19-module skeleton | XS | — |
| G-19 | P2 | `docs/features/README.md` 6-epic model conflicts with the 13-epic CSV | Delete the stale table, keep the `E.F.T` definition | XS | — |
| G-20 | P2 | `docs/ProjectStructure.md` untracked | Commit it | XS | — |
| G-21 | P2 | No root `README.md` / `LICENSE` / `CONTRIBUTING.md` | Add them | S | — |
| G-22 | P2 | Issue status fields carry no information (all `Todo`) | Make the tracker the single status source; drop the field from issue files | S | — |
| G-23 | P2 | `.kotlin/` not git-ignored | Add to `.gitignore` | XS | — |
| G-24 | P2 | No automated accessibility check despite the DoD requiring one | Add Espresso/Compose a11y checks when the first screen lands | M | 1.8 |

---

## 11. The three changes that close most of the risk

If nothing else from this report is actioned, do these.

**1. Make the coverage gate real as the first task of issue 1.2 (G-01).** `koverVerify` passes at 0%
coverage while five governance documents promise "100% money math." It cannot be fixed before 1.2 —
§3.1 shows an impossible bound still passes on the current skeleton — so the gate must be wired in
the same change that introduces `Money`, **and proved to fail** before that change merges. Wiring it
later means retro-fitting coverage onto money math under pressure.

**2. Stop the documentation asserting enforcement that does not exist (G-02, G-03, G-04 — done).**
Mostly editing tense, and the highest-leverage fix in the report because of *who* reads these files.
Agents act on `CLAUDE.md` literally. Every hour a rule is documented as lint-enforced when no lint
rule exists is an hour of false confidence for reviewers and agents alike — and it trains readers to
distrust the rulebook, eroding the value of the parts that *are* enforced.

**3. Add local enforcement and dependency automation (G-05, G-09).** The two lowest-effort items
with the largest ongoing return: a commit hook moves style feedback from minutes to instant and
finally validates the Conventional Commits the project mandates, while Dependabot is the only thing
that will ever tell you a pinned dependency in a finance app has a published CVE.

---

## 12. Answering the question directly

**Is this project at an industry standard?**

For architecture, written rules, planning, and AI-subsystem design — **yes, and it exceeds the norm.**
The `ai/` data layer, `CLAUDE.md`, the ARC-002 guard, and the build-logic setup are better than
typical practice, in places materially so.

For enforcement, supply-chain security, and traceability — **not yet.** Below standard for a
financial application, but with a specific and encouraging shape: nearly every gap is a small,
well-understood, hours-not-weeks fix, and roughly a third are already scheduled in the backlog.
Nothing here requires rearchitecting anything.

The one thing worth internalising beyond any individual fix: **this project's rules are unusually
good, which makes unenforced rules unusually dangerous here.** A team that ignores its own
documentation loses little when that documentation is wrong. A project where humans *and AI agents*
treat `CLAUDE.md` as binding truth pays full price for every rule that promises a gate it does not
have. Close that gap and the governance layer becomes genuinely strong across the board.

---

*Audit method: direct inspection of every cited file at commit `5a39e1f`. Claims about
absent tooling were verified by content search and file globbing across the full tree, not
inferred. No file outside `docs/report/` was modified. The SRS PDF was not re-read for this audit;
requirement-ID claims describe repository state, not SRS content.*
