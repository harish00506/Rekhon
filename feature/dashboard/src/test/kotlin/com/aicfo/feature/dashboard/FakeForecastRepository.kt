package com.aicfo.feature.dashboard

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.data.repository.ForecastRepository
import com.aicfo.domain.engines.forecast.Cadence
import com.aicfo.domain.engines.forecast.CashFlowForecast
import com.aicfo.domain.engines.forecast.Commitment
import com.aicfo.domain.engines.forecast.DailySpend
import com.aicfo.domain.engines.forecast.ForecastEngineFactory
import com.aicfo.domain.engines.forecast.ForecastInput
import com.aicfo.domain.engines.forecast.ItemSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.LocalDate

/**
 * A scriptable [ForecastRepository] for the dashboard's tests (issue 9.2).
 *
 * Why:  every forecast it emits comes from the **real** engine, as `FakeStreamRepository` does — a
 *       hand-built forecast could hold a lowest day the engine never picks, and a screen test passing
 *       against it would prove nothing.
 * What: a replay-less stream a test can push a forecast or a refusal down.
 * Result: the ViewModel can be driven through every state the card renders.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
internal class FakeForecastRepository : ForecastRepository {
    private val forecasts = MutableSharedFlow<Result<CashFlowForecast, AppError>>(replay = 0, extraBufferCapacity = 8)

    override fun observeForecast(): Flow<Result<CashFlowForecast, AppError>> = forecasts

    /** Pushes the fixture forecast. Input: [opening] — paise. Output: none. */
    fun emit(opening: Long = 20_000_00L) {
        forecasts.tryEmit(Ok(fixtureForecast(opening)))
    }

    /** Pushes a refusal. Input: none. Output: none. */
    fun fail() {
        forecasts.tryEmit(Err(AppError.Validation("forecast.spend")))
    }
}

/**
 * A realistic forecast from the real engine: ₹200 a day of steady spend, a salary on the 1st, rent
 * on the 5th (issue 9.2). Steady history means no residuals, so every figure is arithmetic.
 * Result: the forecast. Input: [opening] — paise. Output: [CashFlowForecast].
 */
internal fun fixtureForecast(opening: Long = 20_000_00L): CashFlowForecast {
    val today = LocalDate.parse("2026-09-19")
    val input =
        ForecastInput(
            today = today,
            openingBalance = Money(opening),
            commitments =
                listOf(
                    Commitment(
                        "Employer",
                        Money(60_000_00L),
                        Cadence.MONTHLY,
                        LocalDate.parse("2026-10-01"),
                        ItemSource.RECURRING_RULE,
                    ),
                    Commitment(
                        "Rent",
                        Money(-25_000_00L),
                        Cadence.MONTHLY,
                        LocalDate.parse("2026-10-05"),
                        ItemSource.FIXED_STREAM,
                    ),
                ),
            oneOffs = emptyList(),
            dailySpend = (1..90).map { DailySpend(today.minusDays(it.toLong()), Money(200_00L)) },
            historyStart = today.minusDays(90),
            seed = 1L,
            nowUtcMillis = 0L,
        )
    return when (val result = ForecastEngineFactory.create().forecast(input)) {
        is Ok -> result.value
        is Err -> error("fixture forecast was refused: ${result.error}")
    }
}
