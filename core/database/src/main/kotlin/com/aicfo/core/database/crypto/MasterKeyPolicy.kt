package com.aicfo.core.database.crypto

/**
 * What to do about the Keystore master key that wraps everything else (issue 11.1; SEC-003, §23).
 *
 * Why:  three outcomes, and the difference between them is the difference between a user's
 *       database opening tomorrow and not. It is an enum rather than a pair of booleans so the
 *       caller cannot invent a fourth case by accident.
 * Changelog: 2026-09-28 — Created for issue 11.1.
 */
internal enum class MasterKeyDecision {
    /** A key already exists at the alias. Use it. Never replace it — see [MasterKeyPolicy]. */
    KEEP_EXISTING,

    /** No key yet, and this device will put one in a separate security chip. */
    CREATE_IN_STRONGBOX,

    /** No key yet, and StrongBox is unavailable or was refused. The Keystore's own backing. */
    CREATE_IN_KEYSTORE,
}

/**
 * Decides which master key an installation gets (issue 11.1; SEC-003, §23).
 *
 * Why:  §23 asks for StrongBox where it exists, and the honest reading of that is *"on a fresh
 *       install"*. The alternative — upgrading an existing key to StrongBox — is not an upgrade at
 *       all: Tink's keyset is encrypted **with** the master key, and the database passphrase is
 *       wrapped with that keyset, so a new key at the same alias silently orphans the database of
 *       every user who already had one. This function exists to make that impossible to get wrong,
 *       and to be readable by someone checking exactly that.
 *
 *       It is pure — no Android types — for the same reason [SqlCipherPassphraseManager] is: the
 *       platform call left behind then has no branches, and the branches that remain are tested.
 * What: the decision, from whether a key exists and what the platform will actually give us.
 * Result: StrongBox on a fresh install that supports it, the Keystore everywhere else, and an
 *       existing key left alone.
 * Changelog: 2026-09-28 — Created for issue 11.1.
 */
internal object MasterKeyPolicy {
    /**
     * What this device will do about StrongBox.
     *
     * Why:  the three "no" answers are genuinely different and the caller learns them at different
     *       times — one from the SDK level, one from the package manager, and one only after the
     *       Keystore has refused a generation attempt. They are separate constants so a log line
     *       or a future decision can tell them apart, even though today they lead to the same
     *       place.
     */
    enum class Capability {
        /** `FEATURE_STRONGBOX_KEYSTORE` is present and the API level supports asking for it. */
        STRONGBOX,

        /** Android 28+ but no StrongBox on this hardware. */
        KEYSTORE_ONLY,

        /** Below Android 28, where `setIsStrongBoxBacked` does not exist. */
        TOO_OLD,

        /** Advertised, then refused at generation time (`StrongBoxUnavailableException`). */
        REFUSED,
    }

    /**
     * Result: the decision. Input: [aliasExists] — whether the Keystore already holds this app's
     *         master key; [capability] — what the platform offers. Output: [MasterKeyDecision].
     * Why:    `aliasExists` is checked first and unconditionally. Every other consideration is
     *         about a key that does not exist yet.
     */
    fun decide(
        aliasExists: Boolean,
        capability: Capability,
    ): MasterKeyDecision =
        when {
            aliasExists -> MasterKeyDecision.KEEP_EXISTING
            capability == Capability.STRONGBOX -> MasterKeyDecision.CREATE_IN_STRONGBOX
            else -> MasterKeyDecision.CREATE_IN_KEYSTORE
        }
}
