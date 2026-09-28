package com.aicfo.core.database.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * Puts the master key in the strongest place this device has (issue 11.1; SEC-003, §23).
 *
 * Why:  §23 asks for StrongBox where it exists. Tink's `AndroidKeysetManager` will happily create
 *       the master key for us, but it has no StrongBox flag — so the key it makes lives in the
 *       ordinary TEE even on a phone with a dedicated security chip. The fix is not to write any
 *       cryptography: it is to ask the **platform's own** `KeyGenerator` for a key at the same
 *       alias first, with StrongBox requested, and let Tink find it already there.
 *
 *       **That is not hand-rolled crypto, and SEC-003 does not forbid it.** Nothing here
 *       implements a cipher, a mode, a padding or a key derivation; the Android Keystore generates
 *       the key and keeps it, exactly as it does when Tink asks. The only difference is one
 *       builder flag that moves the key into better hardware. The note in [KeystoreAeadFactory]
 *       used to read the rule the other way and was corrected with this issue (ADR-0057).
 * What: create-if-absent, StrongBox when the device offers it, TEE when it does not or refuses.
 * Result: on a fresh install with StrongBox, the key that wraps everything else lives in a
 *       separate chip; everywhere else it is TEE-backed as before; and an **existing key is never
 *       touched**, because replacing it would orphan the keyset, the passphrase and the database.
 * Changelog: 2026-09-28 — Created for issue 11.1.
 */
internal object KeystoreMasterKey {
    /** The Keystore provider name. Not a choice — it is the platform's own id. */
    private const val PROVIDER = "AndroidKeyStore"

    /** 256-bit AES-GCM, matching what Tink's own master key would have been. */
    private const val KEY_SIZE_BITS = 256

    /**
     * Ensures a master key exists at [alias], in the best available hardware.
     * Why:    called before Tink builds its keyset manager. Tink uses an existing alias as-is, so
     *         everything this function decides is decided exactly once per installation.
     * What:   reads what the platform can do, asks [MasterKeyPolicy], and generates if asked to.
     * Result: `true` when the key ended up in StrongBox, `false` otherwise — returned for the
     *         record rather than for control flow, since either outcome is a working key.
     * Input:  [context] — any context; [alias] — the Keystore alias.
     * Output: `Boolean` — whether StrongBox backs the key that now exists.
     *
     * Throws only if the Keystore itself is unusable, which the caller turns into
     * `AppError.Crypto`. A StrongBox **refusal** is not a failure: it falls back and says so.
     */
    fun ensure(
        context: Context,
        alias: String,
    ): Boolean {
        val decision = MasterKeyPolicy.decide(aliasExists = exists(alias), capability = capabilityOf(context))
        return when (decision) {
            MasterKeyDecision.KEEP_EXISTING -> false
            MasterKeyDecision.CREATE_IN_KEYSTORE -> generate(alias, strongBox = false)
            // The SDK check is redundant — `Capability.STRONGBOX` already requires 28 — but lint
            // reasons about the call, not about the enum, and it is right to: a reader has to be
            // able to see the version gate from here too.
            MasterKeyDecision.CREATE_IN_STRONGBOX ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    generateInStrongBoxOrFallBack(alias)
                } else {
                    generate(alias, strongBox = false)
                }
        }
    }

    /**
     * Result: whether the Keystore already holds [alias]. Input: [alias]. Output: [Boolean].
     * Why:    the one check that protects every existing installation's data.
     */
    private fun exists(alias: String): Boolean =
        KeyStore.getInstance(PROVIDER).apply { load(null) }.containsAlias(alias)

    /**
     * Result: what this device will do about StrongBox. Input: [context]. Output: a [Capability].
     * Why:    two separate gates — the API level that has the flag at all, and the hardware
     *         feature. A device can pass the first and fail the second.
     */
    private fun capabilityOf(context: Context): MasterKeyPolicy.Capability =
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> MasterKeyPolicy.Capability.TOO_OLD
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE) ->
                MasterKeyPolicy.Capability.STRONGBOX
            else -> MasterKeyPolicy.Capability.KEYSTORE_ONLY
        }

    /**
     * Asks for StrongBox and accepts a refusal.
     * Why:    `FEATURE_STRONGBOX_KEYSTORE` is advertised by devices that then throw
     *         [StrongBoxUnavailableException] for a particular key size or spec. Treating that as
     *         fatal would make the app unable to open its own database on those phones; the right
     *         answer is the TEE key it would have had anyway.
     * Result: `true` if StrongBox took the key, `false` after a fallback.
     * Input:  [alias]. Output: [Boolean].
     *
     * `@RequiresApi(P)` because [StrongBoxUnavailableException] itself is API 28 — a `catch` of a
     * class the platform does not have is a load-time failure, not a dead branch. The only caller
     * reaches here through `Capability.STRONGBOX`, which is unreachable below 28.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun generateInStrongBoxOrFallBack(alias: String): Boolean =
        try {
            generate(alias, strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(alias, strongBox = false)
        }

    /**
     * Generates the key.
     * Why:    the spec mirrors the one Tink would have used — AES-256-GCM, no padding, no user
     *         authentication — because Tink has to be able to use the key it finds. The single
     *         addition is [KeyGenParameterSpec.Builder.setIsStrongBoxBacked].
     * Result: `true` when [strongBox] was requested and the key was created. Input: [alias];
     *         [strongBox]. Output: [Boolean].
     */
    private fun generate(
        alias: String,
        strongBox: Boolean,
    ): Boolean {
        val builder =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(builder.build())
            generateKey()
        }
        return strongBox
    }
}
