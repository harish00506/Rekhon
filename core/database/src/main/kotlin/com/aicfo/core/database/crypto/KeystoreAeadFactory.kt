package com.aicfo.core.database.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Builds the Keystore-backed AEAD that wraps the database passphrase (SEC-003, §23).
 *
 * Why:  SEC-003 is absolute — Tink or the platform Keystore, never hand-rolled crypto. Tink's
 *       [AndroidKeysetManager] does the part that is easy to get catastrophically wrong: it
 *       creates an AES-256-GCM keyset, encrypts that keyset with a master key generated **inside**
 *       the Android Keystore, and keeps only the encrypted keyset on disk. The master key never
 *       leaves the TEE, so the wrapped passphrase is worthless to anyone holding the file.
 * What: one factory function returning the [Aead] that
 *       [SqlCipherPassphraseManager] wraps and unwraps with.
 * Result: the only Android-specific, untestable-on-JVM piece of the key path — and it has no
 *       branches, which is the point of putting every decision in the manager instead.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 *
 * **On StrongBox (corrected at issue 11.1, ADR-0057):** this note used to say that asking for
 * StrongBox would mean hand-rolled crypto and was therefore forbidden by SEC-003. That was a
 * misreading. Asking the platform's own `KeyGenerator` for a key — which is all
 * [KeystoreMasterKey] does — implements no cryptography at all; it is the same call Tink makes
 * internally, with one builder flag that puts the key in a separate security chip. Tink still has
 * no StrongBox option of its own, so the key is created at the alias **before** Tink looks for it,
 * and Tink uses what it finds. Where StrongBox is absent or refuses, the key is TEE-backed exactly
 * as it was.
 */
object KeystoreAeadFactory {
    /**
     * The same alias as [DatabaseSecrets.MASTER_KEY_ALIAS], in the form Tink's KMS client expects.
     *
     * Issue 11.4 moved the three names this factory used to declare into [DatabaseSecrets], so the
     * erase can destroy them without a second copy of any name existing to drift from this one.
     */
    private const val MASTER_KEY_URI = "android-keystore://${DatabaseSecrets.MASTER_KEY_ALIAS}"

    /**
     * Creates (or loads) the Keystore-backed AEAD.
     * Why:    the keyset is generated on first call and reused afterwards; regenerating it would
     *         orphan the wrapped passphrase and, with it, the database.
     * What:   registers Tink's AEAD primitives and builds the manager against the Keystore master
     *         key.
     * Result: an [Aead] whose key material never leaves the TEE.
     * Input:  [context] — any context; the application context is used internally.
     * Output: the [Aead] for wrapping the database passphrase.
     *
     * Throws only on a platform-level Keystore failure, which the caller converts to
     * `AppError.Crypto` — this factory stays free of policy.
     */
    fun create(context: Context): Aead {
        AeadConfig.register()
        // Issue 11.1: before Tink looks for the master key, make sure the strongest one this
        // device can hold is already there. On an installation that already has a key this is a
        // single `containsAlias` and nothing else — see MasterKeyPolicy for why that matters.
        KeystoreMasterKey.ensure(context.applicationContext, DatabaseSecrets.MASTER_KEY_ALIAS)
        return AndroidKeysetManager
            .Builder()
            .withSharedPref(
                context.applicationContext,
                DatabaseSecrets.KEYSET_NAME,
                DatabaseSecrets.KEYSET_PREF_FILE,
            )
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
}
