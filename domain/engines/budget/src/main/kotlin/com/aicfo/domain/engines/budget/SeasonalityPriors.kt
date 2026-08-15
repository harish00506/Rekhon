package com.aicfo.domain.engines.budget

/**
 * One Indian calendar event and the months and categories it inflates, copied from
 * `ai/knowledge/calendar-seasonality.json`.
 *
 * Why:  FR-BUD-002 asks for a "seasonality adjustment", and a suggestion built from three monsoon
 *       months would read as a *cut* to a user budgeting for Diwali. §9.3's answer is a set of
 *       labelled priors that work in year one, before the app has enough of the user's own history
 *       to learn a seasonal index — which is exactly the position a new install is in.
 * What: the event's id, the months it spans, the category names it inflates, and how much, as
 *       integer basis points.
 * Result: a suggestion can say *why* it is above a category's ordinary median, naming the festival.
 * Changelog: 2026-08-11 — Created for issue 4.4 from calendar-seasonality.json v1.0.
 *
 * **The multiplier is bps, not the KB's decimal.** The file writes `1.38`; MNY-002 says a rate is
 * an integer basis point, so it is `13_800` here and `SeasonalityKbDriftTest` asserts the two agree.
 * A `Double` multiplier would be the one floating-point value in a money path (MNY-001).
 *
 * Input:  [id] — the KB's event id, cited in evidence; [startMonth]/[endMonth] — 1..12 inclusive,
 *         **wrapping** when `endMonth < startMonth` (the KB's `"Nov-Feb"` and `"Aug-Jan"`);
 *         [inflates] — category names, matched case-insensitively; [priorMultiplierBps] — the KB's
 *         `prior_multiplier`, where 10 000 bps = no change.
 * Output: an immutable value.
 */
data class SeasonalEvent(
    val id: String,
    val startMonth: Int,
    val endMonth: Int,
    val inflates: Set<String>,
    val priorMultiplierBps: Int,
) {
    init {
        require(startMonth in 1..MONTHS_IN_YEAR) { "startMonth must be 1..12, was $startMonth" }
        require(endMonth in 1..MONTHS_IN_YEAR) { "endMonth must be 1..12, was $endMonth" }
        require(inflates.isNotEmpty()) { "a seasonal event that inflates nothing would never fire" }
        // Below 10 000 bps the "prior" would predict a cheaper festival than an ordinary month,
        // which is not what any row in the KB says and would silently cut a budget.
        require(priorMultiplierBps >= BPS_FULL) {
            "priorMultiplierBps ($priorMultiplierBps) must be at least $BPS_FULL — a seasonal event " +
                "inflates spending by definition"
        }
    }

    /**
     * Decides whether this event applies to a category in a given month.
     * Why:    the KB's windows wrap the year end (`"Nov-Feb"`, `"Aug-Jan"`), so a naive
     *         `month in start..end` silently matches nothing for four of the nine events — and the
     *         two it would drop, wedding season and Onam/Pongal, are the ones a December or January
     *         budget most needs.
     * What:   an inclusive month test that handles the wrap, plus a case-insensitive category match.
     * Result: true when this event should inflate this category's suggestion.
     * Input:  [categoryName] — the category being suggested for; [month] — the target month, 1..12.
     * Output: [Boolean].
     */
    fun applies(
        categoryName: String,
        month: Int,
    ): Boolean {
        require(month in 1..MONTHS_IN_YEAR) { "month must be 1..12, was $month" }
        val inWindow =
            if (startMonth <= endMonth) {
                month in startMonth..endMonth
            } else {
                month >= startMonth || month <= endMonth
            }
        return inWindow && inflates.any { it.equals(categoryName, ignoreCase = true) }
    }
}

/**
 * The nine calendar events of `ai/knowledge/calendar-seasonality.json`, and the shrinkage rule that
 * blends them with what the app has actually observed.
 *
 * Why:  same recorded deferral as [BudgetRules] (ADR-0005, ADR-0017) — the app loads no `ai/` file
 *       at runtime, so this is a typed mirror guarded by `SeasonalityKbDriftTest` rather than a
 *       parser in a module that has no serialisation dependency by design (ARC-002).
 * What: the priors, and [seasonalIndexBps], which implements the KB's own
 *       `seasonal_index = 1 + k(raw - 1), k = months_observed / 24`.
 * Result: a young install leans on the calendar; a mature one leans on its own history, with no
 *       switch-over to get wrong — `k` moves the weight continuously.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
object SeasonalityPriors {
    /**
     * Result: the nine events, in the KB's own order. Input: none. Output: an immutable list.
     *
     * `MagicNumber` is suppressed for the same reason `config/detekt/detekt.yml` excludes the theme
     * tokens: **this is the data**, transcribed from `calendar-seasonality.json`, and naming each
     * month and multiplier would put a second name between the reviewer and the file this must
     * match. `SeasonalityKbDriftTest` compares every one of these numbers against the knowledge
     * base — including re-parsing the KB's own `"Oct-Nov"` strings back into months — so the
     * transcription is checked rather than trusted.
     */
    @Suppress("MagicNumber")
    val events: List<SeasonalEvent> =
        listOf(
            SeasonalEvent("diwali", 10, 11, setOf("Shopping", "Dining", "Travel", "Gifts"), 13_800),
            SeasonalEvent("dussehra_navratri", 9, 10, setOf("Shopping", "Dining"), 12_000),
            SeasonalEvent("wedding_season", 11, 2, setOf("Gifts", "Travel", "Shopping", "Gold"), 12_500),
            SeasonalEvent("tax_saving_rush", 1, 3, setOf("Investment", "Insurance"), 14_000),
            SeasonalEvent("school_admission", 3, 6, setOf("Education"), 15_000),
            SeasonalEvent("monsoon", 6, 9, setOf("Health", "Transport", "Vehicle"), 11_500),
            SeasonalEvent("summer", 4, 6, setOf("Utilities", "Travel"), 12_000),
            SeasonalEvent("onam_pongal", 8, 1, setOf("Shopping", "Dining"), 12_000),
            SeasonalEvent("akshaya_tritiya", 4, 5, setOf("Gold"), 13_000),
        )

    /**
     * Finds the strongest prior for a category in a month.
     * Why:    windows overlap — October is both Diwali and Dussehra for Shopping, and June is both
     *         monsoon and summer. **The multipliers are taken at their maximum, never multiplied.**
     *         Compounding them would claim 1.38 x 1.20 = 1.66 for an October shopping budget, a
     *         number no row in the KB supports and one that grows with however many events a future
     *         editor happens to add to the same month.
     * What:   scans [events] for the ones that apply and returns the largest multiplier with the
     *         event that carried it.
     * Result: the single event a suggestion cites, or `null` when the month is ordinary.
     * Input:  [categoryName] — matched case-insensitively; [month] — 1..12.
     * Output: the winning [SeasonalEvent], or `null`.
     */
    fun strongestFor(
        categoryName: String,
        month: Int,
    ): SeasonalEvent? = events.filter { it.applies(categoryName, month) }.maxByOrNull { it.priorMultiplierBps }

    /**
     * Blends a raw prior toward "no adjustment" in proportion to how little the app has observed.
     * Why:    the KB states the rule itself: `seasonal_index = 1 + k*(raw-1)`, `k =
     *         months_observed/24`. A brand-new install has no grounds to apply a festival multiplier
     *         at full strength, and a two-year-old one has its own median doing the work already.
     * What:   the same arithmetic in integer basis points (MNY-002) — `k` is capped at 1 so more
     *         than 24 months of history never *amplifies* a prior past what the KB claims.
     * Result: an index in bps, always between 10 000 (no change) and [rawMultiplierBps].
     * Input:  [rawMultiplierBps] — the event's prior; [monthsObserved] — how many months of this
     *         profile's history exist; [denominatorMonths] — the rule's `24`.
     * Output: [Int] basis points.
     */
    fun seasonalIndexBps(
        rawMultiplierBps: Int,
        monthsObserved: Int,
        denominatorMonths: Int,
    ): Int {
        require(monthsObserved >= 0) { "monthsObserved cannot be negative, was $monthsObserved" }
        require(denominatorMonths >= 1) { "denominatorMonths must be at least 1, was $denominatorMonths" }
        val cappedMonths = minOf(monthsObserved, denominatorMonths)
        val excessBps = rawMultiplierBps - BPS_FULL
        // Integer division truncates toward zero; excessBps is non-negative by SeasonalEvent's
        // invariant, so the index can only ever be rounded *down* toward "no adjustment" — the
        // conservative direction for a number that raises a budget.
        return BPS_FULL + (excessBps * cappedMonths) / denominatorMonths
    }

    /** The knowledge-base file these events were copied from, as `_meta.version`. */
    const val KB_VERSION = "1.0"
}

/** Months in a year — the modulus every wrapping window is checked against. */
internal const val MONTHS_IN_YEAR = 12
