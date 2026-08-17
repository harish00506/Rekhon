package com.aicfo.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType

/**
 * Fails the build on user-visible text written as a literal in a feature screen (SRS §21.6).
 *
 * Why:  a string baked into Kotlin cannot be translated, cannot use ICU plurals, and cannot be
 *       adjusted for an accessibility or regional review — and it is invisible to every tool that
 *       audits copy. `CLAUDE.md` §5 requires every user-visible string in `strings.xml`. The rule
 *       is easy to follow and just as easy to forget under deadline, which is exactly the kind of
 *       rule a machine should hold rather than a reviewer.
 * What: flags a non-blank string literal passed to a `Text(...)` call inside a `:feature:*` module.
 * Result: `Text("Hello")` fails `./gradlew lint`; `Text(stringResource(R.string.hello))` does not.
 * Changelog: 2026-07-25 — Created for issue 1.5 / task 1.1.5 (§21.6).
 *
 * Two deliberate exemptions, both to keep the rule followable rather than resented:
 * `@Preview` functions (sample data that ships to no user) and empty literals (a placeholder, not
 * copy). Scope is `:feature:*` and `:core:designsystem` — both are UI, and the design system is
 * where a stray literal would be copied into every screen that uses the component.
 * `contentDescription` is not covered yet — it needs parameter resolution against Compose, which
 * is not on the analysis classpath until the design system lands. See ADR-0001.
 */
class HardcodedUiStringDetector :
    Detector(),
    SourceCodeScanner {
    /** Input: none. Output: the UAST node types this detector inspects. */
    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UCallExpression::class.java)

    /**
     * Input:  [context] — the file being analysed.
     * Output: a handler reporting hardcoded text in feature-module UI calls.
     */
    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitCallExpression(node: UCallExpression) {
                if (!isFeatureScope(context.file.path, context.uastFile?.packageName)) return
                if (node.methodName !in TEXT_CALLS) return
                if (isInsidePreview(node)) return
                node.valueArguments
                    // A Kotlin "literal" is a string *template* in UAST, not a ULiteralExpression,
                    // so ask the constant evaluator instead of matching on node type. It also
                    // folds concatenation, and returns null for `stringResource(...)` — which is
                    // exactly the call we must not flag.
                    .filter { ConstantEvaluator.evaluateString(context, it, false)?.isNotBlank() == true }
                    .forEach { literal ->
                        context.report(
                            ISSUE,
                            literal,
                            context.getLocation(literal),
                            "Hardcoded user-visible text. Move it to `strings.xml` and read it " +
                                "with `stringResource(...)` — a literal here cannot be " +
                                "translated, cannot use ICU plurals, and is invisible to copy " +
                                "and accessibility review (CLAUDE.md §5).",
                        )
                    }
            }
        }

    companion object {
        /** Composables whose argument is read out to the user. */
        private val TEXT_CALLS = setOf("Text")

        /**
         * UI modules in scope: feature screens, the design system (joined at issue 1.8), and the
         * home-screen widget (joined at issue 5.5).
         *
         * `:widget` is not a `:feature:*` module — it has no ViewModel and no nav graph — so it was
         * silently outside this rule while it was a placeholder, and would have stayed outside it
         * the moment it grew real text. Its Glance `Text(...)` matches [TEXT_CALLS] by name already,
         * so covering the module was two entries. Nothing else needed to change.
         */
        private val UI_PATHS = listOf("/feature/", "/designsystem/", "/widget/")
        private val UI_PACKAGES = listOf("com.aicfo.feature", "com.aicfo.core.designsystem", "com.aicfo.widget")

        /**
         * Whether the file belongs to a feature module.
         * Why:    checks the path **or** the package, for the same reason as
         *         [DomainClockDetector.isDomainScope] — path alone stops matching the moment a
         *         source root moves or a test harness relocates the file.
         * Result: true under `:feature:*` or `:core:designsystem`.
         * Input:  [path] — the file's path; [packageName] — its declared package. Output: [Boolean].
         */
        internal fun isFeatureScope(
            path: String,
            packageName: String?,
        ): Boolean {
            val normalisedPath = "/" + path.replace('\\', '/').trimStart('/')
            if (UI_PATHS.any { normalisedPath.contains(it) }) return true
            val pkg = packageName.orEmpty()
            return UI_PACKAGES.any { pkg == it || pkg.startsWith("$it.") }
        }

        /**
         * Whether the call sits in a `@Preview` function.
         * Why:    previews are developer sample data that never reaches a user, so requiring a
         *         translated resource for them is friction with no benefit — and friction is what
         *         gets a rule suppressed.
         * Result: true when any enclosing-method annotation ends in `Preview`.
         * Input:  [node] — the call. Output: [Boolean].
         */
        internal fun isInsidePreview(node: UCallExpression): Boolean {
            val method = node.getParentOfType<UMethod>() ?: return false
            return method.uAnnotations.any { it.qualifiedName?.endsWith("Preview") == true }
        }

        /** The reported issue: severity ERROR, so it blocks rather than warns. */
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "CfoHardcodedUiString",
                briefDescription = "User-visible strings belong in strings.xml",
                explanation =
                    """
                    SRS §21.6 / CLAUDE.md §5: every user-visible string lives in `strings.xml` \
                    with ICU plurals, so it can be translated, reviewed and adapted. A literal in \
                    a composable cannot. Use `stringResource(R.string.…)`. `@Preview` sample data \
                    and empty placeholders are exempt.
                    """,
                category = Category.CORRECTNESS,
                priority = 7,
                severity = Severity.ERROR,
                implementation = Implementation(HardcodedUiStringDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )
    }
}
