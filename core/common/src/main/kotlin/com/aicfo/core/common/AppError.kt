package com.aicfo.core.common

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Every way an operation in this app is allowed to fail.
 *
 * Why:  §21.6 — failures cross layer boundaries as data, not exceptions, so they have to be a
 *       closed set the compiler can check. A sealed hierarchy means a `when` over an error has no
 *       `else`: add a variant and every handler that needs updating stops compiling.
 * What: a stable [code] plus a user-safe [message], and per-variant diagnostic detail.
 * Result: the UI can branch on the failure, logs can record it, and neither sees anything private.
 * Changelog: 2026-07-25 — Created for issue 1.4 / task 1.1.4 (§21.6, P-01).
 *
 * **[message] is a non-localised fallback, not the string to show.** Localised text lives at the
 * UI edge (task 1.1.4 §4): the UI maps [code] to a `strings.xml` entry, which is what lets this
 * pure-Kotlin module carry error semantics without owning user-visible copy.
 *
 * **Nothing here may carry PII (P-01).** No amount, account name, merchant, path or identifier
 * appears in any [message]; diagnostic fields hold an exception's *class name* only, never its
 * message, because an exception message routinely contains a file path, a URL with a token, or the
 * row that failed. [toAppError] is the only construction path from a [Throwable], and it discards
 * the message — so the guarantee holds by construction rather than by review.
 *
 * Input:  [code] — a stable machine identifier; [message] — the safe fallback text.
 * Output: a value describing one failure.
 */
sealed class AppError(
    val code: String,
    val message: String,
) {
    /**
     * The user gave us something we cannot accept.
     * Why:    the only error the user can fix themselves, so the UI needs to know *which* input.
     * Result: a recoverable error naming the offending field.
     * Input:  [field] — the field's name (a field name, never its value). Output: an [AppError].
     */
    data class Validation(val field: String) : AppError("validation", "Please check what you entered.")

    /**
     * The thing asked for does not exist.
     * Why:    §21.6 is null-hostile — a missing row is this, not a `null` a caller may forget.
     * Result: an error meaning "absent", distinct from "lookup failed".
     */
    data object NotFound : AppError("not_found", "We couldn't find that.")

    /**
     * Reading or writing local data failed.
     * Why:    the database and file edge — a locked DB, a full disk, a corrupt page.
     * Result: an error the UI can offer to retry.
     * Input:  [cause] — the exception's class name only. Output: an [AppError].
     */
    data class Storage(val cause: String) : AppError("storage", "Couldn't reach your saved data.")

    /**
     * A network call failed. Only ever on an opt-in path — core features work offline (P-04).
     * Why:    retryability changes the UI: a timeout invites "try again", a rejection does not.
     * Result: an error carrying whether retrying could help.
     * Input:  [retryable] — true when the same call may later succeed. Output: an [AppError].
     */
    data class Network(val retryable: Boolean) : AppError("network", "No connection right now.")

    /**
     * A cryptographic operation failed — key access, encrypt, decrypt, unwrap.
     * Why:    kept separate from [Storage] because it must never be retried blindly: a failing
     *         unwrap can mean tampering or a rotated key, and both need their own handling (SEC).
     * Result: a security-relevant error, safe to show and safe to audit-log.
     * Input:  [operation] — which operation, as a fixed label. Output: an [AppError].
     */
    data class Crypto(val operation: String) : AppError("crypto", "A security check failed.")

    /**
     * Something we did not classify.
     * Why:    the catch-all that keeps the set closed; a variant appearing often here is a signal
     *         to add a real one.
     * Result: a generic failure carrying the exception's class name for diagnosis.
     * Input:  [cause] — the exception's class name only. Output: an [AppError].
     */
    data class Unexpected(val cause: String) : AppError("unexpected", "Something went wrong.")
}

/**
 * Classifies a caught exception as an [AppError].
 * Why:    one mapping table, so "IOException means storage trouble" is decided once rather than
 *         guessed at each catch site.
 * What:   maps by exception type and keeps **only the class name** — [Throwable.message] is
 *         discarded on purpose (P-01), since it routinely holds paths, URLs or row data.
 * Result: a typed, PII-free error. Unrecognised exceptions become [AppError.Unexpected].
 * Input:  the receiver — the caught exception. Output: the matching [AppError].
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
fun Throwable.toAppError(): AppError {
    val type = this::class.java.simpleName
    return when (this) {
        is IOException -> AppError.Storage(type)
        is GeneralSecurityException -> AppError.Crypto(type)
        else -> AppError.Unexpected(type)
    }
}

/**
 * The one sanctioned place to catch an exception: the DB/network edge.
 * Why:    §21.6 says no exception crosses a layer boundary, so something has to do the converting.
 *         Confining that to a single helper means there is exactly one `catch` in the codebase to
 *         review, and repositories read as ordinary code returning [Result].
 * What:   runs [block], wrapping success in [Ok] and a classified failure in [Err]. Three things
 *         are deliberately **not** caught:
 *         - [CancellationException] — swallowing it tells a cancelled coroutine it merely failed,
 *           so it keeps running. That silently breaks structured concurrency (ARC-006).
 *         - [IllegalStateException] / [IllegalArgumentException] — a failed `check`/`require` is a
 *           programmer error, and §21.6 reserves crashes for exactly those. Disguising a bug as a
 *           user-facing "something went wrong" is how it survives to production unnoticed.
 *         - [Error] — the JVM is already in trouble; a `Result` cannot help.
 * Result: `Ok(value)`, or `Err` of a PII-free [AppError]. Bugs and cancellation still propagate.
 * Input:  [block] — the fallible edge call. Output: `Result<T, AppError>`.
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
@Suppress("TooGenericExceptionCaught") // The point of this function: §21.6 lets nothing unclassified
// escape a boundary, so the last arm must be broad. detekt's rule guards against generic catches
// scattered through the codebase — this is the single one the architecture mandates, and the three
// arms above it narrow exactly what must NOT be swallowed.
inline fun <T> runCatchingToResult(block: () -> T): Result<T, AppError> =
    try {
        Ok(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (bug: IllegalStateException) {
        throw bug
    } catch (bug: IllegalArgumentException) {
        throw bug
    } catch (failure: Exception) {
        Err(failure.toAppError())
    }
