package com.aicfo.core.model

/**
 * Where a computed number came from — attached to every engine result (AI-ARC-003, AI-ARC-006).
 *
 * Why:  two binding rules stand on this type. **AI-ARC-006** says an insight stays reproducible
 *       because the engine version that produced it was stored beside it — six months later,
 *       "why did it say ₹42,500?" has an answer only if the result remembers which code and which
 *       thresholds ran. **P-02** says every recommendation shows the rule that fired, which needs
 *       the rule's id *and* version, since a threshold edit changes the advice without changing
 *       the rule's name. Neither works if a result can be built anonymous, so the constructor
 *       refuses a blank id or version rather than trusting each engine to fill them in.
 * What: the engine's identity and version, when it ran, which rules it cited, and optionally the
 *       window of data it read and how confident it is.
 * Result: every number this app displays can name the code, the version and the rules behind it.
 * Changelog: 2026-07-27 — Created for issue 2.3, the project's first engine result (AI-ARC-003).
 *
 * Lives in `:core:model` rather than in an engine module because **every** engine returns one, and
 * `:data:*` has to persist it — a type owned by one engine would drag that engine into the
 * dependency graph of everything downstream.
 *
 * Input:  [engineId] — stable identifier of the engine, e.g. `quick-setup`; [engineVersion] — the
 *         engine's own version, bumped whenever its formula changes (AI-ARC-006);
 *         [computedAtUtcMillis] — when it ran, UTC epoch millis from the caller's injected `Clock`
 *         (TIM-001), never read from the wall clock here; [evidence] — the rules that fired, in
 *         the order they should be shown; [inputWindow] — a human-readable description of the data
 *         window read (e.g. `2026-04-01..2026-06-30`), `null` when the engine reads no history;
 *         [confidenceBps] — confidence as integer basis points (MNY-002), `null` when the engine
 *         is deterministic and has no notion of confidence.
 * Output: an immutable value carried by every engine result.
 */
data class EngineProvenance(
    val engineId: String,
    val engineVersion: String,
    val computedAtUtcMillis: Long,
    val evidence: List<RuleCitation> = emptyList(),
    val inputWindow: String? = null,
    val confidenceBps: Int? = null,
) {
    init {
        require(engineId.isNotBlank()) { "An engine result must name the engine that produced it (AI-ARC-003)" }
        require(engineVersion.isNotBlank()) { "An engine result must carry its engine version (AI-ARC-006)" }
        require(computedAtUtcMillis >= 0L) {
            "computedAtUtcMillis is UTC epoch millis and cannot be negative, was $computedAtUtcMillis"
        }
        require(confidenceBps == null || confidenceBps in 0..BPS_FULL) {
            "Confidence is a rate in basis points, 0..$BPS_FULL (MNY-002), was $confidenceBps"
        }
    }

    private companion object {
        /** 10 000 bps = 100% (MNY-002). */
        const val BPS_FULL = 10_000
    }
}

/**
 * One rulebook row an engine applied, cited by id and version (P-02, AI-ARC-006).
 *
 * Why:    the id alone is not enough to reproduce a decision. `RULE-50-30-20` at version 1.0 and at
 *         version 1.1 can hold different thresholds and therefore give different advice, so an
 *         insight that recorded only the id would be unreproducible the moment anyone edited the
 *         rulebook — which §6 explicitly invites users to do.
 * Result: what [EngineProvenance.evidence] holds, and what the reasoning card shows.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * Input:  [ruleId] — the stable public id from `ai/rules/rules-kb.json`, e.g. `RULE-50-30-20`;
 *         [ruleVersion] — that row's `version` at the moment it was applied.
 * Output: an immutable value.
 */
data class RuleCitation(
    val ruleId: String,
    val ruleVersion: String,
) {
    init {
        require(ruleId.isNotBlank()) { "A cited rule must have an id (P-02)" }
        require(ruleVersion.isNotBlank()) { "A cited rule must have a version (AI-ARC-006)" }
    }
}
