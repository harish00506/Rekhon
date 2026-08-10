package com.aicfo.domain.engines.classification

import com.aicfo.core.model.RuleCitation

/**
 * One merchant→category row, copied from `ai/knowledge/classification-kb.json` (issue 4.2).
 *
 * Why:  the knowledge base states two match types, `contains` for a single word and `regex` for an
 *       alternation, and **this engine treats both as a list of literals** — see [tokens]. The type
 *       is still carried, and still drift-checked, because AI-ARC-006 says a cited row is reproduced
 *       as the file states it, not as the consumer happens to need it.
 * What: the row's identity, what it matches, and the category it names.
 * Result: the element type of [ClassificationRules.merchantRules].
 * Changelog: 2026-08-10 — Created for issue 4.2 from classification-kb.json v1.2.
 *
 * **`default_nature` is deliberately not mirrored here.** These rows carry one, and reading it would
 * be the natural next line of code — but nature classification is §8.3 and issue **4.3**, whose
 * decision order puts a *category's* default nature at step 5 behind three account-level overrides
 * this engine cannot see. A nature guessed from the merchant alone would be right often enough to be
 * trusted and wrong exactly where it matters (an EMI, a gold purchase, a goal transfer).
 *
 * Input:  [ruleId] — the permanent `CLS-MER-*` id, cited in every suggestion it produces;
 *         [version] — that row's version, so a stored suggestion stays reproducible when the rule
 *         changes (AI-ARC-006); [match] — the row's `match` string **exactly as the file states it**,
 *         alternatives separated by `|`; [type]; [category] — the display name of the
 *         `category_defaults` row it points at.
 * Output: an immutable value.
 */
data class MerchantRule(
    val ruleId: String,
    val version: String,
    val match: String,
    val type: MerchantMatchType,
    val category: String,
) {
    /**
     * The literals this row matches on.
     * Why:    computed once at construction rather than per classification — the whole rule set is
     *         scanned on every keystroke of the merchant field, and splitting thirteen strings each
     *         time is work with a known answer.
     * Result: the alternatives, lower-cased and trimmed. Input: none. Output: `List<String>`.
     */
    val tokens: List<String> = match.split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** Result: the citation this row contributes as evidence (P-02). Output: [RuleCitation]. */
    val citation: RuleCitation get() = RuleCitation(ruleId, version)

    init {
        require(tokens.isNotEmpty()) { "$ruleId matches nothing" }
        require(category.isNotBlank()) { "$ruleId names no category" }
        // The literal-alternation ceiling, enforced rather than merely documented. A row whose
        // `match` used real regex syntax — `^amazon`, `swiggy.*`, `zomato?` — would be matched here
        // character-for-character, find nothing, and fail *silently*: the merchant would simply stop
        // being classified, with no error anywhere to say a rule had stopped working. Failing at
        // construction turns that into a build failure the next time the knowledge base is edited.
        tokens.forEach { token ->
            require(LITERAL_TOKEN.matches(token)) {
                "$ruleId's alternative '$token' is not a literal; this engine matches literals, " +
                    "not regular expressions (see ENGINE.md)"
            }
        }
    }

    private companion object {
        /** Letters, digits, spaces and the punctuation a brand name genuinely contains. */
        val LITERAL_TOKEN = Regex("""[a-z0-9][a-z0-9 &'.-]*""")
    }
}

/**
 * How the knowledge base says a row is matched (issue 4.2).
 *
 * Why:  mirrored from the file's `type` field so the drift test can compare it, and **not branched
 *       on**: both types are matched as literals on token boundaries. `contains` rows hold one
 *       alternative and `regex` rows hold several, which is the only difference that survives.
 * Result: the type of [MerchantRule.type].
 * Changelog: 2026-08-10 — Created for issue 4.2.
 */
enum class MerchantMatchType(val kbValue: String) {
    /** A single literal, e.g. `swiggy`. */
    CONTAINS("contains"),

    /** Several literals separated by `|`, e.g. `uber|ola|rapido`. */
    REGEX("regex"),
    ;

    companion object {
        /**
         * Parses the spelling the knowledge base uses.
         * Result: the matching type, or `null` for one this build does not know — which the drift
         *         test turns into a failure rather than letting a mystery type default to something.
         * Input:  [kb] — a `type` value from the knowledge base. Output: `MerchantMatchType?`.
         * Changelog: 2026-08-10 — Created for issue 4.2.
         */
        fun fromKb(kb: String): MerchantMatchType? = entries.firstOrNull { it.kbValue == kb }
    }
}

/**
 * The rows and thresholds Stage 1 applies, copied from `ai/knowledge/classification-kb.json`.
 *
 * Why:  CLAUDE.md §6 says a classification rule is a **data row in `ai/`, never hardcoded logic** —
 *       and this file is thirteen hardcoded rows and four hardcoded numbers. The same deliberate,
 *       recorded deferral ADR-0005 made for `QuickSetupRules` and `SmsRules` and ADR-0014 made for
 *       `CategorySeed`: nothing in the app loads `ai/` at runtime, so honouring §6 literally would
 *       mean this issue first building an asset pipeline and a JSON parser into a module that has no
 *       serialisation dependency by design (ARC-002). ADR-0015 records why that is still the answer
 *       now that the file has two consumers. `ClassificationKbDriftTest` closes the gap that matters
 *       — edit a merchant, a category or a threshold in the knowledge base and the build goes red
 *       until this file agrees.
 * What: the `merchant_rules` array and the `stage1` block, as typed values.
 * Result: every category this app proposes is attributable to a row a reviewer can open, and every
 *       threshold that decided whether to propose at all is one they can change (P-02, AI-ARC-006).
 * Changelog: 2026-08-10 — Created for issue 4.2 from classification-kb.json v1.2.
 *
 * **Injected rather than read, so a test can move a threshold** and assert the engine moves with it
 * — which is also the seam the real loader will use when it lands, with no change to the engine.
 *
 * Input:  [merchantRules] — `merchant_rules`, in file order, which is also match-precedence order
 *         when two rows tie on confidence; [minConfidenceBps] — `stage1.min_confidence_bps`, the
 *         floor under which Stage 1 proposes nothing (MNY-002, so no `Float` reaches the engine);
 *         [exactMatchBps] — what a merchant that *is* the rule's literal is worth;
 *         [wordMatchBps] — what a literal found as a whole word inside a longer descriptor is worth;
 *         [historyMinOccurrences] — how many past transactions a merchant needs before the user's
 *         own filing counts as a rule; [userHistory] — the id tier (a) cites, which names no
 *         merchant because the user's own corrections are its rows.
 * Output: an immutable value.
 */
data class ClassificationRules(
    val merchantRules: List<MerchantRule> = KB_MERCHANT_RULES,
    /** `stage1.min_confidence_bps` — below this Stage 1 defers to the user (SRS §8.1). */
    val minConfidenceBps: Int = 7_000,
    /** `stage1.exact_match_bps` — the whole merchant is the rule's literal. */
    val exactMatchBps: Int = 9_500,
    /** `stage1.word_match_bps` — the literal is one token of a longer descriptor. */
    val wordMatchBps: Int = 8_500,
    /** `stage1.history_min_occurrences` — one past correction is already a user rule (§8.1(a)). */
    val historyMinOccurrences: Int = 1,
    /** `stage1.user_history_rule_id` / `_version` — what tier (a) cites as evidence (P-02). */
    val userHistory: RuleCitation = RuleCitation("CLS-USER-HISTORY", "1.0"),
) {
    init {
        // An empty rule set is not "the knowledge base tuned to nothing" — it is a different feature,
        // one where the app silently stops classifying and no test that scores accuracy over the
        // rules it has would notice, because there would be nothing to score.
        require(merchantRules.isNotEmpty()) { "Stage 1 needs at least one merchant rule" }
        require(merchantRules.map { it.ruleId }.toSet().size == merchantRules.size) {
            "duplicate merchant rule_id — a citation must be unambiguous (AI-ARC-006)"
        }
        listOf(minConfidenceBps, exactMatchBps, wordMatchBps).forEach { bps ->
            require(bps in 0..BPS_FULL) { "Confidence is basis points in 0..$BPS_FULL (MNY-002), was $bps" }
        }
        // Ordered, not merely bounded. A word match worth less than the floor would make the knowledge
        // base fire only on merchants typed with no descriptor at all — which is almost none of them
        // — and the tier would look alive while classifying nothing.
        require(exactMatchBps >= wordMatchBps) { "An exact match cannot be worth less than a word match" }
        require(wordMatchBps >= minConfidenceBps) {
            "wordMatchBps ($wordMatchBps) is below minConfidenceBps ($minConfidenceBps): the " +
                "knowledge-base tier could never fire on a real merchant descriptor"
        }
        require(historyMinOccurrences >= 1) {
            "A user rule needs at least one past correction to be derived from, was $historyMinOccurrences"
        }
    }

    companion object {
        /**
         * `merchant_rules` from classification-kb.json v1.2, in file order.
         *
         * Why:    order is meaning here in exactly one case — two rows matching the *same* merchant
         *         with the same confidence and naming *different* categories propose nothing at all
         *         (see [DefaultClassificationEngine]), so this list's order never silently picks a
         *         winner. It is kept in file order anyway, because the drift test compares as an
         *         ordered list: a reordered knowledge base is a change worth noticing.
         * Result: the thirteen `CLS-MER-*` rows. Input: none. Output: an immutable list.
         */
        val KB_MERCHANT_RULES: List<MerchantRule> =
            listOf(
                MerchantRule("CLS-MER-001", "1.0", "swiggy", MerchantMatchType.CONTAINS, "Dining"),
                MerchantRule("CLS-MER-002", "1.0", "zomato", MerchantMatchType.CONTAINS, "Dining"),
                MerchantRule("CLS-MER-003", "1.0", "irctc", MerchantMatchType.CONTAINS, "Travel"),
                MerchantRule(
                    "CLS-MER-004",
                    "1.0",
                    "bescom|mseb|tneb|kseb|adani electricity",
                    MerchantMatchType.REGEX,
                    "Utilities",
                ),
                MerchantRule(
                    "CLS-MER-005",
                    "1.0",
                    "bigbasket|blinkit|zepto|dmart|jiomart",
                    MerchantMatchType.REGEX,
                    "Groceries",
                ),
                MerchantRule(
                    "CLS-MER-006",
                    "1.0",
                    "amazon|flipkart|myntra|ajio",
                    MerchantMatchType.REGEX,
                    "Shopping",
                ),
                MerchantRule("CLS-MER-007", "1.0", "uber|ola|rapido", MerchantMatchType.REGEX, "Transport"),
                MerchantRule(
                    "CLS-MER-008",
                    "1.0",
                    "indianoil|iocl|bharat petroleum|bpcl|hpcl|hp petrol",
                    MerchantMatchType.REGEX,
                    "Fuel",
                ),
                MerchantRule(
                    "CLS-MER-009",
                    "1.0",
                    "netflix|hotstar|spotify|prime video|jio cinema",
                    MerchantMatchType.REGEX,
                    "Subscriptions",
                ),
                MerchantRule(
                    "CLS-MER-010",
                    "1.0",
                    "lic|hdfc life|sbi life|max life|icici pru",
                    MerchantMatchType.REGEX,
                    "Insurance",
                ),
                // v1.1: `coin` dropped. Read as a rule for the first time in this issue, it files a
                // laundromat under Investment; `zerodha` already covers every real Coin descriptor.
                MerchantRule(
                    "CLS-MER-011",
                    "1.1",
                    "groww|zerodha|kuvera|et money|indmoney",
                    MerchantMatchType.REGEX,
                    "Investment",
                ),
                MerchantRule(
                    "CLS-MER-012",
                    "1.0",
                    "apollo|pharmeasy|1mg|netmeds|medplus",
                    MerchantMatchType.REGEX,
                    "Health",
                ),
                MerchantRule(
                    "CLS-MER-013",
                    "1.0",
                    "byju|unacademy|vedantu|school fee",
                    MerchantMatchType.REGEX,
                    "Education",
                ),
            )

        /** The knowledge-base file these rows and thresholds were copied from, as `_meta.version`. */
        const val KB_VERSION = "1.2"
    }
}

/** 10 000 bps = 100% (MNY-002). */
internal const val BPS_FULL = 10_000
