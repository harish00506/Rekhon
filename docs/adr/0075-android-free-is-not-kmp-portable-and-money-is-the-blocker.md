<!--
  Why:  CLAUDE.md §5 — any decision needs an ADR, and 13.7's AC2 names one explicitly
        ("a KMP feasibility ADR + a spike sharing one engine exist").
  What: issue 13.7 — the measured gap between "Android-free" and "KMP-portable", why the spike
        ported Money rather than an engine, and what it cost.
  Result: a reader can see why not one of the thirty engines compiles for iOS today, and what the
          first real step of a port would be.
  Changelog: 2026-10-10 — Created.
-->

# ADR-0075 — "Android-free" is not "KMP-portable", and `Money` is the blocker

**Status:** Accepted · **Date:** 2026-10-10 · **Issue:** 13.7 · **SRS:** §27, §33, ARC-002 · **Rules:** MNY-001, MNY-002, P-08

## Context

§33's forward-compatibility table closes with:

> **iOS / KMP** — ARC-002 keeps all engines pure Kotlin; only UI and platform services need porting

ARC-002 is real and enforced: `enforceNoAndroidPlugins()` has failed the build since issue 1.1 if a
pure-Kotlin module applies an Android plugin, and it still does.

This issue's AC1 asks to *"confirm `:core:model` + `:domain:*` stay Android-free **so** they are
KMP-portable"*. That is two claims joined by a "so", and the join is where it fails.

## Decision

### 1 · The two claims are not the same, and the difference is 40 files

A module can be perfectly Android-free and still unusable on iOS, because **Kotlin/Native has no
`java.*` at all**. Measured across `:core:model` and `:domain:*` at 0.13.5:

| | |
|---|---|
| Main Kotlin sources | **139** |
| Importing `java.*` | **40 (29%)** |
| Most common | `java.time.LocalDate` (31), `java.math.BigDecimal` (14), `java.math.RoundingMode` (13) |

So the first half of §33's claim holds and the second does not. **"Only UI and platform services
need porting" is wrong**: a third of the supposedly-portable layer is bound to the JVM.

This is not an argument. The spike's negative control proves it with a compiler: adding the exact
`import java.math.BigDecimal` that `Money` uses to a common source compiles for the JVM target and
fails for Kotlin/Native with `Unresolved reference 'java'`.

### 2 · The spike ports `Money`, not an engine — because every engine sits behind it

AC2 asks for "a spike sharing one engine". Six engines are already free of `java.*`
(`budget`, `classification`, `nature`, `networth`, `safetospend`, `simulator`, plus
`:domain:usecase`), so one could have been nominated and declared shared.

That would have been worthless. **Every one of the thirty engines depends on `:core:model`, and
`Money` uses `BigDecimal` for every rounded division.** A Kotlin/Native build needs the whole
dependency graph to compile, so no engine is portable until `Money` is. Porting a leaf would have
proved only that the leaf was already clean.

So `:spike:kmp` ports the blocker:

- `commonMain` holds `PortableMoney` — `percentOf`, `split`, `allocate` and the half-even division
  they rest on, in common Kotlin with no `java.*`.
- `commonTest` holds the invariants, and **Gradle runs them twice**: once on the JVM and once on
  `linuxX64`. Sixteen tests, green on both.
- `jvmTest` compares `PortableMoney` against the **real `Money`** over roughly 16,000 seeded cases —
  `BigDecimal`/`HALF_EVEN` on one side, hand-rolled integer arithmetic on the other. They agree,
  including on exact ties, where a half-up implementation would diverge.

**`linuxX64`, not an iOS target, and that is stated rather than glossed.** iOS targets can only be
built on macOS; this machine is Linux. Both are Kotlin/Native and share the same standard library,
so a `java.*` import fails identically on either — the portability question is answered the same
way, and only the final linking differs. **This spike does not prove an iOS build. It proves the
arithmetic survives Kotlin/Native**, which is the part that was in doubt.

### 3 · What losing `BigDecimal` costs: a bounded range

`Money.percentOf` multiplies by basis points inside an unbounded `BigDecimal`. `PortableMoney` has
no such intermediate — common Kotlin has no 128-bit integer — so `minor × bps` must fit in a `Long`.
It is exact while `|minor| ≤ Long.MAX / bps`, which at the maximum rate of 10 000 bps is about
**₹92 billion**. Beyond that it throws rather than wrapping.

That is far outside any household figure this app will hold, and it is **not** the unbounded
guarantee `BigDecimal` gives. A production port owes an explicit decision: accept the bound and
document it, or carry a 128-bit multiply. There is no third option in common Kotlin.

### 4 · The audit is pinned, so it cannot rot

`PortabilityAuditTest` counts the JVM-bound files and **fails when the number moves in either
direction**. Up means somebody added a `java.*` import to a layer that must one day run on
Kotlin/Native. Down means a port is making progress and this ADR's figures need updating.

It also names the six clean modules individually — losing one should be a failure, not a drift —
and asserts `Money` still uses `BigDecimal`, so the day that test fails, the single largest obstacle
to iOS is gone.

## Consequences

- `:spike:kmp` exists and **nothing depends on it**. It is not in the app's dependency graph and
  ships in no artefact.
- The Kotlin Multiplatform plugin is applied **without a version and without a catalog alias**: it
  is already on the build classpath via `build-logic`'s `kotlin-gradle-plugin`, and asking for it by
  alias fails with "already on the classpath with an unknown version". No new dependency enters the
  build.
- Building `linuxX64` downloads the Kotlin/Native toolchain (LLVM, libffi, lldb) into `~/.konan` —
  about a gigabyte, once. CI would need that cached before this module could gate anything, which is
  why it does not gate anything today.

## What an iOS port would actually take

In order, because each step unblocks the next:

1. **`Money` and `MoneyFormatter`** — `BigDecimal`, `BigInteger`. The spike shows how, and what it
   costs.
2. **`Clock` and `DateFormatter`** — `java.time`. The largest group by file count (31 files use
   `LocalDate`). `kotlinx-datetime` is the obvious candidate and would be a real dependency decision.
3. **`IdGenerator`** — `java.util.UUID`.
4. **`AppError.toAppError()`** — maps `IOException`/`GeneralSecurityException`, so it needs an
   `expect`/`actual` split.
5. Only then do the engines become portable, and the six clean ones come free.

Nothing above is in this issue's scope. The issue asked for feasibility and a spike; the answer is
**feasible, and larger than §33 implies**.

## Alternatives considered

- **Nominate one of the six clean engines and call it shared.** Rejected, §2: it would satisfy the
  AC's wording while proving nothing, because the engine still could not compile for Native.
- **Port `java.time` first**, since it is the biggest group. Rejected: `Money` blocks *every* module
  including those 31, and `kotlinx-datetime` is a dependency decision that deserves its own issue.
- **Target an iOS simulator.** Not possible on Linux, §2.
- **Keep `BigDecimal` via a multiplatform big-decimal library.** Considered and left open: it would
  remove the bound in §3 at the cost of a dependency on the hottest path in the app. The spike's
  point is that the arithmetic works without one; whether to take it is a port-time decision.
