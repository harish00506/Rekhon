package com.aicfo.core.crypto

import android.content.Context
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.Mac
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.mac.MacConfig
import java.io.File
import java.security.SecureRandom

/**
 * Builds the Keystore-backed MAC the PIN is verified against (SEC-002, SEC-003, §23).
 *
 * Why:  SEC-003 is absolute — Tink or the platform Keystore, never hand-rolled crypto. Tink's
 *       [AndroidKeysetManager] does the part that is easy to get catastrophically wrong: it
 *       creates an HMAC-SHA256 keyset, encrypts that keyset with a master key generated **inside**
 *       the Android Keystore, and keeps only the encrypted keyset on disk. The master key never
 *       leaves the TEE, which is what makes an offline attack on a 4-digit PIN impossible rather
 *       than merely slow (see [TinkPinVerifier]).
 * What: one factory function returning the [Mac] the verifier tags and checks with.
 * Result: the only Android-specific, untestable-on-JVM piece of the PIN path — and it has no
 *       branches, which is the point of putting every decision in the verifier instead.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * **A separate keyset from the database key (issue 1.6), deliberately.** Its own keyset name, its
 * own preferences file and its own master-key alias. Sharing 1.6's alias would tie the PIN to the
 * database passphrase, so rotating or destroying one would silently take the other with it — and
 * erase-all (SEC-006, issue 11.4) destroys the database key on purpose.
 *
 * **On StrongBox:** as in issue 1.6's `KeystoreAeadFactory`, Tink's Android integration exposes no
 * StrongBox flag, and selecting one would mean hand-building a `KeyGenParameterSpec` with
 * `javax.crypto` — which SEC-003 forbids. The master key is TEE-backed via the standard Keystore
 * provider, StrongBox where the platform chooses it. Revisit if Tink adds a first-class option.
 */
object KeystoreMacFactory {
    /** Keyset name inside the preferences file; changing it orphans the existing key. */
    private const val KEYSET_NAME = "cfo_pin_keyset"

    /** The preferences file holding the *encrypted* keyset. Never holds a usable key. */
    private const val KEYSET_PREF_FILE = "cfo_pin_keyset_prefs"

    /** The Keystore alias of the master key that encrypts the keyset. Never exported. */
    private const val MASTER_KEY_URI = "android-keystore://cfo_pin_master_key"

    /** Where the salt-and-tag credential lives, inside app-private storage. */
    private const val CREDENTIAL_FILE = "cfo-pin.bin"

    /**
     * Creates (or loads) the Keystore-backed MAC.
     * Why:    the keyset is generated on first call and reused afterwards; regenerating it would
     *         orphan the stored credential and lock the user out of their own PIN.
     * What:   registers Tink's MAC primitives and builds the manager against the Keystore master
     *         key.
     * Result: a [Mac] whose key material never leaves the TEE.
     * Input:  [context] — any context; the application context is used internally.
     * Output: the [Mac] for tagging and verifying the PIN.
     *
     * Throws only on a platform-level Keystore failure, which [TinkPinVerifier] converts to
     * `AppError.Crypto` — this factory stays free of policy.
     */
    fun createMac(context: Context): Mac {
        MacConfig.register()
        return AndroidKeysetManager
            .Builder()
            .withSharedPref(context.applicationContext, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("HMAC_SHA256_256BITTAG"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Mac::class.java)
    }

    /**
     * Assembles the production [PinVerifier].
     * Why:    the wiring — which store, which MAC, which random source — is a decision, and making
     *         it once here means no caller can accidentally build a verifier over a seeded random
     *         or the wrong file. ARC-003: the implementation stays internal; this is how the DI
     *         graph gets one, exactly as `CfoDatabaseFactory` and `CfoDataStoreFactory` do.
     * Result: a [PinVerifier] over app-private storage and the Keystore.
     * Input:  [context] — any context; the application context is used.
     * Output: [PinVerifier].
     * Changelog: 2026-07-26 — Created for issue 2.2.
     */
    fun createVerifier(context: Context): PinVerifier {
        val application = context.applicationContext
        return TinkPinVerifier(
            store = FilePinCredentialStore(File(application.filesDir, CREDENTIAL_FILE)),
            mac = createMac(application),
            random = SecureRandom(),
        )
    }
}
