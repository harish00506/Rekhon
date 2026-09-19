package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Result
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.dao.NatureCandidateRow
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.stream.StreamEngine
import com.aicfo.domain.engines.stream.StreamHistory
import com.aicfo.domain.engines.stream.StreamInput
import com.aicfo.domain.engines.stream.StreamOccurrence
import com.aicfo.domain.engines.stream.StreamProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate

/**
 * AI-CLS Stage 2 over the ledger (issue 9.1; SRS §8.2, ARC-005).
 *
 * Why:  the engine scores streams; something has to say what a stream **is** in this app's data.
 *       That is a join over three tables — the ledger, the categories and the recurring rules — so
 *       it belongs here, the only layer allowed to touch a DAO, and the engine stays pure.
 * What: the active profile's expense rows over the last [WINDOW_MONTHS] **closed** months, one stream
 *       per Stage-1 category, handed to the engine with each category's prior and whether a
 *       confirmed recurring outflow names it.
 * Result: the stream profile the dashboard shows and the forecast (9.2) and health score (9.4) read.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 */
interface StreamRepository {
    /**
     * Observes the active profile's stream classification.
     *
     * Why:    **closed months only**, the window `observeMonthlyLedger` uses: the live month is half
     *         spent, and a rent that has not come round yet would read as a missed month. The window
     *         moves when the month turns or the profile changes, like every windowed read here.
     *
     *         **A stream is a Stage-1 category**, the "category" half of §8.2's "merchant-series or
     *         category". Classifying by merchant would split one landlord paid through two apps into
     *         two streams and join a supermarket's groceries and its electronics aisle into one; the
     *         category is the unit the user already chose and corrects (ADR-0042).
     * Result: `Ok(profile)` on every change to the rows it reads; `Err` only for an input the
     *         engine refuses, which the ledger's own constraints should make unreachable.
     * Input:  none — the active profile. Output: `Flow<Result<StreamProfile, AppError>>`.
     */
    fun observeStreams(): Flow<Result<StreamProfile, AppError>>

    companion object {
        /**
         * How many closed months §8.2 reads ("over last 6 months"). Mirrored from
         * `stream_classification.window_months`; `StreamKbDriftTest` holds the two together.
         */
        const val WINDOW_MONTHS = 6

        /** The stream key for expenses with no category — its own stream, with no prior. */
        const val UNCATEGORISED = "uncategorised"

        /**
         * Prefix of a stream made of one confirmed recurring rule's payments, followed by the
         * normalised merchant (trimmed, lower-cased — the recurring detector's own key, issue 3.7).
         */
        const val RECURRING_PREFIX = "recurring:"
    }
}

/**
 * The Room-backed [StreamRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 * Result: the implementation injected into the dashboard.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 *
 * Input:  [database]; [engine] — AI-CLS Stage 2; [clock] — resolves the window in the profile zone
 *         (TIM-001); [dispatchers]; [activeProfileId] — the demo reads the demo (ADR-0006).
 * Output: a working repository.
 */
internal class RoomStreamRepository(
    private val database: CfoDatabase,
    private val engine: StreamEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : StreamRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeStreams(): Flow<Result<StreamProfile, AppError>> =
        activeProfileId.flatMapLatest { profileId ->
            val liveMonthStart = LocalDate.parse(MonthWindow.current(clock.today()).startIsoDate)
            val windowStart = liveMonthStart.minusMonths(StreamRepository.WINDOW_MONTHS.toLong())
            val windowEnd = liveMonthStart.minusDays(1)
            combine(
                database.transactionDao().observeNatureCandidates(
                    profileId,
                    windowStart.toString(),
                    windowEnd.toString(),
                ),
                database.recurringRuleDao().observeForProfile(profileId),
            ) { rows, rules ->
                engine.classify(
                    StreamInput(
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        streams = streamsOf(profileId, rows, Obligations.of(rules)),
                        nowUtcMillis = clock.nowUtcMillis(),
                    ),
                )
            }.flowOn(dispatchers.io)
        }

    /**
     * Groups expense rows into streams: one per confirmed recurring merchant, then one per category.
     * Why:    `observeNatureCandidates` already yields **one row per unsplit transaction and one per
     *         live split line** (ADR-0018), so a split payment arrives attributed to its lines'
     *         categories and is counted once. Only outflowing expenses are streams: income and
     *         transfers are not spending, and a refund booked as a positive expense is not an outflow.
     *
     *         **A payment to a confirmed recurring merchant is its own stream**, FIXED by CLS-STR-002
     *         — §8.2 says "*transactions* linked to … recurring rules", and the link is the merchant:
     *         the detector (issue 3.7) keys a rule by merchant and stores no category. The rest of
     *         that category stays a stream of its own and is scored on its own rows, so a plumber's
     *         one-off bill is not made FIXED by sitting in the same category as the rent.
     * Result: the streams. Input: [profileId]; [rows]; [obligations]. Output: `List<StreamHistory>`.
     * Changelog: 2026-09-19 — Created for issue 9.1; the same day, recurring merchants split out after
     *            the device run showed a detected rule carries no category (ADR-0042).
     */
    private fun streamsOf(
        profileId: String,
        rows: List<NatureCandidateRow>,
        obligations: Obligations,
    ): List<StreamHistory> =
        rows
            .filter { it.type == EXPENSE && it.amountMinor < 0L }
            .groupBy { row ->
                obligations.merchantKeyOf(row.merchant) ?: row.categoryId ?: StreamRepository.UNCATEGORISED
            }
            .map { (key, streamRows) ->
                val recurring = key.startsWith(StreamRepository.RECURRING_PREFIX)
                StreamHistory(
                    streamKey = key,
                    priorKey = if (recurring) null else priorKeyOf(profileId, key),
                    occurrences =
                        streamRows.map {
                            StreamOccurrence(
                                LocalDate.parse(it.bookedOnIsoDate),
                                Money(-it.amountMinor),
                            )
                        },
                    isKnownObligation = recurring || key in obligations.categories,
                )
            }

    /**
     * The `category_defaults` key a category was seeded from, which names its cold-start prior.
     * Why:    `CategoryRepository` seeds `<profile>:category:<key>`, so the key is recoverable from
     *         the id without a column — a renamed category keeps its prior, and one the user created
     *         has none, which is the honest answer (the engine then says "estimate").
     * Result: the key, or `null`. Input: [profileId]; [categoryId]. Output: `String?`.
     */
    private fun priorKeyOf(
        profileId: String,
        categoryId: String,
    ): String? = categoryId.removePrefix("$profileId:${CategoryRepository.ID_PREFIX}:").takeIf { it != categoryId }

    private companion object {
        const val EXPENSE = "expense"
    }
}

/**
 * What §8.2's known-obligation override applies to (issue 9.1; CLS-STR-002).
 *
 * Why:  a recurring rule is the one obligation this app records, and it names its target in one of
 *       two ways. A rule the **detector** proposed and the user confirmed (issue 3.7) names a
 *       merchant and no category; a rule **quick setup** seeded (issue 2.3) names a category. Both
 *       are honoured. Unconfirmed proposals, dismissed and deleted rules, and income rules (a
 *       positive amount) are not obligations. Loan EMIs and insurance premia — §8.2's other two
 *       examples — have no such link in this schema yet (ADR-0042).
 * What: the categories and the normalised merchant names live, confirmed outflow rules name.
 * Result: what `RoomStreamRepository.streamsOf` groups and flags by.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 *
 * Input:  [categories] — category ids; [merchants] — trimmed, lower-cased merchant names.
 * Output: an immutable value.
 */
internal data class Obligations(
    val categories: Set<String>,
    val merchants: Set<String>,
) {
    /**
     * The stream key for a row paid to a confirmed recurring merchant.
     * Result: `recurring:<merchant>`, or `null` when the merchant is not one. Input: [merchant].
     * Output: `String?`.
     */
    fun merchantKeyOf(merchant: String?): String? =
        merchant?.let(::normalise)?.takeIf { it in merchants }?.let { StreamRepository.RECURRING_PREFIX + it }

    companion object {
        /**
         * Reads the obligations out of the profile's recurring rules.
         * Result: the live, confirmed, outflow rules' categories and merchants. Input: [rules].
         * Output: [Obligations].
         */
        fun of(rules: List<RecurringRuleEntity>): Obligations {
            val live =
                rules
                    .filter { it.isConfirmed && it.dismissedAtUtcMillis == null && it.deletedAtUtcMillis == null }
                    .filter { it.amountMinor < 0L }
            return Obligations(
                categories = live.mapNotNull { it.categoryId }.toSet(),
                merchants = live.mapNotNull { it.name?.let(::normalise) }.filter { it.isNotEmpty() }.toSet(),
            )
        }

        /** The recurring detector's own merchant key (issue 3.7): trimmed, lower-cased. */
        private fun normalise(merchant: String): String = merchant.trim().lowercase()
    }
}
