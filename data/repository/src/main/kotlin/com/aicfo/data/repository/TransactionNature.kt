package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.database.dao.MerchantNatureOverrideRow
import com.aicfo.core.model.CategoryNature
import com.aicfo.domain.engines.nature.NatureHistoryRow

// =============================================================================
// The small pieces `RoomTransactionRepository` needs for §8.3 (issue 4.3).
//
// Why:  a file of its own because TransactionRepository.kt sits at detekt's eleven-function ceiling,
//       which issues 3.3 and 3.5 already hit — and the seam is real: everything here is a mapper or
//       an unwrapper, with no query and no decision in it.
// Changelog: 2026-08-10 — Created for issue 4.3.
// =============================================================================

/**
 * Turns a profile's stored overrides for one merchant into the engine's history rows (issue 4.3).
 * Why:    the projection carries a nullable nature string because the column is nullable, while the
 *         engine takes a typed [CategoryNature] — and a stored value this build does not recognise
 *         is **dropped rather than guessed at**, the rule every mapper in this module follows. An
 *         old build reading a newer database learns from fewer corrections, never from wrong ones.
 * Result: the rows the learned step scores. Input: the receiver — one merchant's override counts.
 * Output: `List<NatureHistoryRow>`.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
internal fun List<MerchantNatureOverrideRow>.toHistory(): List<NatureHistoryRow> =
    mapNotNull { row ->
        row.nature?.let { CategoryNature.fromStored(it) }?.let { NatureHistoryRow(it, row.occurrences) }
    }

/**
 * Unwraps a `Result` a `runCatchingToResult` block produced around another `Result` (issue 4.3).
 * Why:    `setNature` has to distinguish "the write failed" from "there was nothing to write", so
 *         the block's value is itself a `Result` and the caller would otherwise be handed
 *         `Result<Result<Unit, AppError>, AppError>`. The same helper `CategoryRepository` carries,
 *         kept file-private in each so the reason travels with the use.
 * Result: the inner result when the outer one succeeded, the storage error otherwise.
 * Input:  the receiver. Output: `Result<T, AppError>`.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
internal fun <T> Result<Result<T, AppError>, AppError>.flattenNature(): Result<T, AppError> =
    when (this) {
        is Ok -> value
        is Err -> this
    }

/**
 * 10 000 bps = 100% (MNY-002).
 *
 * Declared here rather than imported because the engine's copy is `internal` to its module — and
 * that is the right visibility for it. A shared constant would be a `:core:*` type carrying one
 * number, which is more coupling than the number is worth.
 */
internal const val BPS_FULL = 10_000
