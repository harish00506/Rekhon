package com.aicfo.domain.engines.chat

/**
 * The tool registry, as typed values the engine can read (issue 10.5; §19.2, §6, ADR-0017).
 *
 * Why:  `ai/skills/tool-registry.json` is the authoritative list of what chat may touch, and §19.2
 *       is explicit that tools are the **only** way chat reaches data. An engine in this project
 *       cannot read a file (ARC-002), so it reads this mirror — and `ChatRegistryDriftTest` fails
 *       the build the moment the two disagree, which is the only thing that makes a mirror safe.
 * What: the tools, the intents that route to them, the routing numbers, and the out-of-scope
 *       triggers.
 * Result: adding a tool or teaching the assistant a new phrase is an edit to the JSON and this
 *         mirror together — never a `when` buried in an engine.
 * Changelog: 2026-09-26 — Created for issue 10.5 from tool-registry.json v1.1.
 *
 * Input:  [version] — the registry revision this copy was taken from; [intents]; [routing];
 *         [outOfScope].
 * Output: an immutable value; [BUNDLED] is the one every caller should use.
 */
data class ToolRegistry(
    val version: String,
    val intents: List<IntentRoute>,
    val routing: RoutingSpec,
    val outOfScope: OutOfScopeSpec,
) {
    companion object {
        /**
         * The bundled registry, mirroring `tool-registry.json` v1.1.
         * Result: the routes the app ships with. Input: none. Output: [ToolRegistry].
         */
        val BUNDLED =
            ToolRegistry(
                version = "1.1",
                intents =
                    listOf(
                        IntentRoute(
                            ChatIntent.SPEND,
                            listOf(ToolName.QUERY_SPEND),
                            listOf("spend", "spent", "spending", "money go", "where did", "outgoings"),
                            listOf(ChatIntent.BALANCE, ChatIntent.BUDGET),
                        ),
                        IntentRoute(
                            ChatIntent.BALANCE,
                            listOf(ToolName.GET_BALANCE),
                            listOf("balance", "how much do i have", "left", "runway", "liquid"),
                            listOf(ChatIntent.SPEND, ChatIntent.FORECAST),
                        ),
                        IntentRoute(
                            ChatIntent.FORECAST,
                            listOf(ToolName.GET_FORECAST),
                            listOf("forecast", "next 90", "ninety days", "run out", "crunch", "shortfall"),
                            listOf(ChatIntent.BALANCE, ChatIntent.GOALS),
                        ),
                        IntentRoute(
                            ChatIntent.BUDGET,
                            listOf(ToolName.GET_BUDGET_STATUS),
                            listOf("budget", "over budget", "on track", "limit"),
                            listOf(ChatIntent.SPEND, ChatIntent.HEALTH),
                        ),
                        IntentRoute(
                            ChatIntent.GOALS,
                            listOf(ToolName.GET_GOALS),
                            listOf("goal", "goals", "saving for", "target", "when can i buy"),
                            listOf(ChatIntent.FORECAST, ChatIntent.AFFORD),
                        ),
                        IntentRoute(
                            ChatIntent.AFFORD,
                            listOf(ToolName.PURCHASE_CHECK),
                            listOf("afford", "should i buy", "can i buy", "worth it"),
                            listOf(ChatIntent.BUYLIST, ChatIntent.GOALS),
                        ),
                        IntentRoute(
                            ChatIntent.HEALTH,
                            listOf(ToolName.GET_HEALTH_SCORE),
                            listOf("health", "score", "how am i doing", "doing financially"),
                            listOf(ChatIntent.BUDGET, ChatIntent.FORECAST),
                        ),
                        IntentRoute(
                            ChatIntent.DEBT,
                            listOf(ToolName.SIMULATE),
                            listOf("prepay", "pay off", "payoff", "which debt", "loan first", "invest instead"),
                            listOf(ChatIntent.BALANCE, ChatIntent.HEALTH),
                        ),
                        IntentRoute(
                            ChatIntent.BUYLIST,
                            listOf(ToolName.REVIEW_BUYLIST),
                            listOf("buy list", "buylist", "wish", "wishlist", "wanted"),
                            listOf(ChatIntent.AFFORD, ChatIntent.SPEND),
                        ),
                        IntentRoute(
                            ChatIntent.VEHICLE,
                            listOf(ToolName.GET_VEHICLE_STATUS),
                            listOf("car", "bike", "scooter", "service due", "vehicle"),
                            listOf(ChatIntent.FORECAST, ChatIntent.SPEND),
                        ),
                    ),
                routing = RoutingSpec(),
                outOfScope =
                    OutOfScopeSpec(
                        keywords =
                            listOf(
                                "stock tip", "should i buy reliance", "which stock", "file my taxes",
                                "tax filing", "itr", "legal advice", "sue", "lawyer", "crypto tip",
                                "guaranteed return", "insider",
                            ),
                        offerChips = listOf(ChatIntent.HEALTH, ChatIntent.AFFORD),
                    ),
            )
    }
}

/**
 * One route: the words that pick an intent, the tools it needs, and what to offer next.
 * Input:  [intent]; [tools]; [keywords] — lower-case, matched as substrings of the question;
 *         [chips] — CHT-003's suggestions.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class IntentRoute(
    val intent: ChatIntent,
    val tools: List<ToolName>,
    val keywords: List<String>,
    val chips: List<ChatIntent>,
) {
    init {
        require(keywords.isNotEmpty()) { "an intent with no keywords can never be matched" }
        require(tools.isNotEmpty()) { "an intent with no tools would answer from nothing (CHT-001)" }
    }
}

/**
 * The registry's `routing` block (CHT-ROUTE v1.0).
 * Input:  [minKeywordHits]; [maxToolsPerTurn] — one question cannot walk the whole registry;
 *         [maxChips]. Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class RoutingSpec(
    val minKeywordHits: Int = 1,
    val maxToolsPerTurn: Int = 2,
    val maxChips: Int = 3,
)

/**
 * The registry's `out_of_scope` block (CHT-REFUSE v1.0).
 * Input:  [keywords]; [offerChips] — what to offer instead of the thing being refused.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
data class OutOfScopeSpec(
    val keywords: List<String>,
    val offerChips: List<ChatIntent>,
)

/**
 * The registry's intents. A typed set, so a question routes to a known destination or to none —
 * never to a string nobody defined.
 * Changelog: 2026-09-26 — Created for issue 10.5 from tool-registry.json v1.1.
 */
enum class ChatIntent {
    /** Where the money went. */
    SPEND,

    /** What is there now. */
    BALANCE,

    /** What the next ninety days look like. */
    FORECAST,

    /** Whether the plan is holding. */
    BUDGET,

    /** What is being saved for. */
    GOALS,

    /** Whether a purchase is affordable. */
    AFFORD,

    /** The financial health score. */
    HEALTH,

    /** Prepay or invest, and which debt first. */
    DEBT,

    /** The buy list. */
    BUYLIST,

    /** A vehicle's upcoming costs. */
    VEHICLE,
}

/**
 * Every tool the registry declares (§19.2). The engine may plan only these, and the executor may
 * run only these — which is what makes "tools are the only way chat touches data" checkable.
 * Changelog: 2026-09-26 — Created for issue 10.5 from tool-registry.json v1.1.
 */
enum class ToolName(
    /** The registry's own name for it, so the mirror and the file can be compared literally. */
    val registryName: String,
    /** Whether it reads or produces a draft the user must confirm — the registry's `access`. */
    val access: ToolAccess,
) {
    /** Aggregated spend for a period. */
    QUERY_SPEND("query_spend", ToolAccess.READ),

    /** Balances and liquid runway. */
    GET_BALANCE("get_balance", ToolAccess.READ),

    /** The cash-flow forecast and its crunch days. */
    GET_FORECAST("get_forecast", ToolAccess.READ),

    /** Budget status per category. */
    GET_BUDGET_STATUS("get_budget_status", ToolAccess.READ),

    /** Goals and what each needs. */
    GET_GOALS("get_goals", ToolAccess.READ),

    /** The Purchase Advisor's verdict. */
    PURCHASE_CHECK("purchase_check", ToolAccess.READ),

    /** The financial health score. */
    GET_HEALTH_SCORE("get_health_score", ToolAccess.READ),

    /** Why an insight fired. */
    EXPLAIN_INSIGHT("explain_insight", ToolAccess.READ),

    /** A what-if simulation. */
    SIMULATE("simulate", ToolAccess.READ),

    /** A transaction draft the user must confirm. */
    CREATE_TXN_DRAFT("create_txn_draft", ToolAccess.WRITE_DRAFT),

    /** Market status (§30). */
    MARKET_STATUS("market_status", ToolAccess.READ),

    /** Whether today scores as an opportunity (§30). */
    OPPORTUNITY_CHECK("opportunity_check", ToolAccess.READ),

    /** Adds a wish to the buy list — a draft, not a purchase. */
    ADD_TO_BUYLIST("add_to_buylist", ToolAccess.WRITE_DRAFT),

    /** The buy list with its interview state. */
    REVIEW_BUYLIST("review_buylist", ToolAccess.READ),

    /** When a vehicle is next due, and what it will cost (§12, AI-VEH — issue 10.5 added it). */
    GET_VEHICLE_STATUS("get_vehicle_status", ToolAccess.READ),

    /** FX conversion at cached reference rates. */
    CONVERT_CURRENCY("convert_currency", ToolAccess.READ),
}

/**
 * What a tool is allowed to do. There is no third value: **chat never writes directly** — the
 * registry's own write policy — and a draft is something the user confirms (P-07).
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
enum class ToolAccess {
    /** Reads and returns aggregates. */
    READ,

    /** Produces a draft or a list entry the user must confirm. */
    WRITE_DRAFT,
}
