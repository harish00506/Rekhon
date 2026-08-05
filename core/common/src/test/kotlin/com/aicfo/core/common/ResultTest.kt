package com.aicfo.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for [Result] and its combinators — task 1.1.4 T1-T3, T5, T6 (SRS §21.6).
 *
 * Why:  every engine and repository will return this type, so its short-circuit behaviour is
 *       load-bearing: a `map` that ran on an `Err`, or a `flatMap` that lost the first error,
 *       would turn a handled failure into a wrong answer silently. These tests pin both arms of
 *       every combinator rather than only the happy path.
 * What: map / flatMap / mapError / fold / getOrElse / getOrNull / onOk / onErr across `Ok` and
 *       `Err`, plus first-error-wins propagation through a chain.
 * Result: the error model is proven exhaustive and short-circuiting before anything depends on it.
 * Changelog: 2026-07-25 — Created for issue 1.4 (written red before Result.kt existed).
 */
class ResultTest {
    private val boom: AppError = AppError.Storage("IOException")

    // --- T1, T2 · map -----------------------------------------------------------------------

    /** Input: `Ok(2).map { it * 3 }`. Output: asserts the value is transformed (AC1). */
    @Test
    fun `map transforms an Ok value`() {
        assertEquals(Ok(6), Ok(2).map { it * 3 })
    }

    /**
     * Input:  `Err.map { … }` with a transform that would throw if it ran.
     * Output: asserts the error passes through untouched and the transform never executes (AC1).
     */
    @Test
    fun `map leaves an Err untouched and does not run the transform`() {
        var ran = false
        val result: Result<Int, AppError> = Err(boom)
        assertEquals(
            Err(boom),
            result.map {
                ran = true
                it * 3
            },
        )
        assertFalse("map must short-circuit on Err", ran)
    }

    // --- T3, T6 · flatMap -------------------------------------------------------------------

    /** Input: two chained Ok-returning steps. Output: asserts the final value (T3). */
    @Test
    fun `flatMap chains successful steps`() {
        val result = Ok(2).flatMap { Ok(it + 1) }.flatMap { Ok(it * 10) }
        assertEquals(Ok(30), result)
    }

    /**
     * Input:  a chain whose middle step fails, followed by a step that would fail differently.
     * Output: asserts the **first** error wins and later steps never run (T6). Losing this means
     *         a user sees the wrong reason for a failure.
     */
    @Test
    fun `flatMap propagates the first error and stops`() {
        var laterRan = false
        val first = AppError.Validation("amount")
        val result =
            Ok(2)
                .flatMap<Int, AppError, Int> { Err(first) }
                .flatMap {
                    laterRan = true
                    Err(AppError.NotFound)
                }
        assertEquals(Err(first), result)
        assertFalse("flatMap must short-circuit after the first Err", laterRan)
    }

    /** Input: `Err.flatMap`. Output: asserts the error is passed straight through. */
    @Test
    fun `flatMap leaves an Err untouched`() {
        val result: Result<Int, AppError> = Err(boom)
        assertEquals(Err(boom), result.flatMap { Ok(it) })
    }

    // --- mapError -----------------------------------------------------------------------------

    /** Input: an Err. Output: asserts the error can be re-classified at a layer boundary. */
    @Test
    fun `mapError rewrites the error`() {
        val result: Result<Int, AppError> = Err(boom)
        assertEquals(Err(AppError.NotFound), result.mapError { AppError.NotFound })
    }

    /** Input: an Ok. Output: asserts mapError leaves success alone and does not run. */
    @Test
    fun `mapError leaves an Ok untouched`() {
        var ran = false
        assertEquals(
            Ok(7),
            Ok(7).mapError {
                ran = true
                AppError.NotFound
            },
        )
        assertFalse(ran)
    }

    // --- T5 · fold, getOrElse, getOrNull -------------------------------------------------------

    /** Input: both variants. Output: asserts fold picks the matching branch (T5). */
    @Test
    fun `fold picks the branch that matches the variant`() {
        assertEquals("ok:5", Ok(5).fold(onOk = { "ok:$it" }, onErr = { "err:${it.code}" }))
        val failed: Result<Int, AppError> = Err(boom)
        assertEquals("err:storage", failed.fold(onOk = { "ok:$it" }, onErr = { "err:${it.code}" }))
    }

    /** Input: both variants. Output: asserts getOrElse supplies a fallback only on Err (T5). */
    @Test
    fun `getOrElse falls back only on Err`() {
        assertEquals(5, Ok(5).getOrElse { 0 })
        val failed: Result<Int, AppError> = Err(boom)
        assertEquals(-1, failed.getOrElse { -1 })
        // The fallback receives the error, so a caller can vary by cause.
        assertEquals("storage", failed.getOrElse { it.code })
    }

    /** Input: both variants. Output: asserts getOrNull is the null-tolerant escape hatch. */
    @Test
    fun `getOrNull returns the value or null`() {
        assertEquals(5, Ok(5).getOrNull())
        val failed: Result<Int, AppError> = Err(boom)
        assertNull(failed.getOrNull())
    }

    /** Input: both variants. Output: asserts errorOrNull mirrors getOrNull for the error arm. */
    @Test
    fun `errorOrNull returns the error or null`() {
        val failed: Result<Int, AppError> = Err(boom)
        assertSame(boom, failed.errorOrNull())
        assertNull(Ok(5).errorOrNull())
    }

    // --- side-effect helpers -------------------------------------------------------------------

    /** Input: both variants. Output: asserts onOk/onErr fire on exactly one arm each. */
    @Test
    fun `onOk and onErr fire on their own arm only`() {
        val seen = mutableListOf<String>()
        Ok(1).onOk { seen += "ok" }.onErr { seen += "err" }
        val failed: Result<Int, AppError> = Err(boom)
        failed.onOk { seen += "ok2" }.onErr { seen += "err2" }
        assertEquals(listOf("ok", "err2"), seen)
    }

    /** Input: a chain of side effects. Output: asserts they return the receiver for chaining. */
    @Test
    fun `onOk and onErr return the same result`() {
        val result = Ok(1)
        assertSame(result, result.onOk { })
        assertSame(result, result.onErr { })
    }

    // --- AC3 · exhaustiveness --------------------------------------------------------------------

    /**
     * Input:  a `when` over the sealed hierarchy with no `else`.
     * Output: asserts it compiles and both arms are reachable (AC3). If `Result` ever stops being
     *         sealed this test stops compiling, which is the point.
     */
    @Test
    fun `when over Result is exhaustive without an else branch`() {
        fun describe(result: Result<Int, AppError>): String =
            when (result) {
                is Ok -> "ok ${result.value}"
                is Err -> "err ${result.error.code}"
            }
        assertEquals("ok 1", describe(Ok(1)))
        assertEquals("err not_found", describe(Err(AppError.NotFound)))
    }

    /** Input: both variants. Output: asserts the isOk/isErr predicates agree with the type. */
    @Test
    fun `isOk and isErr agree with the variant`() {
        val ok: Result<Int, AppError> = Ok(1)
        val err: Result<Int, AppError> = Err(boom)
        assertTrue(ok.isOk)
        assertFalse(ok.isErr)
        assertTrue(err.isErr)
        assertFalse(err.isOk)
    }

    /** Input: a unit-returning success. Output: asserts the common `Ok(Unit)` shorthand works. */
    @Test
    fun `ok of unit models a successful command`() {
        val saved: Result<Unit, AppError> = Ok(Unit)
        assertEquals(Ok(Unit), saved)
    }
}
