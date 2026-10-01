package com.aicfo.core.common

/**
 * Everything one module keeps that an erase has to destroy (issue 11.4; §23, §34, SEC-003, P-01).
 *
 * Why:  "erase everything" fails in exactly one way that matters — something readable is left
 *       behind — and the likeliest cause is not a bug in the deletion but a **key nobody
 *       remembered**. The app holds three Keystore keys, three Tink keysets, four files and a
 *       directory, created by three different modules, and the module that adds a fourth key will
 *       not be the one editing the eraser.
 *
 *       So the inventory is declared by the module that owns the secret, next to the code that
 *       creates it, and the eraser consumes the declarations without knowing any names. The
 *       factories are then written to *use* these constants, which is the point: there is only one
 *       copy of each name in the codebase, so an alias and its shred cannot drift apart. A drift
 *       test would only detect that; this makes it impossible.
 * What: a bag of names — Keystore aliases, shared-preferences files, paths under `filesDir`, and
 *       database names — carrying no behaviour and no Android imports, so it can live here in pure
 *       Kotlin and be asserted in a plain unit test.
 * Result: the eraser's work list, and a reviewable answer to "is that all of them?".
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * Input:  [keystoreAliases] — AndroidKeyStore aliases to delete; [sharedPrefsNames] — shared-
 *         preferences file names (no `.xml` suffix — the platform adds it), typically the Tink
 *         keysets the aliases wrap; [filePaths] — paths relative to `filesDir`, deleted
 *         recursively so a directory may be named; [databaseNames] — Room/SQLCipher database file
 *         names, whose `-wal`, `-shm` and `-journal` siblings go with them.
 * Output: an immutable value.
 */
data class SecretInventory(
    val keystoreAliases: List<String> = emptyList(),
    val sharedPrefsNames: List<String> = emptyList(),
    val filePaths: List<String> = emptyList(),
    val databaseNames: List<String> = emptyList(),
) {
    /**
     * Merges two inventories.
     * Why:    the eraser is handed one inventory per module and wants a single work list; writing
     *         the concatenation at the call site four times is where a module gets dropped.
     * Result: an inventory holding both sides' names, in order, duplicates kept — deleting a name
     *         twice is harmless and silently discarding one is not the kind of helpfulness this
     *         class should offer.
     * Input:  [other] — the inventory to add. Output: a new [SecretInventory].
     * Changelog: 2026-10-01 — Created for issue 11.4.
     */
    operator fun plus(other: SecretInventory): SecretInventory =
        SecretInventory(
            keystoreAliases = keystoreAliases + other.keystoreAliases,
            sharedPrefsNames = sharedPrefsNames + other.sharedPrefsNames,
            filePaths = filePaths + other.filePaths,
            databaseNames = databaseNames + other.databaseNames,
        )

    /**
     * Whether this inventory asks for nothing.
     * Why:    an eraser handed an empty work list has nothing to destroy, which for this feature is
     *         a defect rather than a no-op — a wiring mistake that would report a successful erase
     *         having touched not one key. The composition is checked against this before it runs.
     * Result: `true` when every list is empty. Input: none. Output: [Boolean].
     * Changelog: 2026-10-01 — Created for issue 11.4.
     */
    fun isEmpty(): Boolean =
        keystoreAliases.isEmpty() &&
            sharedPrefsNames.isEmpty() &&
            filePaths.isEmpty() &&
            databaseNames.isEmpty()
}
