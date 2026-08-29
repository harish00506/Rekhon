package com.aicfo.core.model

/**
 * What a rupee spent in a category *became* (issue 4.1; SRS §8.3, AI-CLSN-001).
 *
 * Why:  §8.3 makes nature "the axis the advice layer is built on" — the 50/30/20 rings, true spend,
 *       the emergency-fund essentials and the Purchase Advisor's scrutiny ladder all branch on it.
 *       The `category.nature` column has held a free-text string since issue 1.6 and, exactly as
 *       with [TransactionSource], nothing but a closed enum stops a typo becoming a row every later
 *       query silently misses. Issue 4.3 classifies *transactions* by nature; this is the category
 *       default it starts from (§8.3.1 step 5).
 * What: §8.3's five natures, each carrying the exact string the column stores and the spelling the
 *       knowledge base uses for the same value.
 * Result: a category's nature is a compile-time value everywhere above the database.
 * Changelog: 2026-08-08 — Created for issue 4.1 (AI-CLSN-001).
 *
 * **The stored spelling and the knowledge-base spelling differ for exactly one value**, and neither
 * side gets changed to match the other. `category.nature` has stored `invest` since issue 1.6 (see
 * `DemoDataset.CATEGORY_SPECS`, which wrote the first rows), while `ai/knowledge/classification-kb.json`
 * says `INVESTMENT` because §8.3's table does. Rewriting the column would need a migration for a
 * cosmetic gain; rewriting the knowledge base would put it out of step with the SRS it was
 * transcribed from. So the translation lives here, in one place, checked by a test — [kbValue] is
 * the only thing that may be compared against the knowledge base, and [storedValue] the only thing
 * that may be written to a column.
 */
enum class CategoryNature(val storedValue: String, val kbValue: String) {
    /** Essential living costs — rent, groceries, utilities, commute, medicines, fees. The 50% band. */
    NEED("need", "NEED"),

    /** Discretionary consumption — dining, entertainment, shopping, travel. The 30% band. */
    WANT("want", "WANT"),

    /** Money that stays the user's and grows — SIPs, FD/RD/PPF, goal transfers. The 20% band. */
    INVEST("invest", "INVESTMENT"),

    /** Consumption converted to a lasting asset — gold, property, a vehicle. Excluded from true spend. */
    ASSET("asset", "ASSET"),

    /** Debt service — EMIs, card bill payments, prepayments. Principal reduces a liability; interest is a cost. */
    LIABILITY("liability", "LIABILITY"),
    ;

    companion object {
        /**
         * Parses a stored nature string.
         * Why:    the same forward-compatible shape [TransactionSource.fromStored] uses — a row
         *         written by a newer build may carry a nature this one has never heard of, and the
         *         honest response is to skip that row rather than crash the list. **An old build
         *         reading a newer database shows fewer categories, not an exception.**
         * Result: the matching nature, or `null` when the value is unknown or malformed.
         * Input:  [stored] — the value from `category.nature`.
         * Output: `CategoryNature?`.
         * Changelog: 2026-08-08 — Created for issue 4.1.
         */
        fun fromStored(stored: String): CategoryNature? = entries.firstOrNull { it.storedValue == stored }

        /**
         * Parses the spelling `ai/knowledge/classification-kb.json` uses.
         * Why:    the seed rows and (from issue 4.2) the merchant rules name their nature in the
         *         knowledge base's vocabulary, not the column's. This is the only sanctioned way in.
         * Result: the matching nature, or `null` for a spelling this build does not know.
         * Input:  [kb] — a `default_nature` value from the knowledge base.
         * Output: `CategoryNature?`.
         * Changelog: 2026-08-08 — Created for issue 4.1.
         */
        fun fromKb(kb: String): CategoryNature? = entries.firstOrNull { it.kbValue == kb }
    }
}

/**
 * A spending category as every screen above the database sees one (issue 3.1, widened by 4.1).
 *
 * Why:  FR-TXN-002's three taps are "amount → category suggestion → save", so the add screen has to
 *       be able to *offer* categories; FR-SET-001's editor has to be able to *change* them. Issue
 *       3.1 shipped the id and the name and said, in this comment, that `nature` and `parentId`
 *       belonged to 4.1 rather than to a model no screen was reading. That is now true: the editor
 *       shows the taxonomy grouped by nature, and one level of nesting is what §8's category/
 *       subcategory pair (FR-TXN-001) asks for.
 * What: the four fields a category screen and a category chip need between them.
 * Result: the taxonomy can be rendered, grouped and edited without any caller seeing a Room type
 *       (ARC-005).
 * Changelog: 2026-08-02 — Created for issue 3.1 (FR-TXN-002).
 *            2026-08-08 — Issue 4.1: moved out of `Transaction.kt`, gained [nature], [parentId] and
 *            [isSystem]; the "4.1 owns this" note it carried is discharged.
 *
 * **[isSystem] is not a permission.** It records only that the row came from
 * [CategorySeed] rather than from the user, so the editor can say "default" on a chip. A seeded
 * category is renamed, re-natured and deleted exactly like any other — it is the user's taxonomy,
 * not the app's (P-07).
 *
 * **Nesting is one level and the repository is what enforces it**, not this type: a category whose
 * [parentId] is set may not itself be a parent. Expressing that in the type would mean two classes
 * and a mapper, for an invariant one `require` states and one test proves.
 *
 * Input:  [id]; [name] — the user-facing label, the user's own words; [nature]; [parentId] — the
 *         parent category's id, or `null` for a top-level category; [isSystem].
 * Output: an immutable value.
 */
data class Category(
    val id: String,
    val name: String,
    val nature: CategoryNature,
    val parentId: String? = null,
    val isSystem: Boolean = false,
)

/**
 * One default category, copied from `ai/knowledge/classification-kb.json` (issue 4.1; §8.1, AI-ARC-006).
 *
 * Why:  the seeded row has to be able to say where it came from. §29's governance clause and
 *       AI-ARC-006 both turn on a stored result being traceable to the rule and *version* that
 *       produced it, and "the app just knows fifteen categories" is not traceable to anything.
 * What: the knowledge base's identity fields plus the two values the seed writes.
 * Result: every system category on a real profile can be attributed to a `CLS-CAT-*` row.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * Input:  [ruleId] — the permanent `CLS-CAT-*` id; [version] — that row's version, so a stored
 *         category stays reproducible when the defaults change; [key] — the stable slug the
 *         `category` row's id is derived from, which is **not** the name, so renaming a category in
 *         the editor never changes which row it is; [name] — the default display name; [nature].
 * Output: an immutable value.
 */
data class CategorySeedRow(
    val ruleId: String,
    val version: String,
    val key: String,
    val name: String,
    val nature: CategoryNature,
)

/**
 * The default taxonomy a real profile starts with (issue 4.1; §8.1, FR-SET-001).
 *
 * Why:  `CategoryEntity`, `CategoryDao` and `transactions.category_id` have existed since issue 1.6,
 *       and until this issue **the only thing that ever wrote a category row was `DemoDataset`** —
 *       `DemoModeRepositoryTest` asserts a real profile has exactly zero. So the add-transaction
 *       screen offered an empty chip row and every transaction on a real profile read
 *       "Uncategorised". A taxonomy editor with nothing to edit is not an answer to FR-SET-001;
 *       these fifteen rows are what it edits.
 * What: `category_defaults` from the knowledge base, as typed rows.
 * Result: `CategoryRepository.ensureSeeded()` has something to write, and every row it writes cites
 *       a rule id.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * **This is a copy, and the copy is guarded by a test rather than by discipline** — the same bargain
 * ADR-0005 struck for `QuickSetupRules` and ADR-0014 records for this file. Nothing in the app loads
 * `ai/` at runtime yet; `ClassificationKbDriftTest` reads the knowledge base from the repository and
 * fails the build the moment a row here disagrees with the row it was copied from.
 *
 * **Order is meaning here.** The rows are listed as the knowledge base lists them — NEEDs, then
 * WANTs, then INVEST and LIABILITY — but nothing depends on it: the editor groups by [nature] and
 * `CategoryDao.observeForProfile` orders by name. The drift test compares as an ordered list all the
 * same, because a reordered knowledge base is a change worth noticing.
 */
object CategorySeed {
    /** Result: the fifteen `CLS-CAT-*` defaults. Input: none. Output: an immutable list. */
    val rows: List<CategorySeedRow> =
        listOf(
            CategorySeedRow("CLS-CAT-001", "1.0", "rent", "Rent", CategoryNature.NEED),
            CategorySeedRow("CLS-CAT-002", "1.0", "groceries", "Groceries", CategoryNature.NEED),
            CategorySeedRow("CLS-CAT-003", "1.0", "utilities", "Utilities", CategoryNature.NEED),
            CategorySeedRow("CLS-CAT-004", "1.0", "fuel", "Fuel", CategoryNature.NEED),
            CategorySeedRow("CLS-CAT-005", "1.0", "transport", "Transport", CategoryNature.NEED),
            CategorySeedRow("CLS-CAT-006", "1.0", "health", "Health", CategoryNature.NEED),
            CategorySeedRow("CLS-CAT-007", "1.0", "insurance", "Insurance", CategoryNature.NEED),
            CategorySeedRow("CLS-CAT-008", "1.0", "education", "Education", CategoryNature.NEED),
            CategorySeedRow("CLS-CAT-009", "1.0", "dining", "Dining", CategoryNature.WANT),
            CategorySeedRow("CLS-CAT-010", "1.0", "shopping", "Shopping", CategoryNature.WANT),
            CategorySeedRow("CLS-CAT-011", "1.0", "entertainment", "Entertainment", CategoryNature.WANT),
            CategorySeedRow("CLS-CAT-012", "1.0", "travel", "Travel", CategoryNature.WANT),
            CategorySeedRow("CLS-CAT-013", "1.0", "subscriptions", "Subscriptions", CategoryNature.WANT),
            CategorySeedRow("CLS-CAT-014", "1.0", "investment", "Investment", CategoryNature.INVEST),
            CategorySeedRow("CLS-CAT-015", "1.0", "emi", "EMI", CategoryNature.LIABILITY),
        )

    /**
     * The knowledge-base file these rows were copied from, as `_meta.version`.
     *
     * Changelog: 2026-08-08 — `"1.1"` at creation for issue 4.1.
     *            2026-08-10 — `"1.2"` for issue 4.2, which added the `stage1` block. **None of the
     *            fifteen rows below changed**; this bump records that the *file* moved, which is what
     *            `ClassificationKbDriftTest` compares. A version this object claims and a version the
     *            file states are allowed to describe different edits — they are not allowed to differ.
     *            2026-08-10 — `"1.3"` for issue 4.3 (`CLS-NAT-*` ids and `stage_nature`); again none
     *            of the rows below changed.
     */
    const val KB_VERSION = "1.3"
}
