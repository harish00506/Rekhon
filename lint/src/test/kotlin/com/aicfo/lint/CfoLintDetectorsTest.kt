package com.aicfo.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

/**
 * Fixture tests for the five custom detectors — task 1.1.5 T1-T7 plus the PII-logging rule.
 *
 * Why:  these detectors decide whether a build passes, so a broken one is worse than none: it
 *       either blocks valid code (and gets disabled in frustration) or silently stops catching
 *       the thing it was written for. Task §12 sets the priority explicitly — "prefer missing an
 *       exotic case over blocking valid code" — so every rule is tested in **both** directions.
 * What: for each detector, a fixture that must produce exactly one error and one that must
 *       produce none, including the path-scoped cases where the same code is legal in one module
 *       and banned in another.
 * Result: the enforcement layer is itself enforced.
 * Changelog: 2026-07-25 — Created for issue 1.5.
 *
 * `allowMissingSdk()` throughout: these are source-only checks, so no Android SDK is needed and
 * requiring one would make the suite fail on a clean CI machine.
 */
class CfoLintDetectorsTest {
    // --- T1, T2 · MNY-001: no Double/Float/BigDecimal on money ------------------------------

    /** Input: a `Double` field named for money. Output: asserts one MNY-001 error (T1). */
    @Test
    fun `flags a Double used for an amount`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Txn.kt",
                    """
                    package com.aicfo.core.model

                    class Txn {
                        val amountMinor: Double = 0.0
                    }
                    """,
                ).indented(),
            ).issues(MoneyDoubleDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("MNY-001")
    }

    /** Input: a `Float` parameter named `price`. Output: asserts parameters are covered too. */
    @Test
    fun `flags a Float money parameter`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Cart.kt",
                    """
                    package com.aicfo.domain.usecase

                    fun total(price: Float): Float = price
                    """,
                ).indented(),
            ).issues(MoneyDoubleDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
    }

    /**
     * Input:  money held as `Long`/`Money`, plus a `Double` whose name is not monetary.
     * Output: asserts zero errors (T2). The second case is the false positive task §12 warns
     *         about — an animation fraction must not trip a money rule.
     */
    @Test
    fun `does not flag Long money or a non-money Double`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Ok.kt",
                    """
                    package com.aicfo.core.model

                    class Ok {
                        val amountMinor: Long = 0L
                        val balance: Money = Money(0)
                        val scrollFraction: Double = 0.5
                        val progressRatio: Float = 0.5f
                    }

                    class Money(val minor: Long)
                    """,
                ).indented(),
            ).issues(MoneyDoubleDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    // --- T3 · ARC-006: no GlobalScope ---------------------------------------------------------

    /** Input: `GlobalScope.launch { }`. Output: asserts one ARC-006 error (T3). */
    @Test
    fun `flags GlobalScope`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Runner.kt",
                    """
                    package com.aicfo.data.repository

                    // No coroutines import: the fixture classpath has no kotlinx-coroutines, and
                    // the detector matches the identifier rather than the resolved symbol.
                    class Runner {
                        fun go() {
                            GlobalScope.launch { }
                        }
                    }
                    """,
                ).indented(),
            ).issues(GlobalScopeDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("ARC-006")
    }

    /** Input: an injected scope. Output: asserts structured concurrency is left alone. */
    @Test
    fun `does not flag an injected scope`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Runner.kt",
                    """
                    package com.aicfo.data.repository

                    class Runner(private val scope: Any) {
                        fun go() {
                            scope.launch { }
                        }
                    }
                    """,
                ).indented(),
            ).issues(GlobalScopeDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    // --- T4, T5 · TIM-001: no wall clock in domain --------------------------------------------

    /** Input: `System.currentTimeMillis()` in a `:domain` file. Output: one TIM-001 error (T4). */
    @Test
    fun `flags a wall-clock read inside domain`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Forecast.kt",
                    """
                    package com.aicfo.domain.engines.forecast

                    class Forecast {
                        fun stamp(): Long = System.currentTimeMillis()
                    }
                    """,
                ).indented(),
            ).issues(DomainClockDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("TIM-001")
    }

    /** Input: `LocalDate.now()` in `:core:model`. Output: asserts the whole family is covered. */
    @Test
    fun `flags a date read inside core model`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Period.kt",
                    """
                    package com.aicfo.core.model

                    import java.time.LocalDate

                    class Period {
                        fun today(): LocalDate = LocalDate.now()
                    }
                    """,
                ).indented(),
            ).issues(DomainClockDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
    }

    /**
     * Input:  the same call in `:app` (DI wiring) and in `:core:common` (`SystemClock` itself).
     * Output: asserts zero errors (T5). Scoping matters both ways — the abstraction has to be
     *         allowed to read the clock, or TIM-001 would ban its own implementation.
     */
    @Test
    fun `does not flag the wall clock outside domain`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/TimeModule.kt",
                    """
                    package com.aicfo.app

                    object TimeModule {
                        fun now(): Long = System.currentTimeMillis()
                    }
                    """,
                ).indented(),
                kotlin(
                    "src/main/kotlin/SystemClock.kt",
                    """
                    package com.aicfo.core.common

                    class SystemClock {
                        fun nowUtcMillis(): Long = System.currentTimeMillis()
                    }
                    """,
                ).indented(),
            ).issues(DomainClockDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    // --- T6, T7 · strings live in strings.xml --------------------------------------------------

    /** Input: `Text("Hello")` in a feature module. Output: asserts one error (T6). */
    @Test
    fun `flags a hardcoded string in a feature composable`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Screen.kt",
                    """
                    package com.aicfo.feature.dashboard

                    fun Screen() {
                        Text("Hello")
                    }

                    fun Text(text: String) = text
                    """,
                ).indented(),
            ).issues(HardcodedUiStringDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("strings.xml")
    }

    /**
     * Input:  `stringResource(...)`, an empty literal, and a literal inside a `@Preview`.
     * Output: asserts zero errors (T7). Previews are sample data that never ships to a user, so
     *         blocking them would be the classic rule nobody can satisfy.
     */
    @Test
    fun `does not flag stringResource, empty text or preview sample data`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Screen.kt",
                    """
                    package com.aicfo.feature.dashboard

                    annotation class Preview

                    fun Screen() {
                        Text(stringResource(1))
                        Text("")
                    }

                    @Preview
                    fun ScreenPreview() {
                        Text("Sample balance")
                    }

                    fun Text(text: String) = text
                    fun stringResource(id: Int) = id.toString()
                    """,
                ).indented(),
            ).issues(HardcodedUiStringDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    /**
     * Input:  `Text("Safe to spend")` in the Glance widget module.
     * Output: asserts one error (issue 5.5).
     *
     * `:widget` is not a `:feature:*` module — no ViewModel, no nav graph — so it sat outside this
     * rule entirely while it was a placeholder, and would have stayed outside it the moment it grew
     * real text. The home screen is the app's most-read surface and the least likely to be
     * translated by anyone who forgot it existed, which makes it the worst place for the rule to
     * have a hole.
     */
    @Test
    fun `flags a hardcoded string in the widget module`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/CfoWidgetContent.kt",
                    """
                    package com.aicfo.widget

                    fun Content() {
                        Text("Safe to spend")
                    }

                    fun Text(text: String) = text
                    """,
                ).indented(),
            ).issues(HardcodedUiStringDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("strings.xml")
    }

    /** Input: a literal in a non-feature module. Output: asserts the rule is scoped to UI. */
    @Test
    fun `does not flag a string literal outside a feature module`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Label.kt",
                    """
                    package com.aicfo.core.model

                    fun Text(text: String) = text

                    val label = Text("internal diagnostic")
                    """,
                ).indented(),
            ).issues(HardcodedUiStringDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    // --- P-01 · no amounts or PII in logs --------------------------------------------------------

    /** Input: a log line interpolating a balance. Output: asserts one P-01 error. */
    @Test
    fun `flags an amount in a log call`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Repo.kt",
                    """
                    package com.aicfo.data.repository

                    import android.util.Log

                    class Repo {
                        fun save(balanceMinor: Long) {
                            Log.d("Repo", "saved balance=" + balanceMinor)
                        }
                    }
                    """,
                ).indented(),
            ).issues(PiiLoggingDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("P-01")
    }

    /** Input: `println` of an account identifier. Output: asserts stdout is covered too. */
    @Test
    fun `flags PII in a println`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Debug.kt",
                    """
                    package com.aicfo.data.repository

                    fun dump(accountName: String) {
                        println("account " + accountName)
                    }
                    """,
                ).indented(),
            ).issues(PiiLoggingDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
    }

    /** Input: logging an error code and a row count. Output: asserts safe diagnostics still work. */
    @Test
    fun `does not flag logging a code or a count`() {
        lint()
            .files(
                kotlin(
                    "src/main/kotlin/Repo.kt",
                    """
                    package com.aicfo.data.repository

                    import android.util.Log

                    class Repo {
                        fun done(code: String, rows: Int) {
                            Log.d("Repo", "finished code=" + code + " rows=" + rows)
                        }
                    }
                    """,
                ).indented(),
            ).issues(PiiLoggingDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }
}
