package com.aicfo.core.common

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Behaviour tests for [AppError] and the boundary helper — task 1.1.4 T4 / AC2, AC4 (SRS §21.6).
 *
 * Why:  this is the seam where a thrown exception becomes a typed failure. Two things can go wrong
 *       and both are expensive: an exception escaping a repository (§21.6 forbids it), or the
 *       opposite — swallowing something that must not be swallowed, like a `CancellationException`,
 *       which silently breaks structured concurrency (ARC-006). Privacy rides on this too: an
 *       exception message can carry a file path, a URL with a token or row data, so the app's
 *       error text must be built from class names, never from `Throwable.message` (P-01).
 * What: the exception→error mapping, what is deliberately *not* caught, and the shape of every
 *       variant's code and user-safe message.
 * Result: the boundary is proven both leak-proof and PII-free.
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
class AppErrorTest {
    // --- T4 / AC2 · the boundary converts, it does not leak -------------------------------

    /**
     * Input:  a block throwing [IOException] — the DB/file edge's typical failure.
     * Output: asserts an `Err(AppError.Storage)` comes back and nothing propagates (AC2).
     */
    @Test
    fun `runCatchingToResult converts an IOException into a storage error`() {
        val result = runCatchingToResult { throw IOException("/data/user/0/db/cfo.db is locked") }
        assertEquals(Err(AppError.Storage("IOException")), result)
    }

    /** Input: a successful block. Output: asserts the value is wrapped in Ok. */
    @Test
    fun `runCatchingToResult wraps a successful block`() {
        assertEquals(Ok(42), runCatchingToResult { 42 })
    }

    /** Input: a crypto failure. Output: asserts it maps to the Crypto variant, not Unexpected. */
    @Test
    fun `runCatchingToResult maps a security exception to a crypto error`() {
        val result = runCatchingToResult { throw GeneralSecurityException("bad key") }
        assertEquals(Err(AppError.Crypto("GeneralSecurityException")), result)
    }

    /** Input: an unclassified exception. Output: asserts it falls through to Unexpected. */
    @Test
    fun `runCatchingToResult maps an unknown exception to unexpected`() {
        val result = runCatchingToResult { throw NoSuchElementException("row 7") }
        assertEquals(Err(AppError.Unexpected("NoSuchElementException")), result)
    }

    // --- what must NOT be caught ------------------------------------------------------------

    /**
     * Input:  a block throwing [CancellationException].
     * Output: asserts it propagates. Catching it would tell a cancelled coroutine that it merely
     *         failed, so it would carry on running — the classic structured-concurrency leak
     *         (ARC-006). This test exists to stop a future refactor "simplifying" the catch.
     */
    @Test
    fun `runCatchingToResult rethrows cancellation`() {
        assertThrows(CancellationException::class.java) {
            runCatchingToResult { throw CancellationException("scope closed") }
        }
    }

    /**
     * Input:  blocks throwing the two programmer-error exceptions.
     * Output: asserts both propagate. §21.6 reserves crashes for programmer errors: a failed
     *         `require`/`check` is a bug, and disguising it as a user-facing "something went
     *         wrong" is how bugs survive to production unnoticed.
     */
    @Test
    fun `runCatchingToResult rethrows programmer errors`() {
        assertThrows(IllegalStateException::class.java) {
            runCatchingToResult { check(false) { "invariant broken" } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runCatchingToResult { require(false) { "bad argument" } }
        }
    }

    /** Input: a JVM Error. Output: asserts fatal conditions are never converted to a Result. */
    @Test
    fun `runCatchingToResult rethrows JVM errors`() {
        assertThrows(StackOverflowError::class.java) {
            runCatchingToResult { throw StackOverflowError() }
        }
    }

    // --- AC4 · the messages are user-safe ------------------------------------------------------

    /**
     * Input:  every [AppError] variant, several built from deliberately sensitive input.
     * Output: asserts no `message` contains a digit, a currency symbol, a path separator or the
     *         caller's data. The diagnostic detail lives in its own field, never in the text the
     *         user or a log line sees (AC4, P-01).
     */
    @Test
    fun `no error message leaks an amount, a path or caller data`() {
        val errors =
            listOf(
                AppError.Validation("amount"),
                AppError.NotFound,
                AppError.Storage("IOException"),
                AppError.Network(retryable = true),
                AppError.Crypto("GeneralSecurityException"),
                AppError.Unexpected("NoSuchElementException"),
            )
        val forbidden = listOf("₹", "/", "\\", "@", "1234", "IOException", "amount")
        errors.forEach { error ->
            assertFalse(
                "${error.code} message must not contain a digit: ${error.message}",
                error.message.any(Char::isDigit),
            )
            forbidden.forEach { needle ->
                assertFalse(
                    "${error.code} message must not contain '$needle': ${error.message}",
                    error.message.contains(needle, ignoreCase = true),
                )
            }
            assertTrue("${error.code} message must not be blank", error.message.isNotBlank())
        }
    }

    /**
     * Input:  an exception carrying a sensitive message.
     * Output: asserts only its class name is retained. This is why AC4 holds by construction
     *         rather than by review — there is no path from `Throwable.message` into an AppError.
     */
    @Test
    fun `mapping an exception keeps its type and discards its message`() {
        val leaky = IOException("failed writing ₹1,23,456.78 for account 9876543210")
        val error = leaky.toAppError()
        assertEquals("IOException", (error as AppError.Storage).cause)
        assertFalse(error.cause.contains("9876543210"))
        assertFalse(error.message.contains("9876543210"))
    }

    // --- codes ---------------------------------------------------------------------------------

    /**
     * Input:  every variant.
     * Output: asserts codes are distinct and stable. The UI maps `code` to a `strings.xml` entry
     *         and logs carry it, so renaming one silently breaks both.
     */
    @Test
    fun `every variant has a distinct stable code`() {
        val codes =
            listOf(
                AppError.Validation("field").code,
                AppError.NotFound.code,
                AppError.Storage("IOException").code,
                AppError.Network(retryable = false).code,
                AppError.Crypto("op").code,
                AppError.Unexpected("cause").code,
            )
        assertEquals(
            listOf("validation", "not_found", "storage", "network", "crypto", "unexpected"),
            codes,
        )
        assertEquals("codes must be unique", codes.size, codes.toSet().size)
    }

    /** Input: both retry flavours. Output: asserts the caller can distinguish a retryable failure. */
    @Test
    fun `network errors say whether retrying is worth it`() {
        assertTrue(AppError.Network(retryable = true).retryable)
        assertFalse(AppError.Network(retryable = false).retryable)
    }

    /** Input: the validation variant. Output: asserts the offending field is available to the UI. */
    @Test
    fun `validation errors name the field for the UI to highlight`() {
        assertEquals("amount", AppError.Validation("amount").field)
    }

    /** Input: two equal errors. Output: asserts value equality, so tests can assert on them. */
    @Test
    fun `errors compare by value`() {
        assertEquals(AppError.Storage("IOException"), AppError.Storage("IOException"))
        assertEquals(AppError.Validation("amount"), AppError.Validation("amount"))
        assertEquals(AppError.Network(retryable = true), AppError.Network(retryable = true))
        assertEquals(AppError.Crypto("unwrap"), AppError.Crypto("unwrap"))
        assertEquals(AppError.Unexpected("Boom"), AppError.Unexpected("Boom"))
        assertEquals(AppError.NotFound, AppError.NotFound)
    }
}
