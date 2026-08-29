package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the asset-class taxonomy and its account-type defaults (issue 6.3; §11.2, AI-INV).
 *
 * Why:  two separate things depend on these strings being exactly right, and neither one fails
 *       loudly when they are not. First, `investment_holding.asset_class` is a plain TEXT column
 *       with no CHECK constraint — the identical trap [AccountType]'s doc comment describes, where
 *       a typo becomes a row every later query silently misses. Second, issue 6.4's rulebook rows
 *       already name two of these strings: `RULE-GOLD-CAP.params_json.asset_class` is `"gold"` and
 *       `RULE-CRYPTO-CAP`'s is `"crypto"`. Those rows shipped before this enum existed, so nothing
 *       but this test stops 6.4 discovering a mismatch at the point it tries to cap gold and finds
 *       no holdings in a class it spelled differently.
 * What: every [AssetClass.storedValue], the [AssetClass.fromStored] round trip including the
 *       unknown-value path, and [AssetClass.defaultFor] over all eleven [AccountType]s.
 * Result: the persisted contract and 6.4's fallback mapping are proven, or the build goes red.
 * Changelog: 2026-08-24 — Created for issue 6.3 (written red before AssetClass.kt existed).
 */
class AssetClassTest {
    /** Input: none. Output: asserts each constant carries the exact string the column stores. */
    @Test
    fun `every class stores the string the schema and the rulebook expect`() {
        assertEquals("equity", AssetClass.EQUITY.storedValue)
        assertEquals("debt", AssetClass.DEBT.storedValue)
        assertEquals("gold", AssetClass.GOLD.storedValue)
        assertEquals("crypto", AssetClass.CRYPTO.storedValue)
        assertEquals("cash", AssetClass.CASH.storedValue)
        assertEquals("real_estate", AssetClass.REAL_ESTATE.storedValue)
        assertEquals("other", AssetClass.OTHER.storedValue)
    }

    /**
     * Input: the two strings issue 6.4's rulebook rows already hardcode.
     * Output: asserts this enum spells them identically.
     *
     * This is the whole reason the test exists: `ai/rules/rules-kb.json` shipped `RULE-GOLD-CAP`
     * and `RULE-CRYPTO-CAP` with these literals before any Kotlin consumed them. Renaming a
     * constant here without editing the rulebook would leave 6.4 capping a class that matches no
     * holding, and every diversification figure silently wrong rather than absent.
     */
    @Test
    fun `the gold and crypto strings still match the rulebook rows that name them`() {
        assertEquals("gold", AssetClass.GOLD.storedValue)
        assertEquals("crypto", AssetClass.CRYPTO.storedValue)
    }

    /** Input: every constant's own stored value. Output: asserts the round trip is total. */
    @Test
    fun `every stored value parses back to the class that wrote it`() {
        for (assetClass in AssetClass.entries) {
            assertEquals(assetClass, AssetClass.fromStored(assetClass.storedValue))
        }
    }

    /**
     * Input: values no build writes.
     * Output: asserts `null` rather than a throw — the forward-compatibility contract
     * [AccountType.fromStored] already keeps, so a row written by a newer build is skipped instead
     * of crashing the list that reads it.
     */
    @Test
    fun `an unknown stored value is null rather than a crash`() {
        assertNull(AssetClass.fromStored("commodities"))
        assertNull(AssetClass.fromStored(""))
        assertNull(AssetClass.fromStored("EQUITY"))
    }

    /** Input: the asset-holding account types. Output: asserts each proposes the obvious class. */
    @Test
    fun `an investable account type defaults to its own class`() {
        assertEquals(AssetClass.EQUITY, AssetClass.defaultFor(AccountType.INVESTMENT))
        assertEquals(AssetClass.GOLD, AssetClass.defaultFor(AccountType.GOLD))
        assertEquals(AssetClass.CRYPTO, AssetClass.defaultFor(AccountType.CRYPTO))
    }

    /**
     * Input: the account types that hold value without being lot-tracked.
     * Output: asserts the class 6.4 will put them in when it builds the portfolio denominator.
     */
    @Test
    fun `a cash-like or owned-asset account still has a class`() {
        assertEquals(AssetClass.CASH, AssetClass.defaultFor(AccountType.BANK))
        assertEquals(AssetClass.CASH, AssetClass.defaultFor(AccountType.CASH))
        assertEquals(AssetClass.REAL_ESTATE, AssetClass.defaultFor(AccountType.PROPERTY))
        assertEquals(AssetClass.OTHER, AssetClass.defaultFor(AccountType.VEHICLE))
        assertEquals(AssetClass.OTHER, AssetClass.defaultFor(AccountType.RECEIVABLE))
    }

    /**
     * Input: the three liability types.
     * Output: asserts `null` — a debt is not an asset class, and 6.4 must exclude it from the
     * allocation denominator rather than filing it under `OTHER`, which would understate every
     * other class's share.
     */
    @Test
    fun `a liability has no asset class`() {
        assertNull(AssetClass.defaultFor(AccountType.CREDIT_CARD))
        assertNull(AssetClass.defaultFor(AccountType.LOAN))
        assertNull(AssetClass.defaultFor(AccountType.PAYABLE))
    }

    /**
     * Input: all eleven account types.
     * Output: asserts `defaultFor` answers for every one, and that exactly the liabilities answer
     * `null`. A `when` that grows a new arm silently when [AccountType] gains a member is the
     * failure this guards: the count is asserted, not the arms.
     */
    @Test
    fun `every account type is mapped, and only liabilities map to nothing`() {
        val mapped = AccountType.entries.associateWith { AssetClass.defaultFor(it) }

        assertEquals(11, mapped.size)
        for ((type, assetClass) in mapped) {
            if (type.isLiability) {
                assertNull("$type is a liability and must have no asset class", assetClass)
            } else {
                assertNotNull("$type holds value and must have an asset class", assetClass)
            }
        }
    }
}
