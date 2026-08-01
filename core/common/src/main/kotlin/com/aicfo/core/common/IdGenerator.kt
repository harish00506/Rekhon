package com.aicfo.core.common

import java.util.UUID

/**
 * The app's source of new row ids — the only sanctioned way to mint one (issue 2.5; P-08).
 *
 * Why:  every id the app has created so far has been **derived** — `budgetId()` builds
 *       `<profile>:budget:<nature>:<period>` precisely so a re-run of onboarding updates the same
 *       rows instead of accumulating duplicates. Accounts cannot work that way: a user may
 *       legitimately own two accounts both called "HDFC Savings", so the id has to be generated,
 *       and generation means randomness. P-08 allows randomness only from an **injected, seedable
 *       source**, because a class that calls `UUID.randomUUID()` directly can never be given a
 *       fixed input and so can never have a deterministic test.
 *
 *       This is deliberately the same shape as [Clock]: one narrow interface, one production
 *       implementation that is the single call site of the non-deterministic primitive, and a fake
 *       in `testFixtures` that every module injects rather than writing its own.
 * What: one method that returns a fresh, unique id.
 * Result: id creation is reproducible in tests and unique in production.
 * Changelog: 2026-07-28 — Created for issue 2.5 (FR-ACC-001).
 */
interface IdGenerator {
    /**
     * Mints a new id.
     * Why:    the prefix is not decoration — an id that says what it names makes a database dump
     *         and a stack trace readable, and it matches the shape derived ids already have
     *         (`local:budget:need:2026-07-01`). It is **not** a uniqueness mechanism; the random
     *         part is.
     * Result: a value no previous call has returned.
     * Input:  [prefix] — what kind of row this names, e.g. `account`.
     * Output: a [String] id.
     */
    fun newId(prefix: String): String
}

/**
 * The production [IdGenerator] — the one place allowed to call [UUID.randomUUID].
 *
 * Why:    P-08 has to be enforceable, which means exactly one call site of the random primitive in
 *         the codebase, the same way [SystemClock] is the one call site of the wall clock.
 * What:   a type-4 UUID behind the given prefix.
 * Result: collision-free ids without a coordinator, which is what an offline-first app needs —
 *         there is no server to hand out sequence numbers (P-04).
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * Input:  none. Output: an [IdGenerator] over the platform's secure random UUID source.
 */
class UuidIdGenerator : IdGenerator {
    /** Input: [prefix] — the row kind. Output: `"<prefix>:<uuid>"`, unique per call. */
    override fun newId(prefix: String): String = "$prefix:${UUID.randomUUID()}"
}
