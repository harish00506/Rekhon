package com.aicfo.feature.dashboard

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.data.repository.HealthScoreRepository
import com.aicfo.domain.engines.healthscore.HealthInput
import com.aicfo.domain.engines.healthscore.HealthScore
import com.aicfo.domain.engines.healthscore.HealthScoreEngineFactory
import com.aicfo.domain.engines.healthscore.MonthFlow
import com.aicfo.domain.engines.healthscore.ObligationInput
import com.aicfo.domain.engines.healthscore.RunwayInput
import com.aicfo.domain.engines.healthscore.SavingsInput
import com.aicfo.domain.engines.healthscore.ShareInput
import com.aicfo.domain.engines.healthscore.UtilisationInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A [HealthScoreRepository] the test drives (issue 9.4).
 * Why:  the ViewModel is tested for what it does with a score, not for how one is built — that is
 *       the engine's and the repository's own tests.
 * What: a shared flow the test pushes a score or a refusal into.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
internal class FakeHealthScoreRepository : HealthScoreRepository {
    private val scores = MutableSharedFlow<Result<HealthScore, AppError>>(replay = 0, extraBufferCapacity = 8)

    override fun observeHealthScore(): Flow<Result<HealthScore, AppError>> = scores

    /** Pushes the fixture score. Input: [score] — which one. Output: none. */
    fun emit(score: HealthScore = fixtureHealth()) {
        scores.tryEmit(Ok(score))
    }

    /** Pushes a refusal. Input: none. Output: none. */
    fun fail() {
        scores.tryEmit(Err(AppError.Validation("health.runway")))
    }
}

/**
 * A realistic score from the real engine: every signal but protection, each mid-curve (issue 9.4) —
 * 765, Good, goals the biggest lever, protection shown as a dash.
 * Result: the score. Input: [empty] — `true` for a profile with no data at all. Output: [HealthScore].
 */
internal fun fixtureHealth(empty: Boolean = false): HealthScore {
    val input =
        if (empty) {
            HealthInput(nowUtcMillis = 0L)
        } else {
            HealthInput(
                runway = RunwayInput(48_000, 6),
                obligations = ObligationInput(Money(31_50_000L), Money(95_00_000L), 3),
                cards = UtilisationInput(Money(13_80_000L), Money(40_00_000L)),
                savings =
                    SavingsInput(
                        listOf(
                            MonthFlow("2026-06", Money(95_00_000L), Money(19_00_000L)),
                            MonthFlow("2026-07", Money(95_00_000L), Money(24_50_000L)),
                            MonthFlow("2026-08", Money(98_00_000L), Money(12_10_000L)),
                        ),
                    ),
                budgets = ShareInput(5, 7),
                goals = ShareInput(2, 3),
                nowUtcMillis = 0L,
            )
        }
    return when (val result = HealthScoreEngineFactory.create().score(input)) {
        is Ok -> result.value
        is Err -> error("fixture score was refused: ${result.error}")
    }
}
