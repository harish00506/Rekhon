package com.aicfo.core.common

/**
 * A typed success-or-failure return — the contract every engine and repository uses.
 *
 * Why:  §21.6 forbids exceptions crossing a layer boundary. An exception is an invisible second
 *       return type: nothing in a signature says a call can fail, so the compiler cannot tell a
 *       caller to handle it, and a missed `catch` becomes a crash on a user's phone. Making
 *       failure part of the return type moves that check to compile time — a `when` over a sealed
 *       result has no `else` to hide in. It also serves §21.6's null-hostility rule: absence is
 *       `Err(AppError.NotFound)`, never a `null` the caller may forget to test.
 * What: two variants, [Ok] and [Err], plus short-circuiting combinators declared below. Both type
 *       parameters are `out`, so an `Ok` is usable wherever any error type is expected and vice
 *       versa — that is what lets `Ok(value)` be written without naming the error type.
 * Result: every fallible call states what it returns and what can go wrong, and the compiler
 *       enforces that both are handled.
 * Changelog: 2026-07-25 — Created for issue 1.4 / task 1.1.4 (§21.6).
 *
 * This shadows `kotlin.Result` inside this package. That is deliberate — the SRS names this type —
 * and [runCatchingToResult] exists so no call site has to reach for the stdlib one.
 *
 * Input:  [T] — the success value's type; [E] — the failure's type, normally [AppError].
 * Output: a value that is exactly one of [Ok] or [Err].
 */
sealed interface Result<out T, out E> {
    /** True when this is an [Ok]. Prefer a `when` where both arms matter. */
    val isOk: Boolean get() = this is Ok

    /** True when this is an [Err]. */
    val isErr: Boolean get() = this is Err
}

/**
 * The success arm.
 * Why:    carries the value a caller asked for; `Ok(Unit)` models a command that simply succeeded.
 * Result: a [Result] that combinators operate on.
 * Input:  [value] — the computed value. Output: a success [Result].
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
data class Ok<out T>(val value: T) : Result<T, Nothing>

/**
 * The failure arm.
 * Why:    carries *why* something failed as data, so the UI can branch on it and a log can record
 *         it, instead of a stack trace nobody reads.
 * Result: a [Result] every combinator short-circuits on.
 * Input:  [error] — the failure, normally an [AppError]. Output: a failure [Result].
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
data class Err<out E>(val error: E) : Result<Nothing, E>

/**
 * Transforms a success value, leaving a failure alone.
 * Why:    the everyday case — convert a DB row to a domain model without unwrapping and rewrapping
 *         at every step, and without accidentally running the conversion on a failure.
 * Result: `Ok(f(value))`, or the original [Err] untouched (the transform never runs).
 * Input:  [transform] — success value → new value. Output: a [Result] of the new type.
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
inline fun <T, E, R> Result<T, E>.map(transform: (T) -> R): Result<R, E> =
    when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

/**
 * Chains another fallible step.
 * Why:    real work is a sequence of things that can each fail — read the account, then the
 *         balance, then the budget. Nesting `when` blocks for that is unreadable; this flattens it
 *         and guarantees the **first** error wins, with no later step running after a failure.
 * Result: the next step's [Result], or the first [Err] encountered.
 * Input:  [transform] — success value → the next [Result]. Output: a [Result] of the new type.
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
inline fun <T, E, R> Result<T, E>.flatMap(transform: (T) -> Result<R, E>): Result<R, E> =
    when (this) {
        is Ok -> transform(value)
        is Err -> this
    }

/**
 * Re-classifies a failure, leaving a success alone.
 * Why:    an error is often more specific one layer down than the layer above should expose — a
 *         repository turns "no row" into [AppError.NotFound] rather than leaking a storage detail.
 * Result: `Err(transform(error))`, or the original [Ok] untouched.
 * Input:  [transform] — error → new error. Output: a [Result] with the new error type.
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
inline fun <T, E, F> Result<T, E>.mapError(transform: (E) -> F): Result<T, F> =
    when (this) {
        is Ok -> this
        is Err -> Err(transform(error))
    }

/**
 * Collapses both arms into one value.
 * Why:    the terminal operation at a UI boundary — turn a result into the state to render,
 *         handling both outcomes in one expression so neither can be forgotten.
 * Result: whichever branch matched this result's variant.
 * Input:  [onOk] — success handler; [onErr] — failure handler. Output: their common return type.
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
inline fun <T, E, R> Result<T, E>.fold(
    onOk: (T) -> R,
    onErr: (E) -> R,
): R =
    when (this) {
        is Ok -> onOk(value)
        is Err -> onErr(error)
    }

/**
 * Unwraps the value, or computes a fallback from the error.
 * Why:    for the places where a sensible default beats propagating — a cached figure, an empty
 *         list. The fallback receives the error so it can differ by cause.
 * Result: the success value, or [fallback] applied to the error.
 * Input:  [fallback] — error → replacement value. Output: a plain value, never a [Result].
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
inline fun <T, E> Result<T, E>.getOrElse(fallback: (E) -> T): T =
    when (this) {
        is Ok -> value
        is Err -> fallback(error)
    }

/**
 * The value, or `null`.
 * Why:    a deliberate, narrow escape hatch for call sites that genuinely do not care why
 *         something failed. Domain code should prefer [fold] or [getOrElse] — §21.6 wants absence
 *         modelled, not nulled.
 * Result: the success value, or `null` for a failure.
 * Input:  none. Output: `T?`.
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
fun <T, E> Result<T, E>.getOrNull(): T? = (this as? Ok)?.value

/**
 * The error, or `null`.
 * Why:    the mirror of [getOrNull], mainly for tests and logging.
 * Result: the error for a failure, or `null` for a success.
 * Input:  none. Output: `E?`.
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
fun <T, E> Result<T, E>.errorOrNull(): E? = (this as? Err)?.error

/**
 * Runs a side effect on success and returns the result unchanged.
 * Why:    lets analytics, logging or cache-warming sit in a chain without breaking it apart.
 * Result: this same [Result], so calls chain.
 * Input:  [action] — what to do with the value. Output: the receiver.
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
inline fun <T, E> Result<T, E>.onOk(action: (T) -> Unit): Result<T, E> {
    if (this is Ok) action(value)
    return this
}

/**
 * Runs a side effect on failure and returns the result unchanged.
 * Why:    the logging seam for errors — record the [AppError.code] without swallowing the failure
 *         or restructuring the call.
 * Result: this same [Result], so calls chain.
 * Input:  [action] — what to do with the error. Output: the receiver.
 * Changelog: 2026-07-25 — Created for issue 1.4.
 */
inline fun <T, E> Result<T, E>.onErr(action: (E) -> Unit): Result<T, E> {
    if (this is Err) action(error)
    return this
}
