package com.aicfo.core.datastore

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Result
import org.junit.Assert.fail

/**
 * Asserts that a store write actually succeeded.
 *
 * Why:  the stores return `Result<Unit, AppError>` instead of throwing (§21.6), which means an
 *       ignored return value is an **invisible** failure. The first version of these tests dropped
 *       the results on the floor: writes were failing, the reads then saw stale data, and the only
 *       symptom was a confusing assertion about the wrong field — while several other tests passed
 *       green over the same broken writes. A test that ignores an error return proves nothing.
 * What: fails the test immediately, naming the error code, when a write returns `Err`.
 * Result: a failed write is reported where it happened rather than as a mysterious read later.
 * Changelog: 2026-07-25 — Created for issue 1.9 after exactly that bug.
 *
 * Input:  [result] — what the write returned. Output: none; fails the test on `Err`.
 */
internal fun assertWritten(result: Result<Unit, AppError>) {
    if (result is Err) {
        fail("store write failed with ${result.error.code}: ${result.error.message}")
    }
}
