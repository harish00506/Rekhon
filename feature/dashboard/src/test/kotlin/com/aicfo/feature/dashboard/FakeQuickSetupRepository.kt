package com.aicfo.feature.dashboard

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.data.repository.ProfileSeed
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.domain.engines.quicksetup.BudgetEnvelope
import com.aicfo.domain.engines.quicksetup.BudgetNature
import com.aicfo.domain.engines.quicksetup.QuickSetupPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [QuickSetupRepository] the dashboard tests drive directly (issue 2.3).
 *
 * Why:  the dashboard only *reads*, and what it has to get right is how it reacts to what comes
 *       back — three envelopes, a partial set, or none at all. A hot [MutableStateFlow] lets a test
 *       change the budget **while the screen is open**, which is the case a fake returning a fixed
 *       list could not reach, and the one that matters: the user arrives here straight from
 *       onboarding and issue 4.4 will edit the budget behind them.
 * What: an emittable envelope list; the write side is a no-op this screen never calls.
 * Result: every state the split can be in is reachable from a unit test.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * Deliberately hand-written rather than mocked, matching `FakeStores.kt` in `:feature:onboarding`:
 * a mock would answer "was it called?", and these assertions are about the values.
 */
internal class FakeQuickSetupRepository : QuickSetupRepository {
    private val envelopes = MutableStateFlow(emptyList<BudgetEnvelope>())

    override suspend fun applySeeds(
        plan: QuickSetupPlan,
        profile: ProfileSeed,
    ): Result<Unit, AppError> = Ok(Unit)

    override fun observeLatestEnvelopes(): Flow<List<BudgetEnvelope>> = envelopes

    /**
     * The profile-scoped read.
     * Why:    returns the same flow as the no-argument form, because the dashboard never calls this
     *         one — it asks for "whatever profile is active" and the real repository resolves that
     *         (issue 2.4). Answering identically keeps this fake from implying a distinction the
     *         screen under test does not make.
     * Result: the same envelopes. Input: [profileId] — ignored. Output: `Flow<List<BudgetEnvelope>>`.
     * Changelog: 2026-07-28 — Issue 2.4: added alongside the no-argument overload.
     */
    override fun observeLatestEnvelopes(profileId: String): Flow<List<BudgetEnvelope>> = envelopes

    /**
     * Publishes a complete budget.
     * Result: collectors see three envelopes. Input: the three amounts in paise. Output: none.
     */
    fun emit(
        needs: Long,
        wants: Long,
        savings: Long,
    ) {
        envelopes.value =
            listOf(
                envelope(BudgetNature.NEED, needs),
                envelope(BudgetNature.WANT, wants),
                envelope(BudgetNature.INVEST, savings),
            )
    }

    /**
     * Publishes a budget with only a needs envelope.
     * Why:    the shape issue 4.4's per-category budgets will produce, where a nature-level row may
     *         simply be absent. The dashboard must fill the gap with a zero weight rather than
     *         treating the whole budget as missing.
     * Result: collectors see one envelope. Input: [needs] — paise. Output: none.
     */
    fun emitNeedsOnly(needs: Long) {
        envelopes.value = listOf(envelope(BudgetNature.NEED, needs))
    }

    private fun envelope(
        nature: BudgetNature,
        minor: Long,
    ) = BudgetEnvelope(nature, Money(minor), RuleCitation("RULE-50-30-20", "1.0"))
}
