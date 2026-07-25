package com.aicfo.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

/**
 * Fails the build on a wall-clock read inside domain code (TIM-001, SRS §21.4).
 *
 * Why:  two separate problems, both expensive. Correctness: `LocalDate.now()` answers in the JVM
 *       default zone, but "what day is it?" in this app is a question about the **profile** zone —
 *       a spend at 23:30 IST belongs to that day's budget, and a UTC reading files it in the
 *       wrong day, month and quarter. Testability (P-08): an engine that reads the wall clock
 *       cannot be given a fixed input, so its forecasts and due-date rules can never be pinned by
 *       a test. `Clock`/`FakeClock` (issue 1.3) exist precisely so neither happens.
 * What: flags `System.currentTimeMillis()` and the `java.time` `now()` factories — but only in
 *       files under `:domain:*` or `:core:model`.
 * Result: a wall-clock read in an engine fails `./gradlew lint`; the same call in `:app` DI, or
 *       inside `SystemClock` itself, does not.
 * Changelog: 2026-07-25 — Created for issue 1.5 / task 1.1.5 (TIM-001).
 *
 * Scoping is by file path because that is what the module boundary actually is at analysis time.
 * `:core:common` is excluded deliberately: `SystemClock.nowUtcMillis()` is the one sanctioned
 * wall-clock read in the codebase, and a rule that banned its own implementation would just be
 * suppressed — which is how rules die.
 */
class DomainClockDetector :
    Detector(),
    SourceCodeScanner {
    /** Input: none. Output: the method names worth resolving. */
    override fun getApplicableMethodNames(): List<String> = listOf("currentTimeMillis", "now")

    /**
     * Input:  [context], the call [node], and the resolved [method].
     * Output: reports when a banned time source is read from domain code.
     */
    override fun visitMethodCall(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ) {
        val owner = method.containingClass?.qualifiedName ?: return
        if (owner !in BANNED_TIME_SOURCES) return
        if (!isDomainScope(context.file.path, context.uastFile?.packageName)) return
        context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "TIM-001: domain code must not read the wall clock. Inject `Clock` and use " +
                "`nowUtcMillis()` / `today()` / `startOfDay()`, which resolve in the profile " +
                "time zone and can be fixed by `FakeClock` in tests.",
        )
    }

    companion object {
        /** Every type whose static `now()`-style factory reads ambient time. */
        private val BANNED_TIME_SOURCES =
            setOf(
                "java.lang.System",
                "java.time.Instant",
                "java.time.LocalDate",
                "java.time.LocalDateTime",
                "java.time.LocalTime",
                "java.time.ZonedDateTime",
                "java.time.OffsetDateTime",
                "java.time.Year",
                "java.time.YearMonth",
            )

        /** Path fragments that mean "this is domain code" (§21.2). */
        private val DOMAIN_PATHS = listOf("/domain/", "/core/model/")

        /** The same modules named as packages — the signal that survives a moved source root. */
        private val DOMAIN_PACKAGES = listOf("com.aicfo.domain", "com.aicfo.core.model")

        /** `SystemClock` lives here and is the one sanctioned wall-clock read. */
        private const val CLOCK_MODULE_PATH = "/core/common/"
        private const val CLOCK_MODULE_PACKAGE = "com.aicfo.core.common"

        /**
         * Whether a source file belongs to a module the rule covers.
         * Why:    the same call is a bug in an engine and correct in DI wiring, so the rule has to
         *         know where it is. Two signals rather than one: the file path (what the module
         *         layout says) **or** the package (what the code says). Path alone proved brittle —
         *         a lint test harness relocates fixture files, and a rule that silently stops
         *         firing when a directory moves is worse than no rule. Separators are normalised
         *         so Windows and CI agree.
         * Result: true for `:domain:*` and `:core:model`; false for `:core:common`, which owns the
         *         sanctioned `SystemClock`, and everywhere else.
         * Input:  [path] — the analysed file's path; [packageName] — its declared package, if any.
         * Output: [Boolean].
         */
        internal fun isDomainScope(
            path: String,
            packageName: String?,
        ): Boolean {
            val normalisedPath = "/" + path.replace('\\', '/').trimStart('/')
            val pkg = packageName.orEmpty()
            val isClockModule =
                normalisedPath.contains(CLOCK_MODULE_PATH) ||
                    pkg == CLOCK_MODULE_PACKAGE ||
                    pkg.startsWith("$CLOCK_MODULE_PACKAGE.")
            val isDomain =
                DOMAIN_PATHS.any { normalisedPath.contains(it) } ||
                    DOMAIN_PACKAGES.any { pkg == it || pkg.startsWith("$it.") }
            return isDomain && !isClockModule
        }

        /** The reported issue: severity ERROR, so it blocks rather than warns. */
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "CfoWallClockInDomain",
                briefDescription = "Domain code must read time through the injected Clock",
                explanation =
                    """
                    TIM-001 (SRS §21.4): timestamps are UTC epoch millis and all calendar logic — \
                    day rollover, month boundaries, due dates — resolves in the profile time zone \
                    through an injected `Clock`. Reading the wall clock directly is both wrong \
                    (it answers in the JVM zone) and untestable (a test cannot fix "now"). \
                    Inject `Clock`; tests inject `FakeClock`.
                    """,
                category = Category.CORRECTNESS,
                priority = 9,
                severity = Severity.ERROR,
                implementation = Implementation(DomainClockDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )
    }
}
