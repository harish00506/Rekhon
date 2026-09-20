package com.aicfo.feature.dashboard

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aicfo.domain.engines.healthscore.HealthBand
import com.aicfo.domain.engines.healthscore.HealthScore
import com.aicfo.domain.engines.healthscore.Pillar
import com.aicfo.domain.engines.healthscore.PillarScore
import com.aicfo.domain.engines.healthscore.Signal
import com.aicfo.domain.engines.healthscore.SignalScore
import kotlin.math.absoluteValue

/**
 * The Financial Health Score, opened all the way down (issue 9.4; SRS §14, FR-AI-001; P-02, P-03).
 *
 * Why:  §14 allows a single number only if every part of it can be read: the total and its band,
 *       then each pillar's points and how much of the total it carries — with a pillar that has no
 *       data said to be missing, never shown as a zero — then each signal's measure against where it
 *       would score full marks. The biggest lever turns the number into one thing to do (§14 "the
 *       single highest-leverage action"). No red, no flashing: the band is a word (§14 anti-anxiety).
 * What: label; total and band (or "not scored yet"); five pillar lines with their signals; the
 *       lever; how many pillars it rests on; the rules.
 * Result: nothing before the first emission; a plain sentence for a profile with no data.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 *
 * Input:  [health] — or `null`. Output: the composition.
 */
@Composable
internal fun HealthSection(health: HealthScore?) {
    health ?: return
    Text(text = stringResource(R.string.dashboard_health_label))
    val score = health.score
    val band = health.band
    if (score == null || band == null) {
        Note(stringResource(R.string.dashboard_health_empty))
        return
    }
    Text(
        text = stringResource(R.string.dashboard_health_score, score, SCALE, stringResource(band.label())),
        style = MaterialTheme.typography.bodyMedium,
    )
    health.pillars.forEach { PillarLines(it) }
    LeverAndNotes(health)
}

/**
 * The lever, how much of the score has data behind it, and the rules (§14, P-02).
 * Result: the composition. Input: [health] — already known to have a score. Output: none.
 * Changelog: 2026-09-20 — Split out of [HealthSection] for issue 9.4.
 */
@Composable
private fun LeverAndNotes(health: HealthScore) {
    health.lever?.let { lever ->
        Text(
            text =
                pluralStringResource(
                    R.plurals.dashboard_health_lever,
                    lever.gain,
                    stringResource(lever.signal.leverName()),
                    lever.gain,
                ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val scored = health.pillars.count { it.points != null }
    Note(pluralStringResource(R.plurals.dashboard_health_coverage, Pillar.entries.size, scored, Pillar.entries.size))
    Note(
        stringResource(
            R.string.dashboard_health_rules,
            health.provenance.evidence.joinToString(", ") { "${it.ruleId} v${it.ruleVersion}" },
        ),
    )
}

/**
 * One pillar and its signals.
 * Why:    a pillar with no signal says so, with the weight it has given away, so a missing pillar can
 *         never read as a failing one (§14 "insufficient-data handling").
 * Result: the composition. Input: [pillar]. Output: none.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
@Composable
private fun PillarLines(pillar: PillarScore) {
    val points = pillar.points
    val name = stringResource(pillar.pillar.label())
    if (points == null) {
        Note(stringResource(R.string.dashboard_health_pillar_missing, name, tenths(pillar.weightBps)))
        return
    }
    Text(
        text =
            pluralStringResource(
                R.plurals.dashboard_health_pillar,
                pillar.contribution,
                name,
                tenths(points),
                pillar.contribution,
                tenths(pillar.effectiveWeightBps),
            ),
        style = MaterialTheme.typography.bodySmall,
    )
    pillar.signals.forEach { Note(signalText(it)) }
}

/**
 * One signal, as a sentence: what was measured, where full marks are, what it scored.
 * Result: the text. Input: [signal]. Output: [String].
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
@Composable
private fun signalText(signal: SignalScore): String {
    val points = tenths(signal.points)
    val runway = signal.signal == Signal.RUNWAY
    val measure = if (runway) months(signal.measureBps) else tenths(signal.measureBps)
    val target = if (runway) months(signal.targetBps) else tenths(signal.targetBps)
    return when (signal.signal) {
        Signal.RUNWAY -> stringResource(R.string.dashboard_health_signal_runway, measure, target, points)
        Signal.OBLIGATIONS -> stringResource(R.string.dashboard_health_signal_obligations, measure, target, points)
        Signal.CARD_UTILISATION -> stringResource(R.string.dashboard_health_signal_cards, measure, target, points)
        Signal.SAVINGS_RATE -> stringResource(R.string.dashboard_health_signal_savings, measure, target, points)
        Signal.BUDGET_ADHERENCE -> stringResource(R.string.dashboard_health_signal_budgets, measure, points)
        Signal.GOALS_ON_TRACK -> stringResource(R.string.dashboard_health_signal_goals, measure, points)
    }
}

/** A small, muted line. Input: [text]. Output: none. */
@Composable
private fun Note(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Hundredths as a number with one decimal — `8046` → `80.4`, `-1500` → `-15.0` — in integer
 * arithmetic (MNY-002). Serves both points (0..10 000 = 0..100) and bps ratios (10 000 = 100%).
 * Input: [value]. Output: [String].
 */
private fun tenths(value: Int): String {
    val sign = if (value < 0) "-" else ""
    val magnitude = value.absoluteValue
    return "$sign${magnitude / HUNDRED}.${magnitude % HUNDRED / TEN}"
}

/** Months in bps of a month as one decimal — `48000` → `4.8`. Input: [bps]. Output: [String]. */
private fun months(bps: Int): String = "${bps / BPS}.${bps % BPS / THOUSAND}"

/** Result: the band's word. A `when`, so a new band fails to compile until it has one. */
@StringRes
private fun HealthBand.label(): Int =
    when (this) {
        HealthBand.EXCELLENT -> R.string.dashboard_health_band_excellent
        HealthBand.GOOD -> R.string.dashboard_health_band_good
        HealthBand.FAIR -> R.string.dashboard_health_band_fair
        HealthBand.NEEDS_ATTENTION -> R.string.dashboard_health_band_attention
        HealthBand.AT_RISK -> R.string.dashboard_health_band_at_risk
    }

/** Result: the pillar's name. */
@StringRes
private fun Pillar.label(): Int =
    when (this) {
        Pillar.LIQUIDITY -> R.string.dashboard_health_pillar_liquidity
        Pillar.DEBT -> R.string.dashboard_health_pillar_debt
        Pillar.DISCIPLINE -> R.string.dashboard_health_pillar_discipline
        Pillar.GOALS -> R.string.dashboard_health_pillar_goals
        Pillar.PROTECTION -> R.string.dashboard_health_pillar_protection
    }

/** Result: the signal as the lever line names it. */
@StringRes
private fun Signal.leverName(): Int =
    when (this) {
        Signal.RUNWAY -> R.string.dashboard_health_lever_runway
        Signal.OBLIGATIONS -> R.string.dashboard_health_lever_obligations
        Signal.CARD_UTILISATION -> R.string.dashboard_health_lever_cards
        Signal.SAVINGS_RATE -> R.string.dashboard_health_lever_savings
        Signal.BUDGET_ADHERENCE -> R.string.dashboard_health_lever_budgets
        Signal.GOALS_ON_TRACK -> R.string.dashboard_health_lever_goals
    }

/** The top of §14's scale, as the screen states it. */
private const val SCALE = 1_000
private const val HUNDRED = 100
private const val TEN = 10
private const val BPS = 10_000
private const val THOUSAND = 1_000
