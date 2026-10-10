<!--
  Why:  CLAUDE.md §10 — one session file per working session, holding the full reasoning the root
        records only point at.
  What: issue 13.7 — why "Android-free" turned out not to mean "KMP-portable", why the spike ported
        Money instead of an engine, and the orphaned test the work uncovered.
  Result: a reader can see what an iOS port would actually cost, and why the answer came from a
          compiler rather than from reading the module graph.
  Changelog: 2026-10-10 — Created.
-->

# 2026-10-10 — iOS via KMP (issue 13.7)

Branch `feature/13-7-ios-via-kmp` off `dev` (`b582a01`). Version 0.13.5 → **0.13.6**
(versionCode 70). Schema unchanged. **Epic 13 completes with this issue.**

---

## 1 · The acceptance criterion contains a "so", and the "so" is wrong

AC1: *"Confirm `:core:model` + `:domain:*` stay Android-free **so** they are KMP-portable
(ARC-002)."*

Two claims joined by a conjunction. The first is true and guarded: `enforceNoAndroidPlugins()` has
failed the build since issue 1.1 if a pure-Kotlin module applies an Android plugin.

The second does not follow. **Kotlin/Native has no `java.*` at all.** A module can be perfectly
Android-free and still import `java.time.LocalDate`, `java.math.BigDecimal` or `java.util.UUID` —
none of which exist on the platform an iOS port runs on.

Measured at 0.13.5:

| | |
|---|---|
| Main Kotlin sources in `:core:model` + `:domain:*` | **139** |
| Importing `java.*` | **40 (29%)** |
| Most common | `LocalDate` (31), `BigDecimal` (14), `RoundingMode` (13) |

So §33's forward-compatibility row — *"ARC-002 keeps all engines pure Kotlin; only UI and platform
services need porting"* — is wrong in its second half. That is the fourth §33 claim this epic has
audited, and the third found broken.

---

## 2 · Proved with a compiler, not an argument

A count of imports is an argument. The spike turned it into evidence:

Adding **`Money`'s own** `import java.math.BigDecimal` to a common source gives:

| Target | Result |
|---|---|
| `jvm` | `BUILD SUCCESSFUL` |
| `linuxX64` (Kotlin/Native) | `e: Unresolved reference 'java'` |

That single experiment settles it. The shipped `Money` cannot compile for iOS.

---

## 3 · Spike the blocker, not the easy thing behind it

AC2 asks for "a spike sharing one engine". Six engines are already free of `java.*` — `budget`,
`classification`, `nature`, `networth`, `safetospend`, `simulator`, and `:domain:usecase`. One could
have been nominated, declared shared, and the AC ticked.

**It would have proved nothing.** Kotlin/Native needs the *whole dependency graph* to compile, every
one of the thirty engines depends on `:core:model`, and `Money` uses `BigDecimal`. No engine is
portable until Money is.

So the spike ports Money:

- `commonMain` — `PortableMoney`: `percentOf`, `split`, `allocate`, and the half-even division they
  rest on, in common Kotlin with no `java.*`.
- `commonTest` — 16 invariants, which Gradle compiles **twice**. A green `linuxX64Test` is the
  result; a green `jvmTest` alone would not have been.
- `jvmTest` — 5 tests comparing it to the **real `Money`** over ~16,000 seeded cases. `BigDecimal`
  with `HALF_EVEN` on one side, hand-rolled integer arithmetic on the other. They agree, including
  on exact ties, where a half-up implementation would diverge.

**`linuxX64`, not an iOS target, and the ADR says so plainly.** iOS targets need macOS; this machine
is Linux. Both are Kotlin/Native and share a standard library, so `java.*` fails identically on
either — the portability question is answered the same way and only the linking differs. The spike
does **not** claim to prove an iOS build.

*The general lesson: when a dependency graph has one root, spiking a leaf measures nothing.*

---

## 4 · What losing `BigDecimal` costs

`Money.percentOf` multiplies inside an unbounded `BigDecimal`. `PortableMoney` has no such
intermediate — **common Kotlin has no 128-bit integer** — so `minor × bps` must fit in a `Long`.

Exact while `|minor| ≤ Long.MAX / bps`: about **₹92 billion** at the maximum rate. Beyond that it
throws rather than wrapping silently.

Far outside any household figure, and **not** the unbounded guarantee `BigDecimal` gives. A real
port owes an explicit decision: accept the bound, or carry a 128-bit multiply. There is no third
option, and pretending otherwise is how a money bug ships.

---

## 5 · A test nothing ran — found by its own task's comment

`unitTests` depends on every module task named `test`, `testDebugUnitTest` or
`verifyPaparazziDebug`. **A KMP module has none of those** — its tests are `jvmTest` and
`linuxX64Test`.

So the spike's 21 tests were orphaned. `--dry-run | grep -c spike` returned **0**.

The task's own comment, two lines below the matcher, reads: *"a test nothing runs is this project's
recurring defect."* It was right again.

`jvmTest` is now wired in — the equivalence check and the portability audit gate every build. The
**Kotlin/Native half deliberately is not**: `linuxX64Test` needs about a gigabyte of toolchain in
`~/.konan` that CI does not cache, so it runs on demand, and the ADR says so rather than leaving a
CI failure for somebody to discover.

---

## 6 · Two Gradle fights worth recording

**The KMP plugin could not be taken from the version catalog.** `alias(libs.plugins.kotlin.multiplatform)`
fails with *"already on the classpath with an unknown version"* — the plugin ships inside
`kotlin-gradle-plugin`, which `build-logic` already depends on. Applied by plain id, catalog entry
reverted: **nothing new enters the build.**

**The audit's inputs had to be source files, not directories.** `inputs.dir("core/model")` was
rejected because it contains `core/model/build/`, another task's output. A `fileTree` with an
include pattern failed the same way — the *base* directory still overlaps. Resolved by enumerating
the concrete `src/main` directories at configuration time. Verified both ways: adding a `java.*`
import to a clean engine turns the task red rather than leaving it UP-TO-DATE.

---

## 7 · What an iOS port would actually take

In order, because each unblocks the next:

1. **`Money`, `MoneyFormatter`** — `BigDecimal`, `BigInteger`. The spike shows how and what it costs.
2. **`Clock`, `DateFormatter`** — `java.time`. The biggest group: 31 files use `LocalDate`.
   `kotlinx-datetime` is the candidate and is a real dependency decision.
3. **`IdGenerator`** — `java.util.UUID`.
4. **`AppError.toAppError()`** — maps JVM exception types; needs an `expect`/`actual` split.
5. Then the engines become portable, and the six clean ones come free.

**Feasible, and larger than §33 implies.**

---

## 8 · Code changed this session

| Path | What it does now |
|------|------------------|
| `spike/kmp/` | **New KMP module.** `PortableMoney` in `commonMain`; 16 common tests run on JVM **and** `linuxX64`; 5 equivalence tests and 5 audit tests on the JVM |
| `build.gradle.kts` | `unitTests` also matches `jvmTest`, so the spike is not orphaned |
| `settings.gradle.kts` | `:spike:kmp` registered, with a note that `:spike:*` never ships |
| `docs/adr/0075-*.md` | The ADR |

---

## 9 · Quiz

1. **Why isn't "Android-free" enough for iOS?** Kotlin/Native has no `java.*`, and 40 of 139 files
   in the portable layer import it.
2. **Why port `Money` rather than a clean engine?** All thirty engines depend on `:core:model`;
   Native needs the whole graph. A leaf proves nothing.
3. **What does the port cost?** A bounded range (~₹92 billion), because common Kotlin has no
   128-bit integer to replace `BigDecimal`'s unbounded intermediate.
4. **Why `linuxX64` and not an iOS target?** iOS needs macOS. Both are Kotlin/Native with the same
   stdlib, so `java.*` fails identically — but the spike does not claim to prove an iOS build.
5. **Why were the spike's tests invisible to `unitTests`?** It matches `test`/`testDebugUnitTest`,
   and a KMP module names its tests by target.
