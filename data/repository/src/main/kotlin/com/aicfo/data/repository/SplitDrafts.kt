package com.aicfo.data.repository

import com.aicfo.core.database.entity.TransactionSplitEntity
import com.aicfo.core.model.Money
import com.aicfo.core.model.TransactionSplit
import com.aicfo.core.model.sum
import com.aicfo.core.model.total

/*
 * Split transactions: the drafts a caller supplies, and the rules that decide whether one may be
 * written (issue 3.3; FR-TXN-004).
 *
 * Split out of `TransactionRepository.kt` when that file passed detekt's eleven-function ceiling.
 * The seam is a real one: everything here is about **one amount divided across categories**, and
 * none of it touches a balance — the parent transaction does that, exactly as it would unsplit.
 */

/**
 * What the user entered about a split transaction (issue 3.3; FR-TXN-004).
 *
 * Why:    separate from [TransactionDraft] because a split is not an ordinary transaction with an
 *         extra field: it has **no category of its own** — the lines carry them — and it is only
 *         valid when its lines sum to it. Modelling it as its own type means the compiler refuses a
 *         split with a parent category, and one function decides whether it may be written.
 * Result: the argument to [TransactionRepository.createSplit].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 *
 * **[amount] is signed**, like [TransactionDraft.amount] and unlike [TransferDraft.amount]: a split
 * is one movement in one direction, so the sign means exactly what it always does.
 *
 * Input:  [accountId]; [amount] — MNY-001 paise, signed, non-zero; [lines] — at least two, each
 *         signed the same way and together summing to [amount] exactly; [merchant]; [note].
 * Output: an immutable value.
 */
data class SplitDraft(
    val accountId: String,
    val amount: Money,
    val lines: List<SplitLineDraft>,
    val merchant: String? = null,
    val note: String? = null,
)

/**
 * One line of a split, as the user entered it (issue 3.3; FR-TXN-004).
 * Result: an element of [SplitDraft.lines].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 *
 * Input:  [amount] — MNY-001 paise, signed the same way as the parent, non-zero; [categoryId] —
 *         optional, and `null` for every real profile until issue 4.1; [note] — optional free text.
 * Output: an immutable value.
 */
data class SplitLineDraft(
    val amount: Money,
    val categoryId: String? = null,
    val note: String? = null,
)

/**
 * Rejects a split the user cannot have meant (issue 3.3; FR-TXN-004).
 *
 * Why:    **this function is FR-TXN-004.** "Lines MUST sum exactly to the parent amount (validated,
 *         no rounding drift)" is the last check below, and it is a plain equality of two [Money]
 *         values — no tolerance, no epsilon, because paise are integers and a split that is off by
 *         one paise is off. Nothing here adjusts the user's figures to make them fit: an app that
 *         quietly moved a number to balance a form would be worse than one that refuses.
 *
 *         The three checks before it exist so the last one means something. **Fewer than two lines**
 *         is not a split. **A zero line** contributes nothing and would sit in every category report
 *         saying nothing. **A line signed against its parent** would let ₹600 and −₹400 "sum" to
 *         ₹200 of a ₹200 expense while describing a refund that never happened.
 * Result: the draft with its text trimmed, or `null` when it is unusable. `null` rather than an
 *         exception because §5 forbids exceptions across a layer boundary.
 * Input:  the receiver. Output: `SplitDraft?`.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
internal fun SplitDraft.validated(): SplitDraft? {
    if (accountId.isBlank() || amount == Money.ZERO) return null
    if (lines.size < TransactionRepository.MIN_SPLIT_LINES) return null
    val parentIsOutflow = amount < Money.ZERO
    val linesUsable =
        lines.all { it.amount != Money.ZERO && (it.amount < Money.ZERO) == parentIsOutflow }
    if (!linesUsable || lines.total() != amount) return null
    return copy(
        accountId = accountId.trim(),
        lines = lines.map { it.copy(note = it.note?.trim()?.takeIf(String::isNotBlank)) },
        merchant = merchant?.trim()?.takeIf(String::isNotBlank),
        note = note?.trim()?.takeIf(String::isNotBlank),
    )
}

/**
 * The total of a draft's lines.
 * Why:    the same sum [Iterable.total] computes over stored lines, so the figure validated before
 *         the write and the figure read back afterwards come from one piece of arithmetic —
 *         [Money]'s checked addition (MNY-001).
 * Result: the signed total; [Money.ZERO] for no lines.
 * Input:  the receiver. Output: [Money].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
internal fun List<SplitLineDraft>.total(): Money = map { it.amount }.sum()

/**
 * Names the field that made a split draft invalid (issue 3.3).
 * Why:    `AppError.Validation` carries a field name so the screen can point at what is wrong. The
 *         line-level problems all report `"lines"` because that is the control the user would fix —
 *         the running remainder is what tells them *which* line, and it is already on screen.
 * Result: `"accountId"`, `"amount"`, or `"lines"`.
 * Input:  the receiver. Output: [String].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
internal fun SplitDraft.invalidField(): String =
    when {
        accountId.isBlank() -> "accountId"
        amount == Money.ZERO -> "amount"
        else -> "lines"
    }

/**
 * Converts a stored line into the domain model.
 * Why:    ARC-005 — nothing above `:data:repository` may hold a Room type. Unlike a transaction,
 *         a line has no stored vocabulary to fall off, so this cannot fail.
 * Result: a [TransactionSplit]. Input: the receiver. Output: [TransactionSplit].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
internal fun TransactionSplitEntity.toSplit(): TransactionSplit =
    TransactionSplit(
        id = id,
        transactionId = transactionId,
        amount = Money(amountMinor),
        categoryId = categoryId,
        note = note,
    )
