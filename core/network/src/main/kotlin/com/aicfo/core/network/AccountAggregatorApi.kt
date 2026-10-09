package com.aicfo.core.network

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money

/**
 * The Account Aggregator ingest contract (issue 13.6; SRS §16, §22, ADR-0074).
 *
 * Why:  AA is the one thing that would close the gap between this app and every Indian competitor
 *       that syncs a bank account — and it is also the largest disclosure the product can ask a
 *       user for, because a regulated third party hands over their whole statement history. So the
 *       interface is written before the implementation, and it is written to make the dangerous
 *       shapes **unavailable** rather than discouraged:
 *
 *       - every call carries a [ConsentHandle], so there is no way to fetch without one;
 *       - a fetch returns [FetchedStatements], which **always** carries `fetchedAtUtcMillis` —
 *         a caller cannot render AA data without knowing how old it is (P-04's staleness label);
 *       - there is no method that returns a bare list of transactions.
 * What: the contract, the consent handle, and the result with its provenance.
 * Result: AC2's interface stub. [UnconfiguredAccountAggregatorApi] is the only implementation that
 *       ships, and it refuses.
 * Changelog: 2026-10-09 — Created for issue 13.6.
 *
 * **Nothing in this file talks to a bank.** AA traffic goes through the app's own backend proxy,
 * never directly to an AA or an FIP, for the same reason market data does (EXT-001): a third-party
 * endpoint the app calls itself is one the user cannot audit and the project cannot pin.
 */
interface AccountAggregatorApi {
    /**
     * Fetches statements for a consent the user has already granted.
     * Why:    one call rather than a session/poll pair, because the backend owns the AA handshake
     *         and the app's job is to ask for data it is already entitled to.
     * Result: `Ok(FetchedStatements)` — possibly empty, always stamped with when it was fetched;
     *         `Err(AppError.Network)` when the backend is unreachable, with `retryable` saying
     *         whether asking again could help; `Err(AppError.Validation)` for a handle this build
     *         cannot use.
     * Input:  [consent] — the handle, which the caller must already hold.
     * Output: `Result<FetchedStatements, AppError>`.
     */
    suspend fun statements(consent: ConsentHandle): Result<FetchedStatements, AppError>
}

/**
 * A reference to a consent the user granted inside the AA framework.
 *
 * Why:  **it holds no account number, no bank name and no token.** An AA consent is identified by a
 *       handle the framework issues; keeping only that means this type can be logged, carried
 *       through a `Result` and held in a UI state without any of them becoming a place a bank
 *       identifier leaks into (P-01, §21.6's logging ban).
 * Input:  [id] — the framework's own consent id; [expiresAtUtcMillis] — when the framework's
 *   consent lapses, which is **not** the same as the app's own [ConsentFeature] switch and may
 *   expire while that is still on.
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.6.
 */
data class ConsentHandle(
    val id: String,
    val expiresAtUtcMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "an AA consent must be identified" }
        require(expiresAtUtcMillis > 0L) { "a consent expiry is UTC epoch millis (TIM-001)" }
    }
}

/**
 * What a fetch returned, and when.
 *
 * Why:  the `fetchedAtUtcMillis` is **not optional**, and that is the design. P-04 requires network
 *       features to degrade to cached data *with a staleness label*, and the reliable way to make a
 *       screen show one is to make it impossible to hold the data without the timestamp. A result
 *       type with a nullable "maybe we know when" would be a label nobody renders.
 * Input:  [lines] — the statement rows, in the order the backend returned them;
 *   [fetchedAtUtcMillis] — UTC epoch millis (TIM-001), from the injected clock at the fetch site;
 *   [complete] — false when the window was truncated, so a caller can say "and there may be more"
 *   rather than quietly showing a partial history as a whole one.
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.6.
 */
data class FetchedStatements(
    val lines: List<StatementLine>,
    val fetchedAtUtcMillis: Long,
    val complete: Boolean,
) {
    init {
        require(fetchedAtUtcMillis > 0L) { "AA data must say when it was fetched (P-04 staleness)" }
    }
}

/**
 * One statement row as the backend hands it over.
 *
 * Why:  deliberately **not** a `Transaction`. A statement line is evidence of what a bank says
 *       happened; a transaction is a row in this user's ledger, with a category, a nature and a
 *       profile. Converting one into the other is a repository's job and involves decisions — dedupe
 *       against existing rows, classification, which account it belongs to — that do not belong in
 *       a network type.
 * Input:  [externalId] — the bank's own reference, which is what deduplication will key on;
 *   [amount] — paise, signed (MNY-001); [bookedOnIsoDate] — ISO `yyyy-MM-dd` (TIM-002);
 *   [narration] — the bank's description, as printed.
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.6.
 */
data class StatementLine(
    val externalId: String,
    val amount: Money,
    val bookedOnIsoDate: String,
    val narration: String,
)
