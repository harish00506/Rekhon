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
 * **On StrongBox:** the `keystore-crypto` skill asks for StrongBox where available. Tink's Android
 * integration does not expose a StrongBox flag, and selecting it would mean building the
 * `KeyGenParameterSpec` by hand with `javax.crypto` — which SEC-003 forbids outright, and which
 * would be a far worse trade: hand-rolled crypto is the exact failure the rule exists to prevent.
 * The master key is therefore TEE-backed via the standard Keystore provider, StrongBox where the
 * platform chooses it. Revisit if Tink adds a first-class option.
 */
object KeystoreAeadFactory {
    /** Keyset name inside the preferences file; changing it orphans the existing key. */
    private const val KEYSET_NAME = "cfo_db_keyset"

    /** The preferences file holding the *encrypted* keyset. Never holds a usable key. */
    private const val KEYSET_PREF_FILE = "cfo_db_keyset_prefs"

    /** The Keystore alias of the master key that encrypts the keyset. Never exported. */
    private const val MASTER_KEY_URI = "android-keystore://cfo_db_master_key"

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
        return AndroidKeysetManager
            .Builder()
            .withSharedPref(context.applicationContext, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
}
