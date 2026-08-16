package com.aicfo.feature.dashboard

import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.data.repository.SafeToSpendRepository
import com.aicfo.domain.engines.safetospend.SafeToSpend
import com.aicfo.domain.engines.safetospend.SafeToSpendComponent
import com.aicfo.domain.engines.safetospend.SafeToSpendLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

/**
 * A [SafeToSpendRepository] the dashboard tests drive directly (issue 5.2).
 *
 * Why:  the dashboard only *reads*, and what it has to get right is how it reacts to what comes
 *       back — a figure, **nothing at all**, or a failure. All three are states the user will see:
 *       a profile that declared no income has no figure, and this is the screen's headline, so a
 *       silent blank and a raised error are meaningfully different outcomes.
 *
 *       **A [MutableSharedFlow] with no replay, not a [kotlinx.coroutines.flow.MutableStateFlow]**,
 *       unlike `FakeNetWorthRepository` beside it. This stream is what turns `isLoading` off, so a
 *       fake that replayed an initial value would end the loading state before any test could
 *       observe it — and "the screen shows a loading state" is a case §21.5 asks for by name. Here
 *       nothing is emitted until a test says so.
 * What: an emittable figure, an emittable absence, and an emittable failure.
 * Result: every state the Safe-to-Spend card can be in is reachable from a unit test.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * Hand-written rather than mocked, matching the fakes beside it: a mock would answer "was it
 * called?", and these assertions are about the values.
 */
internal class FakeSafeToSpendRepository : SafeToSpendRepository {
    // kotlin.Result, so a test can push a *failure* down the same stream. A repository Flow can
    // fail, the ViewModel has a `.catch` for it, and without this the fake could not reach it.
    private val figures = MutableSharedFlow<Result<SafeToSpend?>>(replay = 0, extraBufferCapacity = 8)

    override fun observeSafeToSpend(): Flow<SafeToSpend?> = figures.map { it.getOrThrow() }

    /**
     * Publishes a figure.
     * Result: collectors see it, and the screen leaves its loading state.
     * Input:  [amountMinor] — paise, signed; [spentMinor] — the one deduction the fixture draws, so
     *         a test can assert the breakdown is rendered rather than only the headline.
     * Output: none.
     */
    fun emit(
        amountMinor: Long,
        spentMinor: Long = 0L,
    ) {
        figures.tryEmit(Result.success(figure(amountMinor, spentMinor)))
    }

    /**
     * Publishes the absence — a profile with no income basis (P-03).
     * Result: collectors see `null`, which the screen renders as "not worked out yet", never as ₹0.
     * Input:  none. Output: none.
     */
    fun emitNoBasis() {
        figures.tryEmit(Result.success(null))
    }

    /**
     * Fails the stream.
     * Result: the ViewModel's `.catch` sets `errorCode` and clears `isLoading` — the case that
     *         would otherwise strand the screen on its loading text with no banner.
     * Input:  none. Output: none.
     */
    fun fail() {
        figures.tryEmit(Result.failure(IllegalStateException("safe-to-spend read failed")))
    }

    /**
     * Result: a figure whose lines add up to [amountMinor], as the engine's own invariant requires.
     * Input:  [amountMinor]; [spentMinor]. Output: [SafeToSpend].
     */
    private fun figure(
        amountMinor: Long,
        spentMinor: Long,
    ): SafeToSpend =
        SafeToSpend(
            amount = Money(amountMinor),
            lines =
                listOf(
                    SafeToSpendLine(SafeToSpendComponent.INCOME, Money(amountMinor + spentMinor)),
                    SafeToSpendLine(SafeToSpendComponent.SPENT, Money(spentMinor)),
                ).filter { it.component == SafeToSpendComponent.INCOME || spentMinor != 0L },
            provenance =
                EngineProvenance(
                    engineId = "safe-to-spend",
                    engineVersion = "1.0",
                    computedAtUtcMillis = 1_785_542_400_000L,
                    evidence = listOf(RuleCitation("RULE-STS", "1.0")),
                    inputWindow = "2026-08-01..2026-08-31",
                ),
        )
}
