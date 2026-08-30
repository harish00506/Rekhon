package com.aicfo.backend

import io.ktor.server.application.Application
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import java.security.KeyStore
import java.time.Clock
import java.time.ZoneId

/**
 * The §22 market-data proxy's entry point (issue 6.7).
 *
 * Why:  issue 6.5 shipped a client for a service that did not exist, so its first acceptance
 *       criterion could not be demonstrated and the pinned handshake had never run (ADR-0030). This
 *       starts the other half.
 * What: reads the environment, builds the catalogue, serves one route.
 * Result: a listening server. Deliberately three statements long — everything with a decision in it
 *         lives in [ServerConfig] and [PriceCatalogue], which are tested; an entry point that cannot
 *         be unit-tested should therefore contain nothing worth testing.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
fun main() {
    enableIncompleteChainRecovery()
    val config = ServerConfig.fromEnv(System.getenv())
    val catalogue = config.catalogue(OkHttpFetch.create(), marketClock())
    embeddedServer(
        factory = Netty,
        environment = applicationEnvironment(),
        configure = { listenOn(config) },
        module = { marketPrices(catalogue::quote) },
    ).start(wait = true)
}

/**
 * Lets the JVM fetch a certificate chain that a vendor served incompletely.
 *
 * Why:  AMFI's own host, `portal.amfiindia.com`, **serves an incomplete chain** — it omits the
 *       intermediate CA. A browser and `curl` do not notice, because the platform trust store chases
 *       the missing certificate through the Authority Information Access extension. The JDK does not
 *       do that by default, so every NAV fetch died with `PKIX path building failed` and every `mf:`
 *       key came back unpriced, quickly and silently. Nothing in the suite could catch it: the source
 *       tests point at MockWebServer, which is exactly what they should do.
 * What: turns on the JDK's AIA CA-issuer fetching, once, before any TLS handshake happens.
 * Result: the chain completes and AMFI is reachable.
 *
 * **This does not weaken verification, and that distinction is the whole point.** The certificate is
 * still validated to a root in the JDK's trust store; the only change is that the JVM will go and
 * fetch an intermediate the server should have sent. The alternatives were to bundle AMFI's
 * intermediate (which then expires on someone else's schedule) or to trust the host without checking
 * it — and the second is the kind of shortcut SEC-003 exists to forbid.
 *
 * Changelog: 2026-08-30 — Created for issue 6.7, after `mf:` keys returned nothing against the live
 *            host while `curl` fetched the same file fine.
 * Input: none. Output: none.
 */
private fun enableIncompleteChainRecovery() {
    System.setProperty("com.sun.security.enableAIAcaIssuers", "true")
}

/**
 * The clock every source and cache is given.
 *
 * Why:  TIM-001 — calendar logic runs in a real zone, never in whatever the host happens to be set
 *       to. These are Indian market days: a gold price stamped just after midnight UTC belongs to the
 *       next Indian day, and dating it as the previous one would make it read a day stale the moment
 *       it arrived on the device.
 * Result: a system clock in `Asia/Kolkata`. Input: none. Output: [Clock].
 */
internal fun marketClock(): Clock = Clock.system(ZoneId.of("Asia/Kolkata"))

/**
 * Opens the ports [config] describes.
 *
 * Why:    two deployments, two shapes. In production this sits behind a TLS terminator and serves
 *         cleartext to it; on a laptop it must serve TLS **itself**, because the whole point of the
 *         local run is to make the app perform a real pinned handshake against a certificate whose
 *         SPKI we know — the one gap issue 6.5 recorded and could not close.
 * What:   a plain connector always, plus an SSL connector when a keystore is configured.
 * Result: none (mutates the engine configuration).
 * Input:  the receiver — the engine configuration; [config] — the ports and optional keystore.
 * Output: none.
 */
private fun io.ktor.server.engine.ApplicationEngine.Configuration.listenOn(config: ServerConfig) {
    connector { port = config.port }
    val tls = config.tls ?: return
    val password = tls.password.toCharArray()
    val keyStore =
        KeyStore.getInstance("PKCS12").apply {
            tls.keyStore.inputStream().use { load(it, password) }
        }
    sslConnector(
        keyStore = keyStore,
        keyAlias = tls.alias,
        keyStorePassword = { password },
        privateKeyPassword = { password },
    ) {
        port = tls.port
        keyStorePath = tls.keyStore
    }
}

/**
 * The Ktor module, named so `application.conf` or a test can mount it by reference.
 * Input:  the receiver [Application]; [catalogue] — what prices keys. Output: none.
 * Result: the one route is installed.
 */
fun Application.marketDataModule(catalogue: PriceCatalogue) {
    marketPrices(catalogue::quote)
}
