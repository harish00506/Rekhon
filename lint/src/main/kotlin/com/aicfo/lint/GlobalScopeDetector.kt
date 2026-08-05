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
import org.jetbrains.uast.UImportStatement
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.getParentOfType

/**
 * Fails the build on any use of `GlobalScope` (ARC-006, SRS §21.2).
 *
 * Why:  a coroutine launched in `GlobalScope` belongs to nothing. Nobody cancels it, so it
 *       outlives the screen that started it, keeps its captured references alive, and can write
 *       to a database after the user has locked the app or revoked a consent. It also cannot be
 *       controlled by a test, which quietly costs determinism (P-08). Structured concurrency on
 *       an injected scope has none of those properties, and `DispatcherProvider` (issue 1.3)
 *       exists so there is never a reason to reach for the global one.
 * What: flags every reference to the `GlobalScope` identifier, excluding the import statement so
 *       one usage is reported once rather than twice.
 * Result: `GlobalScope.launch { }` fails `./gradlew lint`; `scope.launch { }` does not.
 * Changelog: 2026-07-25 — Created for issue 1.5 / task 1.1.5 (ARC-006).
 *
 * Matches on the identifier rather than the resolved symbol on purpose: resolution needs
 * kotlinx-coroutines on the analysis classpath, and a detector that silently stops firing when a
 * classpath changes is worse than one that occasionally flags a variable someone named
 * `GlobalScope` — which would itself be worth a review comment.
 */
class GlobalScopeDetector :
    Detector(),
    SourceCodeScanner {
    /** Input: none. Output: the UAST node types this detector inspects. */
    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(USimpleNameReferenceExpression::class.java)

    /**
     * Input:  [context] — the file being analysed.
     * Output: a handler reporting each non-import reference to `GlobalScope`.
     */
    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression) {
                if (node.identifier != GLOBAL_SCOPE) return
                // The import is the same usage seen twice; report the call site only.
                if (node.getParentOfType<UImportStatement>() != null) return
                context.report(
                    ISSUE,
                    node,
                    context.getLocation(node),
                    "ARC-006: `GlobalScope` is banned. A coroutine launched here is never " +
                        "cancelled — it outlives the screen, keeps its captures alive and can " +
                        "write after the app is locked. Inject a scope (or use `coroutineScope`) " +
                        "and take dispatchers from `DispatcherProvider`.",
                )
            }
        }

    companion object {
        private const val GLOBAL_SCOPE = "GlobalScope"

        /** The reported issue: severity ERROR, so it blocks rather than warns. */
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "CfoGlobalScope",
                briefDescription = "GlobalScope is banned — use an injected scope",
                explanation =
                    """
                    ARC-006 (SRS §21.2): all async work uses structured concurrency on injected \
                    scopes. `GlobalScope` coroutines are unowned and uncancellable, so they leak \
                    and can outlive the consent or session that authorised their work. Inject a \
                    `CoroutineScope`, or use `coroutineScope`/`viewModelScope`, with dispatchers \
                    from `DispatcherProvider`.
                    """,
                category = Category.CORRECTNESS,
                priority = 9,
                severity = Severity.ERROR,
                implementation = Implementation(GlobalScopeDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )
    }
}
