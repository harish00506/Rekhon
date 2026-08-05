package com.aicfo.feature.dashboard

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.domain.engines.networth.NetWorthResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * A [NetWorthRepository] the dashboard tests drive directly (issue 2.6).
 *
 * Why:  the dashboard only *reads*, and what it has to get right is how it reacts to what comes
 *       back — a figure, or **nothing at all**. The second case is the one worth a fake rather than
 *       a stub: a user who onboarded a minute ago has no snapshot, and the screen must render that
 *       as "not worked out yet" rather than as ₹0 (P-03). A hot [MutableStateFlow] also lets a test
 *       deliver a snapshot *while the screen is open*, which is what happens when the nightly job
 *       lands under a user who left the app on.
 * What: an emittable snapshot; the write side is a no-op this screen never calls.
 * Result: both states the net-worth card can be in are reachable from a unit test.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 *
 * Hand-written rather than mocked, matching `FakeQuickSetupRepository` beside it: a mock would
 * answer "was it called?", and these assertions are about the values.
 */
internal class FakeNetWorthRepository : NetWorthRepository {
    private val latest = MutableStateFlow<NetWorthResult?>(null)

    override fun observeLatest(): Flow<NetWorthResult?> = latest

    /**
     * The live figure — what the dashboard actually observes (issue 2.6).
     * Why: emits only once a test has published one, so `null` in the state means "not yet", which
     *      is the state the screen renders as "not worked out yet".
     */
    override fun observeCurrent(): Flow<NetWorthResult> = latest.filterNotNull()

    override suspend fun computeAsOf(asOfIsoDate: String): Result<NetWorthResult, AppError> = Ok(result(0L))

    override suspend fun snapshotUpToToday(): Result<Int, AppError> = Ok(0)

    /**
     * Publishes a snapshot.
     * Result: collectors see the figure. Input: [netWorthMinor] — paise, signed. Output: none.
     */
    fun emit(netWorthMinor: Long) {
        latest.value = result(netWorthMinor)
    }

    /** Result: a snapshot with the given net worth. Input: [netWorthMinor]. Output: the result. */
    private fun result(netWorthMinor: Long) =
        NetWorthResult(
            asOfIsoDate = "2026-08-01",
            assets = Money(netWorthMinor),
            liabilities = Money.ZERO,
            netWorth = Money(netWorthMinor),
            provenance =
                EngineProvenance(
                    engineId = "net-worth",
                    engineVersion = "1.0",
                    computedAtUtcMillis = 1_785_542_400_000L,
                ),
        )
}
