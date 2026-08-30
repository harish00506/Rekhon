package com.aicfo.backend

import com.aicfo.core.model.PriceKey
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

/**
 * [ServerConfig] — what the environment turns into (issue 6.7; P-04, ADR-0030).
 *
 * Why:  the set of registered sources **is** this service's behaviour, and it is decided entirely by
 *       which environment variables are set. The case that matters most is the absent gold key: it
 *       has to leave `gold:` unpriced rather than crash, register a broken source, or — worst —
 *       reach a vendor with an empty token and get a 401 on every request forever.
 * What: the defaults, the port parsing, the all-or-nothing TLS triple, and which namespaces the
 *       resulting catalogue actually reaches out for.
 * Result: a change that quietly registers or drops a source goes red.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
class ServerConfigTest {
    @Test
    fun `an empty environment is a working cleartext server with no gold`() {
        val config = ServerConfig.fromEnv(emptyMap())

        assertThat(config.port).isEqualTo(ServerConfig.DEFAULT_PORT)
        assertThat(config.tls).isNull()
        assertThat(config.goldApiKey).isNull()
    }

    @Test
    fun `the ports come from the environment`() {
        val config =
            ServerConfig.fromEnv(
                mapOf(
                    ServerConfig.ENV_PORT to "9090",
                    ServerConfig.ENV_TLS_PORT to "9443",
                    ServerConfig.ENV_TLS_KEYSTORE to "dev.p12",
                    ServerConfig.ENV_TLS_ALIAS to "dev",
                    ServerConfig.ENV_TLS_PASSWORD to "secret",
                ),
            )

        assertThat(config.port).isEqualTo(9090)
        assertThat(config.tls?.port).isEqualTo(9443)
        assertThat(config.tls?.keyStore).isEqualTo(File("dev.p12"))
        assertThat(config.tls?.alias).isEqualTo("dev")
    }

    @Test
    fun `a port that is not a number falls back rather than refusing to boot`() {
        val config = ServerConfig.fromEnv(mapOf(ServerConfig.ENV_PORT to "eight thousand"))

        assertThat(config.port).isEqualTo(ServerConfig.DEFAULT_PORT)
    }

    @Test
    fun `TLS is all three of keystore, alias and password or it is none of them`() {
        // A half-set environment starts a working cleartext server. In production this sits behind a
        // TLS terminator that was going to provide the certificate anyway, so refusing to boot over
        // a missing alias would take the service down to protect nothing.
        val partial =
            listOf(
                mapOf(ServerConfig.ENV_TLS_KEYSTORE to "dev.p12"),
                mapOf(ServerConfig.ENV_TLS_KEYSTORE to "dev.p12", ServerConfig.ENV_TLS_ALIAS to "dev"),
                mapOf(ServerConfig.ENV_TLS_ALIAS to "dev", ServerConfig.ENV_TLS_PASSWORD to "secret"),
            )

        partial.forEach { assertThat(ServerConfig.fromEnv(it).tls).isNull() }
    }

    @Test
    fun `a blank value is an absent value`() {
        val config =
            ServerConfig.fromEnv(
                mapOf(
                    ServerConfig.ENV_GOLD_API_KEY to "   ",
                    ServerConfig.ENV_TLS_KEYSTORE to "",
                    ServerConfig.ENV_TLS_ALIAS to "dev",
                    ServerConfig.ENV_TLS_PASSWORD to "secret",
                ),
            )

        assertThat(config.goldApiKey).isNull()
        assertThat(config.tls).isNull()
    }

    @Test
    fun `with no gold key configured, no gold vendor is ever reached`() =
        runTest {
            // The client already handles an unpriced instrument by keeping its cached price and ageing
            // the label (P-04). Reaching a vendor with an empty token instead would earn a 401 on every
            // request for ever, and look identical from the outside.
            val fetch = RecordingFetch()

            val quotes =
                ServerConfig.fromEnv(emptyMap()).catalogue(fetch, TestClock())
                    .quote(setOf(PriceKey("gold:inr.gram.24k")))

            assertThat(quotes).isEmpty()
            assertWithMessage("an unconfigured vendor opens no socket, exactly as the client does")
                .that(fetch.urls).isEmpty()
        }

    @Test
    fun `with a gold key configured, the gold vendor is reached`() =
        runTest {
            val fetch = RecordingFetch()

            ServerConfig.fromEnv(mapOf(ServerConfig.ENV_GOLD_API_KEY to "token"))
                .catalogue(fetch, TestClock())
                .quote(setOf(PriceKey("gold:inr.gram.24k")))

            assertThat(fetch.urls).hasSize(1)
            assertThat(fetch.urls.single()).contains("/api/XAU/INR")
            assertThat(fetch.headers.single()).containsEntry("x-access-token", "token")
        }

    @Test
    fun `the keyless namespaces are registered whatever the environment says`() =
        runTest {
            val fetch = RecordingFetch()

            ServerConfig.fromEnv(emptyMap()).catalogue(fetch, TestClock()).quote(
                setOf(PriceKey("crypto:btc.inr"), PriceKey("mf:inf109k01z48"), PriceKey("fx:usd.inr")),
            )

            assertThat(fetch.urls.joinToString(" ")).contains("simple/price")
            assertThat(fetch.urls.joinToString(" ")).contains("NAVAll.txt")
            assertThat(fetch.urls.joinToString(" ")).contains("/latest")
        }

    /** Records where the catalogue tried to go, and answers nothing. */
    private class RecordingFetch : HttpFetch {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): String? {
            urls += url
            this.headers += headers
            return null
        }
    }
}
