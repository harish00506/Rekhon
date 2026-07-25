package com.aicfo.core.datastore

/**
 * Every feature that needs the user's explicit permission before data leaves the device (P-01).
 *
 * Why:  P-01 makes consent **per feature**, not one blanket "share my data" switch — agreeing to
 *       read SMS for transaction detection says nothing about sending anything to a cloud model.
 *       Enumerating them here rather than passing strings around means a typo cannot create a
 *       phantom consent: a misspelled key would silently read as "not granted", the feature would
 *       appear broken, and nobody would suspect the consent layer.
 * What: the closed set of consent-gated features, each with the stable id stored on disk.
 * Result: adding a feature is one entry here plus its own gate; it is never a schema migration,
 *       because the store keys a map by these ids.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 *
 * **[id] is persisted — never change one.** Renaming an id orphans every existing user's decision,
 * which silently reads as "not granted" and re-prompts them. Deprecate an entry instead, the same
 * rule the `ai/` rulebook uses for its rule ids.
 *
 * Input:  [id] — the stable on-disk key. Output: an enum constant.
 */
enum class ConsentFeature(
    val id: String,
) {
    /** Reading transaction SMS on-device to detect spending (issue 3.9). Nothing is uploaded. */
    SMS_PARSING("sms_parsing"),

    /** Fetching gold/crypto/fund prices from a network API (issue 6.5). */
    MARKET_DATA("market_data"),

    /** Sending the structured context pack — never raw transactions — to a cloud LLM (§19.4). */
    CLOUD_LLM("cloud_llm"),

    /** Storing the end-to-end-encrypted backup archive off-device (issue 8.x). */
    CLOUD_BACKUP("cloud_backup"),
    ;

    companion object {
        /**
         * Resolves a stored id back to a feature.
         * Why:    the store may hold an id from a newer or older build; an unknown one must not
         *         crash and must not be guessed at.
         * Result: the matching feature, or `null` for an id this build does not know.
         * Input:  [id] — the persisted key. Output: `ConsentFeature?`.
         */
        fun fromId(id: String): ConsentFeature? = entries.firstOrNull { it.id == id }
    }
}
