package com.aicfo.domain.engines.investment

import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.RuleCitation
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Whether a stored price is still worth believing (issue 6.5; §16 EXT-002, P-02, P-04).
 *
 * Why:  a price with no age is a figure the reader has no way to check. Before this, a price typed
 *       in July still read as fact in September — the screen showed a number and said nothing about
 *       when anyone last looked. Offline, the last cached value is the only value there is (P-04),
 *       so the answer is never to hide it; it is to say how old it is and let the user judge.
 * What: the three states a stored price can be in, and whether it is due a refresh.
 * Result: what the holdings screen renders beneath the value.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * @property verdict which of the three states this price is in.
 * @property pricedOnIsoDate the day the market priced it, echoed so the label needs no second
 *   lookup; `null` exactly when [verdict] is [PriceVerdict.NEVER_PRICED].
 * @property ageDays whole days from [pricedOnIsoDate] to today, clamped at 0; `null` exactly when
 *   [verdict] is [PriceVerdict.NEVER_PRICED].
 * @property refreshDue whether the fetch stamp is older than this class's refresh interval — the
 *   question "is it worth a network call", which is not the same question as "is it stale".
 * @property citation the rulebook row that drew both lines (P-02).
 * @property provenance which engine and version decided this (AI-ARC-003).
 */
data class PriceFreshness(
    val verdict: PriceVerdict,
    val pricedOnIsoDate: String?,
    val ageDays: Int?,
    val refreshDue: Boolean,
    val citation: RuleCitation,
    val provenance: EngineProvenance,
) {
    init {
        require((verdict == PriceVerdict.NEVER_PRICED) == (pricedOnIsoDate == null)) {
            "A priced holding has a date and an unpriced one has neither — never one without the other"
        }
        require((pricedOnIsoDate == null) == (ageDays == null)) {
            "An age is only meaningful with a date to measure it from"
        }
        require(ageDays == null || ageDays >= 0) {
            "An age is clamped at zero: a price dated in the future is a clock or a typo, and a " +
                "negative number of days old is not something to show anyone, was $ageDays"
        }
    }
}

/**
 * The three states a stored price can be in (issue 6.5; P-03).
 *
 * Why:  an enum rather than a boolean because there are genuinely three answers and the third is
 *       not a kind of `false`. "Never priced" is not a stale price — it is the absence of one, and
 *       the user needs a different sentence for it (P-03: absent is never zero, and never a
 *       degenerate case of present).
 * What: never priced, fresh, stale.
 * Result: what the UI switches on to choose its wording.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
enum class PriceVerdict {
    /** No price has ever been recorded. The screen invites one rather than reporting an age. */
    NEVER_PRICED,

    /** Priced within this class's threshold. The date is shown, without alarm. */
    FRESH,

    /** Older than this class's threshold. The date is shown with how old it is. */
    STALE,
}

/**
 * Everything the freshness verdict is a function of (issue 6.5).
 *
 * Why:  **[todayIsoDate] and [nowUtcMillis] are arguments, never read here** (TIM-001). That is the
 *       same discipline every other input to this engine keeps, and it is what lets a fixed input
 *       produce a fixed output (P-08) even though the question — "is this old?" — is definitionally
 *       about the present. The caller owns the clock; this owns the arithmetic.
 *
 *       This is deliberately a **separate input type from `HoldingInput`**, and no `asOf` was added
 *       to that one. `HoldingInput`'s doc says there is deliberately no such parameter, because a
 *       money-weighted return that moved with the calendar would be a P-08 violation invisible to
 *       any single run. Freshness is the one question here that *should* move with the calendar, so
 *       it gets its own door rather than widening that one.
 *
 * @property assetClass which class's thresholds apply.
 * @property pricedOnIsoDate the day the market priced it, or `null` if never priced.
 * @property fetchedAtUtcMillis when this device last fetched a price, or `null` if hand-typed.
 * @property todayIsoDate today in the profile's zone, ISO `yyyy-MM-dd` (TIM-002).
 * @property nowUtcMillis the instant, for the refresh-due comparison and for provenance.
 * @property rules the thresholds, defaulting to the shipped rulebook values.
 */
data class PriceFreshnessInput(
    val assetClass: AssetClass,
    val pricedOnIsoDate: String?,
    val fetchedAtUtcMillis: Long?,
    val todayIsoDate: String,
    val nowUtcMillis: Long,
    val rules: PriceFreshnessRules = PriceFreshnessRules(),
)

/**
 * The freshness arithmetic (issue 6.5).
 *
 * Why:  kept out of [DefaultInvestmentEngine] for the reason [CashFlows], [Xirr] and [Allocation]
 *       are — the engine class assembles results and stamps provenance, and mixing date arithmetic
 *       into that is how an off-by-one ends up somewhere nobody looks for it.
 * What: the age in days, the verdict, and whether a refresh is due.
 * Result: everything [PriceFreshness] carries except its provenance.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
internal object Freshness {
    /**
     * Decides the three answers.
     * Why:    an unparseable date is treated as never priced rather than thrown. The model refuses
     *         a bad date on the way in, so one here means a row written by a build that did not —
     *         and blanking the label is a better failure than taking the screen down.
     * Result: the verdict, the age and whether a fetch is due.
     * Input:  [input] — the dates, the class and the thresholds.
     * Output: a [Triple] the engine copies into [PriceFreshness].
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    fun of(input: PriceFreshnessInput): Triple<PriceVerdict, Int?, Boolean> {
        val refreshDue = refreshDue(input)
        val priced = input.pricedOnIsoDate?.let(::parse)
        val today = parse(input.todayIsoDate)
        // Clamped at zero. A price dated tomorrow is clock skew or a typo in the editor, and
        // "-1 days old" is not a thing to put in front of anyone.
        val age =
            if (priced == null || today == null) {
                null
            } else {
                ChronoUnit.DAYS.between(priced, today).coerceAtLeast(0L).toInt()
            }

        return Triple(verdict(age, input), age, refreshDue)
    }

    /**
     * The verdict for an age.
     * Result: [PriceVerdict.NEVER_PRICED] when there is no age, else fresh or stale against this
     *         class's threshold.
     * Input:  [age] — whole days, or `null`; [input] — for the class and the thresholds.
     * Output: the verdict.
     */
    private fun verdict(
        age: Int?,
        input: PriceFreshnessInput,
    ): PriceVerdict =
        when {
            age == null -> PriceVerdict.NEVER_PRICED
            age > input.rules.staleAfterDaysFor(input.assetClass) -> PriceVerdict.STALE
            else -> PriceVerdict.FRESH
        }

    /**
     * Parses an ISO date, treating an unparseable one as absent.
     * Why:    `InvestmentHolding` refuses a bad date on the way in, so one here means a row written
     *         by a build that did not. Blanking the label is a better failure than taking the
     *         holdings screen down over a string.
     * Result: the date, or `null`. Input: [isoDate]. Output: [LocalDate]?.
     */
    private fun parse(isoDate: String): LocalDate? = runCatching { LocalDate.parse(isoDate) }.getOrNull()

    /**
     * Whether the price is old enough to be worth a network call.
     * Why:    a separate question from staleness, on a separate clock. A price never fetched at all
     *         is always due — that is how a holding that just gained a price key gets its first
     *         quote without waiting a day for the interval to elapse.
     * Result: `true` when no fetch is recorded or the interval has passed.
     * Input:  [input]. Output: [Boolean].
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    private fun refreshDue(input: PriceFreshnessInput): Boolean {
        val fetchedAt = input.fetchedAtUtcMillis ?: return true
        val elapsedMinutes = (input.nowUtcMillis - fetchedAt) / MILLIS_PER_MINUTE
        return elapsedMinutes >= input.rules.refreshMinutesFor(input.assetClass)
    }

    /** 60 000 ms in a minute. The refresh interval is expressed in minutes (§16.1). */
    private const val MILLIS_PER_MINUTE = 60_000L
}
