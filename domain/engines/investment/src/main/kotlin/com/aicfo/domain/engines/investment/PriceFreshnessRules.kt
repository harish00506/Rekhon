package com.aicfo.domain.engines.investment

import com.aicfo.core.model.AssetClass

/**
 * `RULE-PRICE-STALE`'s two thresholds, copied from `ai/rules/rules-kb.json` (issue 6.5; §16.1).
 *
 * Why:  the same deliberate, recorded deferral `InvestmentRules` documents — nothing in the app
 *       loads `ai/` at runtime, so the numbers are mirrored here and `RulebookDriftTest` fails the
 *       build the moment the mirror and the rulebook disagree.
 *
 *       **Two thresholds rather than one, because they answer different questions.**
 *       [refreshMinutes] asks *should I spend a network call?* — SRS §16.1 prices gold and silver
 *       end-of-day and crypto every fifteen minutes while the app is open. [staleAfterDays] asks
 *       *should I tell the user this number is old?* A single value cannot do both: at fifteen
 *       minutes crypto would be labelled stale permanently, and at one day it would never refresh
 *       often enough to be worth fetching at all.
 *
 *       **Minutes for one and days for the other, deliberately.** The refresh decision reads a
 *       fetch instant, which is a clock time; the staleness decision reads the day the market
 *       priced the instrument, which is a calendar date (TIM-002). Expressing the second in minutes
 *       would imply a precision the source does not have — an end-of-day gold print is a fact about
 *       a *day*, not a moment.
 * What: one property per `params_json` key, keyed by [AssetClass] with a fallback for the rest.
 * Result: a class the engine reads and a test can move, so a rulebook edit moves behaviour.
 * Changelog: 2026-08-29 — Created for issue 6.5 from rules-kb.json v1.14.0.
 *
 * **Injected rather than read**, the seam the runtime loader will use when it lands (ADR-0017).
 *
 * Input:  [refreshMinutes] — `RULE-PRICE-STALE.refresh_minutes`, per class; [defaultRefreshMinutes]
 *         — its `default`, for a class the rulebook does not name; [staleAfterDays] —
 *         `stale_after_days`, per class; [defaultStaleAfterDays] — its `default`.
 * Output: an immutable value.
 */
data class PriceFreshnessRules(
    /** `refresh_minutes` — gold end-of-day, crypto every fifteen minutes (§16.1). */
    val refreshMinutes: Map<AssetClass, Int> =
        mapOf(AssetClass.GOLD to GOLD_REFRESH_MINUTES, AssetClass.CRYPTO to CRYPTO_REFRESH_MINUTES),
    /** `refresh_minutes.default` — daily, for a class the rulebook names no cadence for. */
    val defaultRefreshMinutes: Int = DEFAULT_REFRESH_MINUTES,
    /** `stale_after_days` — how old a price may be before the user is told. */
    val staleAfterDays: Map<AssetClass, Int> =
        mapOf(AssetClass.GOLD to GOLD_STALE_DAYS, AssetClass.CRYPTO to CRYPTO_STALE_DAYS),
    /** `stale_after_days.default` — a week, because a mutual-fund NAV is daily. */
    val defaultStaleAfterDays: Int = DEFAULT_STALE_DAYS,
) {
    init {
        // A zero or negative interval means "always due", so the app would ask the proxy for a
        // price on every single emission of a Flow that re-emits on every lot edit. That is a
        // request storm dressed as a configuration value, and it would look like a server problem.
        require(refreshMinutes.values.all { it > 0 } && defaultRefreshMinutes > 0) {
            "Every refresh interval must be a positive number of minutes: at or below zero a price " +
                "is always due and the app would fetch on every emission"
        }
        // A zero threshold labels a price fetched one second ago as stale, so the warning fires for
        // everyone always and stops carrying information — the same argument the concentration
        // ceilings make for their own bounds.
        require(staleAfterDays.values.all { it > 0 } && defaultStaleAfterDays > 0) {
            "Every staleness threshold must be a positive number of days: at zero every price is " +
                "stale the moment it arrives and the label means nothing"
        }
    }

    /**
     * How often a class's price is worth re-fetching.
     * Result: the class's interval, or the default for one the rulebook does not name.
     * Input:  [assetClass]. Output: whole minutes.
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    fun refreshMinutesFor(assetClass: AssetClass): Int = refreshMinutes[assetClass] ?: defaultRefreshMinutes

    /**
     * How old a class's price may be before the user is told.
     * Result: the class's threshold, or the default. Input: [assetClass]. Output: whole days.
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    fun staleAfterDaysFor(assetClass: AssetClass): Int = staleAfterDays[assetClass] ?: defaultStaleAfterDays

    companion object {
        /** `RULE-PRICE-STALE.refresh_minutes.gold` — a day; gold prints end-of-day (§16.1). */
        private const val GOLD_REFRESH_MINUTES = 1_440

        /** `RULE-PRICE-STALE.refresh_minutes.crypto` — fifteen minutes while the app is open. */
        private const val CRYPTO_REFRESH_MINUTES = 15

        /** `RULE-PRICE-STALE.refresh_minutes.default`. */
        private const val DEFAULT_REFRESH_MINUTES = 1_440

        /** `RULE-PRICE-STALE.stale_after_days.gold` — a weekend plus a holiday is a normal gap. */
        private const val GOLD_STALE_DAYS = 3

        /** `RULE-PRICE-STALE.stale_after_days.crypto` — it moves that fast. */
        private const val CRYPTO_STALE_DAYS = 1

        /** `RULE-PRICE-STALE.stale_after_days.default` — a week; a fund NAV is daily. */
        private const val DEFAULT_STALE_DAYS = 7
    }
}
