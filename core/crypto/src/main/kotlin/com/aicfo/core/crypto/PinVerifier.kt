package com.aicfo.core.crypto

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.common.map
import com.google.crypto.tink.Mac
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * The PIN half of the app lock (SEC-002, §23.2).
 *
 * Why:  SEC-002 requires a PIN fallback, because biometrics are not universal — a device may have
 *       no sensor, the user may have enrolled none, and a wet or injured finger fails on the day it
 *       matters. Without a fallback those users are locked out of their own finances.
 * What: set, verify, clear, and "is one set at all".
 * Result: a yes/no answer to "is this the owner?", with every failure answering no.
 * Changelog: 2026-07-26 — Created for issue 2.2 (SEC-002).
 *
 * One public interface, one internal implementation (ARC-003). Nothing here throws across the
 * boundary (§21.6), and nothing here logs (P-01 / `CfoPiiInLogs`).
 */
interface PinVerifier {
    /**
     * Whether a PIN has been set on this device.
     * Why:    decides whether the lock screen offers PIN entry or the setup flow offers to create
     *         one, and whether the app lock can be enabled at all.
     * Result: `Ok(true|false)`, or `Err(Storage)` if the credential cannot be read.
     * Input:  none. Output: `Result<Boolean, AppError>`.
     */
    fun isPinSet(): Result<Boolean, AppError>

    /**
     * Sets or replaces the PIN.
     * Why:    called from onboarding's security step and from settings. Replacing retires the old
     *         PIN immediately — there is no window in which both work.
     * Result: `Ok(Unit)`; `Err(Validation("pin"))` for a malformed PIN, with nothing written;
     *         `Err(Crypto)` if the key is unavailable; `Err(Storage)` if it cannot be persisted.
     * Input:  [pin] — 4 to 6 ASCII digits. Output: `Result<Unit, AppError>`.
     */
    fun setPin(pin: String): Result<Unit, AppError>

    /**
     * Checks a PIN.
     * Why:    the unlock decision.
     * Result: `Ok(true)` only for the PIN that was set. Everything else answers `Ok(false)` —
     *         including a PIN checked when none is set, and a MAC that can no longer verify.
     *         `Err` is reserved for states where no answer is possible at all (unreadable or
     *         truncated credential), which the caller must also treat as "stay locked".
     * Input:  [pin] — what the user typed. Output: `Result<Boolean, AppError>`.
     */
    fun verify(pin: String): Result<Boolean, AppError>

    /**
     * Removes the PIN.
     * Why:    turning the app lock off, and the erase-all path (issue 11.4).
     * Result: `Ok(Unit)` — also when no PIN was set — or `Err(Storage)`.
     * Input:  none. Output: `Result<Unit, AppError>`.
     */
    fun clearPin(): Result<Unit, AppError>
}

/**
 * A [PinVerifier] backed by a Keystore-bound Tink MAC (SEC-002, SEC-003).
 *
 * Why:  **a PIN cannot be protected by hashing it.** Four to six digits is at most a million
 *       candidates; any laptop walks all of them against a stored hash in seconds, however many
 *       rounds it was stretched with. The answer is not a better hash, it is to make the check
 *       impossible to perform off the device: the tag is an HMAC under a key generated **inside**
 *       the Android Keystore, which cannot be exported. An attacker holding the file has no
 *       oracle to test guesses against, so the only route left is guessing on the device itself —
 *       which is exactly what [LockoutPolicy] makes hopeless.
 * What: `salt || tag` where `tag = MAC(salt || pin)`, with a fresh 16-byte salt per set.
 * Result: a credential from which the PIN cannot be recovered or recognised.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * Deliberately free of Android types: the Keystore lives behind Tink's own [Mac] interface, which
 * is what lets every decision here be unit-tested and leaves [KeystoreMacFactory] with no branches
 * in it. The same split issue 1.6 used for the database passphrase.
 *
 * **The salt is not there to slow an attacker down** — the Keystore key already does that. It is
 * there so two devices with the same PIN store different bytes, and so one stored credential says
 * nothing about another.
 *
 * Input:  [store] — where the credential is persisted; [mac] — the Keystore-backed MAC;
 *         [random] — a cryptographic source (never a seeded one; P-08's seedable-randomness rule is
 *         for engines, and a predictable salt would be a weakness).
 * Output: a working PIN verifier.
 */
internal class TinkPinVerifier(
    private val store: PinCredentialStore,
    private val mac: Mac,
    private val random: SecureRandom,
) : PinVerifier {
    override fun isPinSet(): Result<Boolean, AppError> = store.read().map { credential -> credential != null }

    override fun setPin(pin: String): Result<Unit, AppError> {
        if (!isWellFormed(pin)) return Err(AppError.Validation(PIN_FIELD))
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        // Nothing is written until the tag exists, so a Keystore failure cannot leave a
        // half-initialised credential that later reads as corrupt and locks the user out.
        return computeTag(salt, pin).flatMap { tag -> store.write(salt + tag) }
    }

    override fun verify(pin: String): Result<Boolean, AppError> =
        store.read().flatMap { credential ->
            when {
                // No PIN set answers "no", never "yes": a deleted credential file must not turn the
                // lock screen into a doorway that opens on any input.
                credential == null -> Ok(false)
                credential.size <= SALT_BYTES -> Err(AppError.Crypto(TRUNCATED_CREDENTIAL))
                else ->
                    Ok(
                        matches(
                            salt = credential.copyOfRange(0, SALT_BYTES),
                            tag = credential.copyOfRange(SALT_BYTES, credential.size),
                            pin = pin,
                        ),
                    )
            }
        }

    override fun clearPin(): Result<Unit, AppError> = store.clear()

    /**
     * Whether a candidate is shaped like a PIN.
     * Why:    a 1- or 2-digit PIN would make SEC-002's lockout schedule pointless — a hundred
     *         guesses covers it, and the schedule only starts biting at five. The digit test is
     *         written against `'0'..'9'` rather than [Char.isDigit], which also accepts Arabic-Indic
     *         and Devanagari digits: those would be accepted here and then be unenterable on the
     *         numeric keypad the lock screen shows.
     * Result: `true` for 4 to 6 ASCII digits.
     * Input:  [pin] — the candidate. Output: `Boolean`.
     * Changelog: 2026-07-26 — Created for issue 2.2.
     */
    private fun isWellFormed(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it in '0'..'9' }

    /**
     * Tags a salted PIN with the Keystore key.
     * Result: `Ok(tag)` or `Err(Crypto)` — never a thrown [GeneralSecurityException] (§21.6).
     * Input:  [salt], [pin]. Output: `Result<ByteArray, AppError>`.
     * Changelog: 2026-07-26 — Created for issue 2.2.
     */
    private fun computeTag(
        salt: ByteArray,
        pin: String,
    ): Result<ByteArray, AppError> =
        try {
            Ok(mac.computeMac(salt + pin.toByteArray(Charsets.UTF_8)))
        } catch (failure: GeneralSecurityException) {
            Err(AppError.Crypto(failure::class.java.simpleName))
        }

    /**
     * Whether the candidate reproduces the stored tag.
     * Why:    Tink's `verifyMac` signals a mismatch by throwing, and it compares in constant time —
     *         a hand-written `contentEquals` would leak, byte by byte, how much of a guess was
     *         right. SEC-003 is why this goes through Tink rather than a local comparison.
     * Result: `true` only for the PIN that was set. **A [GeneralSecurityException] answers `false`**,
     *         which conflates a wrong PIN with a Keystore that is gone — deliberately, because both
     *         must deny. Reporting "correct" because the crypto broke is the one outcome this class
     *         exists to prevent.
     * Input:  [salt], [tag] — from the stored credential; [pin] — what the user typed.
     * Output: `Boolean`.
     * Changelog: 2026-07-26 — Created for issue 2.2.
     */
    @Suppress("SwallowedException") // Swallowing is the requirement, not an oversight: the detail of
    // *why* verification failed is exactly what must not escape. Reporting "wrong PIN" differently
    // from "key unavailable" hands an attacker an oracle, and P-01 forbids logging it. The one thing
    // the caller needs — that access is denied — is the return value.
    private fun matches(
        salt: ByteArray,
        tag: ByteArray,
        pin: String,
    ): Boolean =
        try {
            mac.verifyMac(tag, salt + pin.toByteArray(Charsets.UTF_8))
            true
        } catch (failure: GeneralSecurityException) {
            false
        }

    internal companion object {
        /** 128 bits — enough that two installations never collide. See the class note on why. */
        const val SALT_BYTES = 16

        /** Four digits is the floor SEC-002's lockout schedule assumes. */
        const val MIN_PIN_LENGTH = 4

        /** Six digits is the ceiling the lock screen's keypad is laid out for. */
        const val MAX_PIN_LENGTH = 6

        /** The field name in `AppError.Validation` — a field name, never a value (P-01). */
        const val PIN_FIELD = "pin"

        /** A fixed label, so the audit log records the condition without recording anything private. */
        const val TRUNCATED_CREDENTIAL = "pin_credential_truncated"
    }
}
