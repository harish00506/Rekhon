package com.aicfo.core.database.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which master key this installation should use (issue 11.1; SEC-003, §23).
 *
 * Why:  the decision is three lines and every one of them can destroy a user's database or quietly
 *       weaken its key, so it is a pure function with tests rather than a branch inside an Android
 *       binding nobody can run. The dangerous case is the first one: **an existing alias is never
 *       replaced.** Tink's keyset is encrypted with that key; generating a new one at the same
 *       alias would orphan the keyset, which orphans the wrapped passphrase, which orphans the
 *       database — silently, and on an upgrade rather than a fresh install.
 * What: the four inputs that decide it, and the fallbacks.
 * Result: StrongBox where the platform offers it, the Keystore's ordinary TEE everywhere else, and
 *       never a regenerated key.
 * Changelog: 2026-09-28 — Created for issue 11.1.
 */
class MasterKeyPolicyTest {
    @Test
    fun `an existing key is kept, whatever the device can do now`() {
        // The upgrade case: a phone that gained StrongBox, or a build that started asking for it.
        // Re-minting here would lose every user who already has data.
        MasterKeyPolicy.Capability.entries.forEach { capability ->
            assertEquals(
                "capability $capability",
                MasterKeyDecision.KEEP_EXISTING,
                MasterKeyPolicy.decide(aliasExists = true, capability = capability),
            )
        }
    }

    @Test
    fun `a fresh install uses StrongBox when the device has it`() {
        assertEquals(
            MasterKeyDecision.CREATE_IN_STRONGBOX,
            MasterKeyPolicy.decide(aliasExists = false, capability = MasterKeyPolicy.Capability.STRONGBOX),
        )
    }

    @Test
    fun `a device without StrongBox still gets a Keystore key, not a weaker one`() {
        // The fallback §23 asks to be documented: no StrongBox does not mean no hardware backing,
        // and it certainly does not mean a key outside the Keystore.
        assertEquals(
            MasterKeyDecision.CREATE_IN_KEYSTORE,
            MasterKeyPolicy.decide(aliasExists = false, capability = MasterKeyPolicy.Capability.KEYSTORE_ONLY),
        )
    }

    @Test
    fun `an Android too old for StrongBox falls back rather than failing`() {
        // StrongBox is API 28; this app supports 26. A device that cannot ask must still get a key.
        assertEquals(
            MasterKeyDecision.CREATE_IN_KEYSTORE,
            MasterKeyPolicy.decide(aliasExists = false, capability = MasterKeyPolicy.Capability.TOO_OLD),
        )
    }

    @Test
    fun `the capability is read from the platform, never assumed`() {
        // A device may advertise the feature and still refuse the key at generation time
        // (StrongBoxUnavailableException). `REFUSED` is that answer, recorded after the fact so the
        // second attempt does not ask again and fail again.
        assertEquals(
            MasterKeyDecision.CREATE_IN_KEYSTORE,
            MasterKeyPolicy.decide(aliasExists = false, capability = MasterKeyPolicy.Capability.REFUSED),
        )
    }
}
