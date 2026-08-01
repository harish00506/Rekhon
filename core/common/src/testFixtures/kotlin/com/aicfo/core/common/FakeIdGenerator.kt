package com.aicfo.core.common

/**
 * An [IdGenerator] a test can predict completely.
 *
 * Why:  P-08 requires fixed input to give fixed output, and a repository that mints ids from
 *       `UUID.randomUUID()` has no fixed output at all — a golden-file assertion over its rows
 *       could never be written. This makes the id an argument, exactly as [FakeClock] makes time
 *       one. It ships from `testFixtures` so every module injects the same double rather than
 *       writing its own.
 * What: a monotonic counter behind the caller's prefix.
 * Result: the first account created in any test is always `account:1`, so a test can name the row
 *       it just wrote instead of fishing it back out to learn its id.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * Not thread-safe, deliberately, for the same reason [FakeClock] is not: a test minting ids from
 * two threads has a determinism problem the fake should not paper over.
 *
 * Input:  [startAt] — the first number handed out, so a test needing two independent generators can
 *         keep their ids apart. Output: a predictable [IdGenerator].
 */
class FakeIdGenerator(startAt: Int = 1) : IdGenerator {
    private var next: Int = startAt

    /** How many ids have been handed out — lets a test assert that nothing minted an id. */
    var issuedCount: Int = 0
        private set

    /** Input: [prefix] — the row kind. Output: `"<prefix>:<n>"`, n incrementing from `startAt`. */
    override fun newId(prefix: String): String {
        issuedCount++
        return "$prefix:${next++}"
    }
}
