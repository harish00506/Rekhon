package com.aicfo.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UVariable

/**
 * Fails the build when a monetary value is held in a floating-point type (MNY-001, SRS §21.4).
 *
 * Why:  binary floating point cannot represent 0.10, so a `Double` rupee amount is wrong the
 *       moment it is added, split or accumulated — and wrong by an amount too small to notice
 *       until a reconciliation disagrees months later. `CLAUDE.md` §3 calls this review-blocking;
 *       this detector is what makes it build-blocking instead of a thing a reviewer must spot.
 * What: flags any variable, field or parameter whose **type** is floating point (or `BigDecimal`)
 *       and whose **name** looks monetary. Both halves are required, which is what keeps a
 *       `Double` animation fraction from tripping a money rule.
 * Result: `val amountMinor: Double` fails `./gradlew lint`; `val amountMinor: Long` does not.
 * Changelog: 2026-07-25 — Created for issue 1.5 / task 1.1.5 (MNY-001).
 *
 * The name heuristic is a judgement call, deliberately: Kotlin cannot express "this Double is
 * money" in the type system, which is the whole reason `Money` exists. Task §12 sets the
 * trade-off — prefer missing an exotic case over blocking valid code — so the word list below is
 * kept to terms that are monetary in this domain and little else. It is documented in
 * `docs/adr/0001-custom-lint-module-and-money-heuristic.md`; extend it there, not here.
 */
class MoneyDoubleDetector :
    Detector(),
    SourceCodeScanner {
    /** Input: none. Output: the UAST node types this detector inspects. */
    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UVariable::class.java)

    /**
     * Input:  [context] — the file being analysed.
     * Output: a handler that reports every floating-point declaration with a monetary name.
     */
    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitVariable(node: UVariable) {
                val name = node.name ?: return
                if (!isMonetaryName(name)) return
                val type = node.type.canonicalText.lowercase()
                if (FLOATING_TYPES.none { type.endsWith(it) }) return
                context.report(
                    ISSUE,
                    // Explicit UElement: UVariable is also a PsiElement, so the overload is
                    // otherwise ambiguous.
                    node as UElement,
                    context.getNameLocation(node),
                    "MNY-001: `$name` looks monetary but is `${node.type.presentableText}`. " +
                        "Money is `Long` minor units (paise) end-to-end — use the `Money` value " +
                        "class. Floating point cannot represent 0.10 exactly, so this value will " +
                        "drift. If this is not money, rename it so the intent is obvious.",
                )
            }
        }

    companion object {
        /** Type suffixes that cannot hold money. `BigDecimal` is exact but still not our type. */
        private val FLOATING_TYPES = listOf("double", "float", "bigdecimal")

        /** Words that mean money in this domain. See ADR-0001 before adding one. */
        private val MONEY_WORDS =
            setOf(
                "amount", "amounts", "price", "prices", "balance", "balances", "cost", "costs",
                "paise", "rupee", "rupees", "money", "fee", "fees", "salary", "income", "expense",
                "spend", "spending", "budget", "emi", "premium", "minor", "payment", "payments",
                "principal", "interest", "cashflow", "networth",
            )

        /**
         * Whether an identifier reads as monetary.
         * Why:    matching whole words inside camelCase avoids the substring trap — "expand"
         *         must not match "pan", and "minorVersion" must not match "minor" alone.
         * Result: true when any camelCase word is in [MONEY_WORDS].
         * Input:  [name] — the declaration's identifier. Output: [Boolean].
         */
        internal fun isMonetaryName(name: String): Boolean = splitCamelCase(name).any { it in MONEY_WORDS }

        /**
         * Splits an identifier into lowercase words.
         * Result: `amountMinor` → `[amount, minor]`; `amount_minor` → `[amount, minor]`.
         * Input:  [name] — an identifier. Output: its words, lowercased.
         */
        internal fun splitCamelCase(name: String): List<String> =
            name
                .split(Regex("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+"))
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }

        /** The reported issue: severity ERROR, so it blocks rather than warns. */
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "CfoMoneyAsFloatingPoint",
                briefDescription = "Money must be Long minor units, never Double/Float",
                explanation =
                    """
                    MNY-001 (SRS §21.4): money is `Long` minor units (paise) end-to-end, from the \
                    Room column to the screen, held in the `Money` value class. A `Double` or \
                    `Float` amount silently drifts, and `BigDecimal` is not the project's type. \
                    Division uses explicit HALF_EVEN rounding with remainder distribution — see \
                    `Money.percentOf` and `Money.allocate`.
                    """,
                category = Category.CORRECTNESS,
                priority = 10,
                severity = Severity.ERROR,
                implementation = Implementation(MoneyDoubleDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )
    }
}
