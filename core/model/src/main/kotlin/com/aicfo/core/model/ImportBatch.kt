package com.aicfo.core.model

/**
 * One ingest run, as the rest of the app sees it (§20.1, §33, ADR-0078).
 *
 * Why:  §33 promises "statement-grade provenance" for imported rows. The storage side is
 *       `import_batches`; this is what a caller gets back when it asks where a transaction came
 *       from. It is a domain model rather than the Room entity because a ViewModel must never see
 *       a Room type (ARC-005), and because the tombstone and the audit timestamps are the
 *       database's business, not an answer to "where did this come from".
 * What: the run's source, when it happened, what period it covered, and how much of it was kept.
 * Result: enough to render a provenance line — "imported from your bank on 3 Oct, covering
 *         1–30 Sep, 42 of 47 lines kept" — without the caller consulting anything else.
 * Changelog: 2026-10-10 — Created (ADR-0078).
 *
 * Input:  [id]; [source] — how the data arrived; [startedAtUtcMillis] — when the app ran the
 *         import (TIM-001); [fetchedAtUtcMillis] — when the data was true at the source, `null`
 *         for a file; [windowStartIsoDate], [windowEndIsoDate] — the period covered, ISO
 *         `yyyy-MM-dd` (TIM-002), `null` when the source stated none; [complete] — false when the
 *         window was truncated; [lineCount] — rows offered; [acceptedCount] — rows kept.
 * Output: an immutable value.
 */
data class ImportBatch(
    val id: String,
    val source: TransactionSource,
    val startedAtUtcMillis: Long,
    val fetchedAtUtcMillis: Long? = null,
    val windowStartIsoDate: String? = null,
    val windowEndIsoDate: String? = null,
    val complete: Boolean = true,
    val lineCount: Int = 0,
    val acceptedCount: Int = 0,
) {
    /**
     * How many of the offered lines did not become transactions.
     * Why:    the number a user actually wants when they ask why an import "missed" rows, and the
     *         one a caller would otherwise recompute at every call site and eventually get wrong.
     * Result: the difference, never negative — the repository refuses a batch claiming it accepted
     *         more lines than it was offered.
     * Input:  none. Output: `Int`.
     * Changelog: 2026-10-10 — Created.
     */
    val skippedCount: Int get() = lineCount - acceptedCount

    init {
        require(id.isNotBlank()) { "an import batch must be identified" }
        require(startedAtUtcMillis > 0L) { "an import's start is UTC epoch millis (TIM-001)" }
        require(lineCount >= 0 && acceptedCount >= 0) { "line counts cannot be negative" }
        require(acceptedCount <= lineCount) {
            "an import cannot keep more lines than it was offered"
        }
    }
}
