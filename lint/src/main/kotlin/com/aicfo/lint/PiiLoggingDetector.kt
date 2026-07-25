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
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UQualifiedReferenceExpression

/**
 * Fails the build when a log line looks like it carries an amount or personal data (P-01).
 *
 * Why:  logcat is readable by anyone with the device plugged in, survives in bug reports, and is
 *       collected by crash reporters. A privacy-first finance app that prints a balance or an
 *       account name to it has leaked exactly the data `CLAUDE.md` §1 promises never leaves the
 *       device. `CLAUDE.md` §5 bans it and, until this detector, nothing checked — the same
 *       documented-but-unenforced gap the governance audit raised as its systemic finding.
 * What: flags `Log.*`, `println` and `print` whose arguments mention a monetary or personal
 *       identifier, matching whole camelCase words rather than substrings.
 * Result: `Log.d(TAG, "balance=$balanceMinor")` fails lint; `Log.d(TAG, "saved code=$code")` does not.
 * Changelog: 2026-07-25 — Created for issue 1.5 (P-01, CLAUDE.md §5).
 *
 * Matched on call shape rather than resolved symbol so the rule keeps working without
 * `android.jar` on the analysis classpath. This one leans slightly stricter than the money
 * heuristic: the fix (drop the value, log an id or an error code) is trivial, while the cost of a
 * miss is a privacy incident in a financial app.
 */
class PiiLoggingDetector :
    Detector(),
    SourceCodeScanner {
    /** Input: none. Output: the UAST node types this detector inspects. */
    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UCallExpression::class.java)

    /**
     * Input:  [context] — the file being analysed.
     * Output: a handler reporting log calls whose arguments name money or personal data.
     */
    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitCallExpression(node: UCallExpression) {
                if (!isLoggingCall(node)) return
                val arguments = node.valueArguments.joinToString(" ") { it.asSourceString() }
                val leaked = sensitiveWordsIn(arguments)
                if (leaked.isEmpty()) return
                context.report(
                    ISSUE,
                    node,
                    context.getLocation(node),
                    "P-01: this log line looks like it carries ${leaked.joinToString()}. Logcat " +
                        "is readable over USB and travels in bug reports — never log amounts, " +
                        "account or merchant names, or contact details. Log a stable id or an " +
                        "`AppError.code` instead; security events go to `audit_log`.",
                )
            }
        }

    companion object {
        /** `Log.<level>` plus the two stdout calls that reach logcat on Android. */
        private val LOG_LEVELS = setOf("d", "e", "i", "v", "w", "wtf")
        private val STDOUT_CALLS = setOf("println", "print")
        private const val LOG_RECEIVER = "Log"
        private const val ANDROID_LOG = "android.util.Log"

        /** Words that mean money or a person. Kept in sync with ADR-0001. */
        private val SENSITIVE_WORDS =
            setOf(
                "amount", "balance", "paise", "rupee", "rupees", "money", "salary", "income",
                "price", "minor", "networth", "payment", "emi", "premium",
                "account", "merchant", "payee", "beneficiary", "customer", "email", "phone",
                "mobile", "upi", "vpa", "iban", "aadhaar", "address", "pin", "otp", "token",
            )

        /**
         * Whether a call writes to a log.
         * Why:    `Log.d(...)` does not have one UAST shape. When `android.util.Log` resolves
         *         (a real Android build) the qualifier hangs off the parent expression; when it
         *         does not (a lint fixture with no SDK) it is the call's own receiver. Checking a
         *         single shape passed the unit test and then silently failed on the real module —
         *         so all three signals are checked, and resolution is a bonus rather than a
         *         requirement.
         * Result: true for `Log.<level>(…)`, `println(…)` and `print(…)`.
         * Input:  [node] — the call. Output: [Boolean].
         */
        internal fun isLoggingCall(node: UCallExpression): Boolean {
            val name = node.methodName ?: return false
            if (name in STDOUT_CALLS) return true
            if (name !in LOG_LEVELS) return false
            if (node.resolve()?.containingClass?.qualifiedName == ANDROID_LOG) return true
            val receiver =
                node.receiver?.asSourceString()
                    ?: (node.uastParent as? UQualifiedReferenceExpression)?.receiver?.asSourceString()
            return receiver == LOG_RECEIVER
        }

        /**
         * The sensitive words appearing in a fragment of source.
         * Why:    whole-word camelCase matching stops "expand" matching "pan" and "spinner"
         *         matching "pin" — substring matching here would produce constant false alarms.
         * Result: the distinct sensitive words found, empty when the line is safe.
         * Input:  [source] — the call's argument text. Output: a set of matched words.
         */
        internal fun sensitiveWordsIn(source: String): Set<String> =
            Regex("[A-Za-z_][A-Za-z0-9_]*")
                .findAll(source)
                .flatMap { MoneyDoubleDetector.splitCamelCase(it.value).asSequence() }
                .filter { it in SENSITIVE_WORDS }
                .toSet()

        /** The reported issue: severity ERROR, so it blocks rather than warns. */
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "CfoPiiInLogs",
                briefDescription = "Never log amounts or personal data",
                explanation =
                    """
                    P-01 / CLAUDE.md §5: no financial data leaves the device without explicit \
                    consent, and logcat is not private — it is readable over USB and included in \
                    bug reports. Do not log amounts, account or merchant names, or contact \
                    details. Log a stable identifier or an `AppError.code`; route security events \
                    to `audit_log`.
                    """,
                category = Category.SECURITY,
                priority = 10,
                severity = Severity.ERROR,
                implementation = Implementation(PiiLoggingDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )
    }
}
