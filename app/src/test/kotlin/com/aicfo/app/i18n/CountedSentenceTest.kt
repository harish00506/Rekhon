package com.aicfo.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The sentences that count things say "1 month", never "1 months" (§21.6's ICU rule).
 *
 * Why:  five strings in this app were a single `<string>` used for every quantity, so a one-month
 *       emergency-fund plan read "1 months of your needs budget" and a single imported row read
 *       "Imported 1 rows." Android Lint reported them as `PluralsCandidate` and they became
 *       `<plurals>` — a change **no existing test could have caught**. `TranslationCoverageTest`
 *       checks that a plural stays a plural in every locale and declares the categories its
 *       language needs, but not that the `one` form actually reads as a singular: a plurals block
 *       with the plural sentence pasted into both categories satisfies every check in that file and
 *       still prints "1 months".
 *
 *       So this pins the content, in the one place that can see every module's resources at once.
 *       [StringCatalogue] reads the files from disk, which is why no Robolectric or merged-resource
 *       machinery is needed to assert across `:app`, `:feature:dashboard`, `:feature:emergencyfund`
 *       and `:feature:onboarding` together.
 * What: for each converted key, the English `one` and `other` forms exactly, and the plural *shape*
 *       in all three translations.
 * Result: re-flattening one of these to a `<string>`, or pasting the plural wording into `one`,
 *         fails here and names the key.
 * Changelog: 2026-10-15 — Created alongside the `PluralsCandidate` conversions.
 *
 * Deliberately **not** a generic "every plural's forms must differ" rule: `transactions_selected`
 * and `transactions_deleted` are correctly identical in English — "%1$d selected" has no noun to
 * inflect — so that rule would fail on entries that are already right. Naming the five is honest.
 */
class CountedSentenceTest {
    /**
     * One counted sentence and how English must read it.
     * Why:    a named type rather than a nested `Pair`, so the expectations below read as data and
     *         the assertions stay one level deep.
     * Input:  [module] — repository-relative module path; [key]; [one]; [other]. Output: the value.
     */
    private data class Counted(
        val module: String,
        val key: String,
        val one: String,
        val other: String,
    )

    private val expected =
        listOf(
            Counted(
                module = "app",
                key = "card_due_title",
                one = "%1\$s payment due in %2\$d day",
                other = "%1\$s payment due in %2\$d days",
            ),
            Counted(
                module = "feature/dashboard",
                key = "dashboard_archive_imported",
                one = "Imported %1\$d row.",
                other = "Imported %1\$d rows.",
            ),
            Counted(
                module = "feature/emergencyfund",
                key = "emf_top_up",
                one = "%1\$s a month closes it over %2\$d month",
                other = "%1\$s a month closes it over %2\$d months",
            ),
            Counted(
                module = "feature/emergencyfund",
                key = "emf_evidence_multiplier",
                one = "Held for %1\$d month: 6 to start with, %2\$s",
                other = "Held for %1\$d months: 6 to start with, %2\$s",
            ),
            Counted(
                module = "feature/onboarding",
                key = "onboarding_quick_setup_summary_emergency_help",
                one = "%1\$d month of your needs budget.",
                other = "%1\$d months of your needs budget.",
            ),
        )

    @Test
    fun `every counted sentence has a singular form that reads as a singular`() {
        val wrong = expected.flatMap { englishFaults(it) }

        assertEquals(
            "\"1 months\" is the kind of wrongness that makes everything around it look careless",
            emptyList<String>(),
            wrong,
        )
    }

    @Test
    fun `every counted sentence is a plurals in every shipped locale`() {
        // The shape check `TranslationCoverageTest` already makes, narrowed to these five keys so a
        // failure names the conversion rather than the whole catalogue — and so a locale file left
        // holding the old `<string>` is caught even if that broader test is ever relaxed.
        val gaps =
            expected.flatMap { counted ->
                StringCatalogue.SHIPPED_LOCALES.mapNotNull { locale -> localeFault(counted, locale) }
            }

        assertEquals(emptyList<String>(), gaps)
    }

    /**
     * Result: what is wrong with [counted]'s English entry, empty when nothing is.
     * Why:    returning the faults rather than asserting inside a loop keeps each test one block
     *         deep and lets a failure list every key at once instead of the first.
     * Input:  [counted]. Output: `List<String>` — human-readable faults.
     */
    private fun englishFaults(counted: Counted): List<String> {
        val where = ":${counted.module} · ${counted.key}"
        return when (val entry = entry(counted, locale = null)) {
            null -> listOf("$where is absent")
            !is StringCatalogue.Entry.Plural ->
                listOf("$where is a <string>; a counted sentence must be <plurals>")
            else ->
                listOfNotNull(
                    fault(where, "one", counted.one, entry.byQuantity["one"]),
                    fault(where, "other", counted.other, entry.byQuantity["other"]),
                )
        }
    }

    /**
     * Result: a fault line when [found] is not [want], otherwise `null`.
     * Input: [where]; [quantity] — the CLDR category; [want]; [found]. Output: `String?`.
     */
    private fun fault(
        where: String,
        quantity: String,
        want: String,
        found: String?,
    ): String? = if (found == want) null else "$where · $quantity: expected \"$want\", found \"$found\""

    /**
     * Result: what is wrong with [counted] in [locale], or `null` when nothing is.
     * Input: [counted]; [locale]. Output: `String?`.
     */
    private fun localeFault(
        counted: Counted,
        locale: String,
    ): String? {
        val where = ":${counted.module} · $locale · ${counted.key}"
        return when (val entry = entry(counted, locale)) {
            null -> "$where is absent"
            !is StringCatalogue.Entry.Plural -> "$where is a <string>"
            else ->
                when {
                    entry.byQuantity.keys != CATEGORIES -> "$where has ${entry.byQuantity.keys}"
                    entry.byQuantity.values.any { it.isBlank() } -> "$where has an empty form"
                    else -> null
                }
        }
    }

    /** Result: the parsed entry, or `null`. Input: [counted]; [locale] — `null` for English. */
    private fun entry(
        counted: Counted,
        locale: String?,
    ): StringCatalogue.Entry? {
        val module = File(StringCatalogue.root, counted.module)
        return StringCatalogue.parse(StringCatalogue.file(module, locale))[counted.key]
    }

    private companion object {
        /** The categories Hindi, Kannada and Tamil each need — the same set `values/` declares. */
        val CATEGORIES = setOf("one", "other")
    }
}
