package com.aicfo.domain.engines.seasonality

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.RuleCitation
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.absoluteValue

/**
 * §9.3's seasonal index, and the factor it puts on the forecast's everyday spend (issue 9.3;
 * AI-SEAS, P-02, P-08, ADR-0044).
 *
 * Why:  §9.3 in its own terms — `median(month m across years) / median(all months)`, shrunk by
 *       `k = months_observed / 24` — with the calendar knowledge base supplying the raw value where
 *       the user has no earlier year of that month. Two gaps §9.3 leaves are closed as ADR-0044
 *       records: an own index needs the month to have been spent in at least once (a literal ratio
 *       of zero would forecast no spending at all), and a lookback's own season is divided out of
 *       the factor, so a monsoon-heavy ninety days does not carry the monsoon into October.
 * What: validate → build the index table → one index per category per month → one factor per month
 *       and the events that moved it, a move under `min_effect_bps` being noise → provenance.
 * Result: a [SeasonalityResult].
 * Changelog: 2026-09-19 — Created for issue 9.3.
 *
 * Integer basis points throughout (MNY-002); the factor is an exact `BigDecimal` rounded HALF_EVEN
 * once. No clock, no randomness: the input fixes the output (P-08). `internal` per ARC-003.
 */
internal class MedianSeasonalityEngine : SeasonalityEngine {
    override fun index(input: SeasonalityInput): Result<SeasonalityResult, AppError> {
        validate(input)?.let { return Err(it) }
        return runCatchingToResult {
            val table = IndexTable.of(input)
            val lookbackDays =
                generateSequence(input.lookbackStart) { it.plusDays(1) }.takeWhile { it <= input.lookbackEnd }.toList()
            val weights = input.lookback.filter { it.amount.minor > 0L }
            val indices = input.months.flatMap { month -> table.categories.map { table.index(it, month) } }
            val factors =
                input.months.map {
                        month ->
                    FactorMath(table, weights, lookbackDays, input.rules).factor(month)
                }
            SeasonalityResult(indices, factors, table.monthsObserved, provenance(input, table, factors))
        }
    }

    /**
     * The inputs that cannot be indexed.
     * Result: the first refusal, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: SeasonalityInput): AppError.Validation? =
        when {
            input.history.any { it.amount.minor < 0L } || input.lookback.any { it.amount.minor < 0L } ->
                AppError.Validation(FIELD_SPEND)
            input.lookbackEnd < input.lookbackStart -> AppError.Validation(FIELD_LOOKBACK)
            else -> null
        }

    /**
     * Provenance (AI-ARC-003): the rule, every event that moved a factor, the windows, and how far
     * the shrinkage lets the history speak (`k`, as confidence).
     * Result: the provenance. Input: [input]; [table]; [factors]. Output: [EngineProvenance].
     */
    private fun provenance(
        input: SeasonalityInput,
        table: IndexTable,
        factors: List<SpendFactor>,
    ): EngineProvenance {
        val named = factors.flatMap { it.rising + it.easing }.toSet()
        val events =
            SeasonalityPriors.events.filter {
                it.id in named
            }.map { RuleCitation(it.id, SeasonalityPriors.KB_VERSION) }
        val history = table.observedRange?.let { (first, last) -> "$first..$last" } ?: "none"
        val months = if (input.months.isEmpty()) "none" else "${input.months.first()}..${input.months.last()}"
        val denominator = input.rules.shrinkageDenominatorMonths
        return EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = listOf(SeasonalityRules.INDEX) + events,
            inputWindow = "$history · ${input.lookbackStart}..${input.lookbackEnd} → $months",
            confidenceBps = minOf(table.monthsObserved, denominator) * BPS_FULL / denominator,
        )
    }

    private companion object {
        const val ENGINE_ID = "AI-SEAS"
        const val ENGINE_VERSION = "1.0"
        const val FIELD_SPEND = "seasonality.spend"
        const val FIELD_LOOKBACK = "seasonality.lookback"
    }
}

/**
 * Every category's index for any month, computed once (issue 9.3).
 *
 * Why:  the factor asks for the same category's index for every lookback day and every month
 *       ahead; the table answers from one pass over the history and remembers each answer.
 * Input:  built by [of]. Output: [SeasonalIndex] per category and month.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
internal class IndexTable private constructor(
    private val spend: Map<Pair<String, YearMonth>, Long>,
    private val observed: List<YearMonth>,
    private val names: Map<String, String?>,
    val categories: List<String>,
    private val rules: SeasonalityRules,
) {
    private val cache = HashMap<Pair<String, YearMonth>, SeasonalIndex>()

    /** `months_observed`: every month with any row, categorised or not. */
    val monthsObserved: Int get() = observed.size

    /** The first and last observed month, or `null` with no history. */
    val observedRange: Pair<YearMonth, YearMonth>?
        get() = if (observed.isEmpty()) null else observed.first() to observed.last()

    /**
     * One category's index for one month.
     * Why:    **own history first, when it is evidence**: the month must have recurred and been
     *         spent in, and the category's typical month must be above zero — otherwise the
     *         calendar's prior (the strongest event, never a product of several), otherwise ×1.
     *         Then `1 + k(raw − 1)`, truncated toward ×1 (the budget's same helper).
     * Result: the index. Input: [category] id; [month]. Output: [SeasonalIndex].
     */
    fun index(
        category: String,
        month: YearMonth,
    ): SeasonalIndex =
        cache.getOrPut(category to month) {
            val own = ownRaw(category, month)
            val prior =
                if (own == null) {
                    SeasonalityPriors.strongestFor(
                        names[category].orEmpty(),
                        month.monthValue,
                    )
                } else {
                    null
                }
            val raw = own ?: prior?.priorMultiplierBps ?: BPS_FULL
            val source =
                when {
                    own != null -> IndexSource.OWN_HISTORY
                    prior != null -> IndexSource.CALENDAR_PRIOR
                    else -> IndexSource.NONE
                }
            SeasonalIndex(
                categoryId = category,
                categoryName = names[category],
                month = month,
                rawBps = raw,
                indexBps = SeasonalityPriors.seasonalIndexBps(raw, monthsObserved, rules.shrinkageDenominatorMonths),
                source = source,
                eventId = prior?.id,
            )
        }

    /**
     * §9.3's own ratio, or `null` when the history is no evidence for this month.
     * Why:    a zero typical month would divide by zero, and a zero same-month median would claim
     *         the user never spends in that month — for a category they use most months, that is a
     *         gap in the data, not a season (ADR-0044). The ratio saturates where `excess × k` would
     *         leave an `Int`: thousands of times a typical month is not a season either, and the
     *         saturation keeps the arithmetic exact below it.
     * Result: bps, or `null`. Input: [category]; [month]. Output: `Int?`.
     */
    private fun ownRaw(
        category: String,
        month: YearMonth,
    ): Int? {
        val same = observed.filter { it.monthValue == month.monthValue }.map { spend[category to it] ?: 0L }
        val typical = median(observed.map { spend[category to it] ?: 0L })
        val thisMonth = median(same) // 0 for an empty list, so a month never observed falls back too
        if (typical.signum() == 0 || thisMonth.signum() == 0) return null
        val ceiling = (Int.MAX_VALUE - BPS_FULL) / rules.shrinkageDenominatorMonths
        val raw = thisMonth.multiply(BPS).divide(typical, MATH).setScale(0, RoundingMode.HALF_EVEN)
        return raw.min(BigDecimal.valueOf(ceiling.toLong())).intValueExact()
    }

    companion object {
        /**
         * Builds the table from the input.
         * Why:    a category's display name is taken from the lookback first (today's name), then
         *         the history; its id is what groups the months, so a renamed category keeps its
         *         history.
         * Result: the table. Input: [input]. Output: [IndexTable].
         */
        fun of(input: SeasonalityInput): IndexTable {
            val spend = HashMap<Pair<String, YearMonth>, Long>()
            input.history.forEach { row ->
                val id = row.categoryId ?: return@forEach
                spend.merge(id to row.month, row.amount.minor, Math::addExact)
            }
            val names = LinkedHashMap<String, String?>()
            input.lookback.forEach { row -> row.categoryId?.let { id -> names.putIfAbsent(id, row.categoryName) } }
            input.history.forEach {
                    row ->
                row.categoryId?.let { id -> if (names[id] == null) names[id] = row.categoryName }
            }
            return IndexTable(
                spend = spend,
                observed = input.history.map { it.month }.distinct().sorted(),
                names = names,
                categories = names.keys.sorted(),
                rules = input.rules,
            )
        }
    }
}

/**
 * The factor for a month against the lookback (issue 9.3).
 *
 * Why:  `Σ_c w_c × index_c(m) / L_c`, where `w_c` is category c's share of the lookback's everyday
 *       spend (uncategorised spend is weighted at ×1) and `L_c` is its index averaged over the
 *       lookback's days — the lookback's own season, divided out. A category's **effect** is
 *       `w_c × (index_c(m) / L_c − 1)`; its event is named when that is at least
 *       `min_effect_bps` either way. **A factor that moves less than `min_effect_bps` is ×1 with
 *       nothing named** — a young install's 0.04% is noise, and showing it as a seasonal line would
 *       be a number with no meaning (ADR-0044 §4, found on the device).
 * Input:  [table]; [weights] — the lookback rows with spend; [lookbackDays]; [rules].
 * Output: [SpendFactor] per month.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
internal class FactorMath(
    private val table: IndexTable,
    private val weights: List<CategorySpend>,
    private val lookbackDays: List<LocalDate>,
    private val rules: SeasonalityRules,
) {
    private val total = BigDecimal.valueOf(weights.sumOf { it.amount.minor })
    private val lookbackMonths = lookbackDays.map(YearMonth::from).distinct()
    private val threshold = BigDecimal.valueOf(rules.minEffectBps.toLong())

    /**
     * One month's factor and the events behind it.
     * Result: ×1 with nothing named when there is no everyday spend. Input: [month].
     * Output: [SpendFactor].
     */
    fun factor(month: YearMonth): SpendFactor {
        val unit = SpendFactor(month, BPS_FULL, emptyList(), emptyList(), false)
        if (total.signum() == 0) return unit
        val rising = mutableSetOf<String>()
        val easing = mutableSetOf<String>()
        var own = false
        var sum = BigDecimal.ZERO
        weights.groupBy { it.categoryId }.toSortedMap(nullsFirst()).forEach { (id, rows) ->
            val weight = BigDecimal.valueOf(rows.sumOf { it.amount.minor }) // paise, used only as a weight
            val ratio = if (id == null) BigDecimal.ONE else ratio(id, month)
            sum += weight.multiply(ratio)
            if (id == null) return@forEach
            val effect = weight.divide(total, MATH).multiply(ratio - BigDecimal.ONE).multiply(BPS)
            val here = table.index(id, month)
            if (effect.abs() >= threshold && here.source == IndexSource.OWN_HISTORY) own = true
            if (effect >= threshold && here.source == IndexSource.CALENDAR_PRIOR) rising += here.eventId.orEmpty()
            if (effect <= threshold.negate()) easing += endedEvents(id, here.eventId)
        }
        val factorBps = sum.divide(total, MATH).multiply(BPS).setScale(0, RoundingMode.HALF_EVEN).intValueExact()
        val order = SeasonalityPriors.events.map { it.id }
        val moved =
            SpendFactor(
                month = month,
                factorBps = factorBps,
                rising = order.filter { it in rising },
                easing = order.filter { it in easing && it !in rising },
                fromOwnHistory = own,
            )
        return if ((factorBps - BPS_FULL).absoluteValue < rules.minEffectBps) unit else moved
    }

    /**
     * `index(m) / L`, exactly (to 34 digits).
     * Why:    `L = Σ index over lookback days / days`, so the ratio is `index(m) × days / Σ`. A lookback
     *         whose every day indexed to zero carries no information, so the ratio is ×1.
     * Result: the ratio. Input: [category]; [month]. Output: [BigDecimal].
     */
    private fun ratio(
        category: String,
        month: YearMonth,
    ): BigDecimal {
        val lookbackSum = lookbackDays.sumOf { table.index(category, YearMonth.from(it)).indexBps.toLong() }
        if (lookbackSum == 0L) return BigDecimal.ONE
        val numerator = BigDecimal.valueOf(table.index(category, month).indexBps.toLong() * lookbackDays.size)
        return numerator.divide(BigDecimal.valueOf(lookbackSum), MATH)
    }

    /** Result: the calendar events that lifted [category] during the lookback and are not [current]. */
    private fun endedEvents(
        category: String,
        current: String?,
    ): List<String> =
        lookbackMonths.map { table.index(category, it) }
            .filter { it.source == IndexSource.CALENDAR_PRIOR && it.eventId != current }
            .mapNotNull { it.eventId }
}

/**
 * The median of paise, exactly — the midpoint of the two middle values for an even count.
 * Result: the median, `0` for an empty list. Input: [values]. Output: [BigDecimal].
 */
internal fun median(values: List<Long>): BigDecimal {
    if (values.isEmpty()) return BigDecimal.ZERO
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        BigDecimal.valueOf(sorted[middle])
    } else {
        BigDecimal.valueOf(sorted[middle - 1]).add(BigDecimal.valueOf(sorted[middle])).divide(TWO)
    }
}

private val MATH = MathContext.DECIMAL128
private val BPS = BigDecimal.valueOf(BPS_FULL.toLong())
private val TWO = BigDecimal.valueOf(2L)
