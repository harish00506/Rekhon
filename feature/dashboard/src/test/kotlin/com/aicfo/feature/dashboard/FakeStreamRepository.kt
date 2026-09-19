package com.aicfo.feature.dashboard

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.data.repository.StreamRepository
import com.aicfo.domain.engines.stream.StreamEngineFactory
import com.aicfo.domain.engines.stream.StreamHistory
import com.aicfo.domain.engines.stream.StreamInput
import com.aicfo.domain.engines.stream.StreamOccurrence
import com.aicfo.domain.engines.stream.StreamProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.LocalDate

/**
 * A scriptable [StreamRepository] for the dashboard's tests (issue 9.1).
 *
 * Why:  every profile it emits comes from the **real** engine, the choice
 *       `FakeOrderOfOperationsRepository` makes — a hand-built `StreamProfile` could hold totals the
 *       engine never produces, and a screen test passing against it would prove nothing.
 * What: a replay-less stream a test can push a profile or a refusal down.
 * Result: the ViewModel can be driven through every state the section renders.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 */
internal class FakeStreamRepository : StreamRepository {
    private val profiles = MutableSharedFlow<Result<StreamProfile, AppError>>(replay = 0, extraBufferCapacity = 8)

    override fun observeStreams(): Flow<Result<StreamProfile, AppError>> = profiles

    /** Pushes the classification of [streams]. Input: [streams]. Output: none. */
    fun emit(streams: List<StreamHistory>) {
        profiles.tryEmit(Ok(classify(streams)))
    }

    /** Pushes a refusal. Input: none. Output: none. */
    fun fail() {
        profiles.tryEmit(Err(AppError.Validation("stream.window")))
    }
}

/**
 * Classifies [streams] with the real engine over the fixture window (issue 9.1).
 * Result: the profile. Input: [streams]. Output: [StreamProfile].
 */
internal fun classify(streams: List<StreamHistory>): StreamProfile =
    when (
        val result =
            StreamEngineFactory.create().classify(
                StreamInput(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-08-31"), streams, nowUtcMillis = 0L),
            )
    ) {
        is Ok -> result.value
        is Err -> error("fixture streams were refused: ${result.error}")
    }

/**
 * Rent on the 1st for six months (FIXED, measured) and one month of utilities (SEMI_FIXED, from the
 * category prior) — so the section shows all its lines, the estimate note included (issue 9.1).
 * Result: two streams. Input: none. Output: `List<StreamHistory>`.
 */
internal fun fixtureStreams(): List<StreamHistory> =
    listOf(
        StreamHistory(
            streamKey = "rent",
            priorKey = "rent",
            occurrences =
                (3..8).map {
                    StreamOccurrence(
                        LocalDate.parse("2026-%02d-01".format(it)),
                        Money(25_000_00L),
                    )
                },
        ),
        StreamHistory(
            streamKey = "utilities",
            priorKey = "utilities",
            occurrences = listOf(StreamOccurrence(LocalDate.parse("2026-08-10"), Money(1_840_00L))),
        ),
    )
