package com.aicfo.backend

import com.aicfo.core.model.PriceKey
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The wire contract, asserted against the same bytes the client's suite reads (issue 6.7; §22.2).
 *
 * Why:  a contract described in two places drifts, and this one is described in two places by
 *       necessity — `:core:network` is an Android library whose DTO is `internal`, and this module is
 *       plain JVM. So neither side owns the shape: `contracts/market-prices-v1.json` does, and both
 *       suites read it. Rename a field here and this goes red; rename it there and
 *       `MarketDataApiTest` goes red. Same idea as `RulebookDriftTest`.
 * What: decodes the golden with a **strict** reader, round-trips it, and asserts the three
 *       properties the client depends on but cannot check for itself.
 * Result: the server cannot silently change what it puts on the wire.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
class MarketPricesContractTest {
    private val golden: String = File(contractsDir(), "market-prices-v1.json").readText()

    @Test
    fun `the contract decodes into this service's wire types, with no field unaccounted for`() {
        // The default Json is strict — ignoreUnknownKeys is false — so a field in the contract that
        // this service does not model fails here rather than going quietly unserved.
        val parsed = Json.decodeFromString<PricesResponse>(golden)

        assertThat(parsed.quotes.map { it.priceKey })
            .containsExactly("crypto:btc.inr", "gold:inr.gram.24k", "mf:inf109k01z48", "fx:usd.inr")
    }

    @Test
    fun `what this service emits for the contract's values is the contract`() {
        val parsed = Json.decodeFromString<PricesResponse>(golden)

        val emitted = Json { encodeDefaults = true }.encodeToString(parsed)

        // Structural, so the contract file stays readable with whitespace. A retyped field still
        // fails: a Double `unit_price_minor` would round-trip as `789012.0`.
        assertThat(Json.parseToJsonElement(emitted)).isEqualTo(Json.parseToJsonElement(golden))
    }

    @Test
    fun `every price in the contract is a positive JSON integer`() {
        // MNY-001, and the single most important property of the whole contract. A decimal here
        // would route the client's parse through a Double and corrupt every derived figure silently.
        val quotes = Json.parseToJsonElement(golden).jsonObject.getValue("quotes").jsonArray

        assertThat(quotes).isNotEmpty()
        quotes.forEach { quote ->
            val price = quote.jsonObject.getValue("unit_price_minor").jsonPrimitive
            assertThat(price.isString).isFalse()
            assertThat(price.content).doesNotContain(".")
            assertThat(price.content.toLong()).isGreaterThan(0L)
        }
    }

    @Test
    fun `every key in the contract is one the device could have sent`() {
        // PriceKey's charset is the EXT-003 control: a request may carry instrument identifiers and
        // nothing else. A contract example that the client's own type would reject is a contract
        // example that could never occur.
        Json.decodeFromString<PricesResponse>(golden).quotes.forEach { PriceKey(it.priceKey) }
    }

    @Test
    fun `every as-of is a date, not a timestamp`() {
        // TIM-002: date-only fields are ISO LocalDate strings. The day the market priced an
        // instrument is not a moment, and storing it as one invites a time-zone bug at every read.
        Json.decodeFromString<PricesResponse>(golden).quotes.forEach {
            assertThat(it.asOfIsoDate).hasLength("yyyy-MM-dd".length)
            LocalDate.parse(it.asOfIsoDate)
        }
    }

    /**
     * Where `contracts/` is.
     * Why:    the tests must find it from any working directory, so `build.gradle.kts` passes the
     *         absolute path rather than the test guessing with `../..`.
     * Output: the directory. Fails loudly if the build script stopped setting it — a contract test
     *         that silently skips is the vacuous gate this project has been bitten by before.
     */
    private fun contractsDir(): File =
        File(
            requireNotNull(System.getProperty("cfo.contracts.dir")) {
                "cfo.contracts.dir is not set — backend/build.gradle.kts must pass it to the test task"
            },
        )
}
