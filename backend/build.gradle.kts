import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64

// :backend — the §22 stateless market-data proxy (issue 6.7; EXT-001, EXT-003, API-001, API-002).
//
// Why:  issue 6.5 shipped the client for a service that did not exist, so its first acceptance
//       criterion could not be demonstrated and the TLS/pinning path had never run (ADR-0030).
//       This module is that service.
// What: a Ktor/Netty server exposing one route — GET /v1/market/prices — over the exact wire shape
//       `:core:network` already parses.
// Result: `./gradlew :backend:run` serves prices; `:backend:devTls` mints the local certificate the
//         app pins against.
//
// It is deliberately OUTSIDE the §21.2 app graph: nothing in the app depends on it and it ships in
// no APK. It depends on :core:model only, so `PriceKey`'s charset (the EXT-003 control) and paise
// (MNY-001) have ONE definition across both sides of the wire.
plugins {
    alias(libs.plugins.cfo.kotlin.library)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.aicfo.backend.MainKt")
}

dependencies {
    // The same PriceKey the client validates with, and the same Money. Written once, so the two
    // sides of the wire cannot drift on what an identifier or a paise value is.
    implementation(project(":core:model"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Upstream vendor fetches. OkHttp is already pinned for :core:network, so the server adds no
    // second HTTP client to the catalogue — and MockWebServer below is the matching test double
    // that comes with it.
    implementation(libs.okhttp)

    // The SLF4J binding. It exists so a failed start is visible; logback.xml is where the
    // no-request-logging property is written down. See src/main/resources/logback.xml.
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
}

// The wire contract is a file both sides read, not a shape each side describes separately. Passing
// its directory as a system property means the test works from any working directory — `:core:network`
// sets the identical property, and the two suites assert against the same bytes.
tasks.withType<Test>().configureEach {
    systemProperty("cfo.contracts.dir", rootProject.file("contracts").absolutePath)
}

// =============================================================================
// devTls — mint the local certificate the app pins against (issue 6.7; §22.1, ADR-0030).
//
// Why:  ADR-0030 recorded that "TLS and certificate pinning are untested, and are the only part of
//       :core:network that is. The gap is the handshake and nothing else." MockWebServer serves
//       cleartext and NetworkConfig refuses a cleartext host, so the two could never meet, and
//       trusting a test certificate would have meant an SSLSocketFactory seam in the one file whose
//       job is to have no seams. That objection still stands, and this closes the gap without
//       touching it: Android's debug-overrides trust anchors apply ONLY to a debuggable build, so
//       nothing here can reach a release APK.
// What: two keypairs, their SPKI pins, the debug trust anchor, and the debug manifest pointing at it.
//       Everything it writes is gitignored, so a fresh clone has no debug overrides at all and builds
//       exactly as it does today.
// Result: `./gradlew :backend:devTls` prints the two pins to install with. TWO, not one: the
//         acceptance criterion asks for a pin set of at least two (current + rotation), and a set
//         whose second member has never been exercised is a rotation plan nobody has tested. With
//         both installed, restarting the server on `dev-next` proves a rotation does not brick an
//         installed copy.
// =============================================================================

/** The dev keypairs, and the `res/raw` resource each certificate is exported to. */
val devAliases = listOf("dev" to "cfo_dev_ca", "dev-next" to "cfo_dev_ca_next")

/**
 * The dev keystore password.
 * Not a secret, and deliberately not treated as one: these keys exist only to serve `10.0.2.2` on a
 * developer's own machine, they are gitignored, and the app trusts them only in a debuggable build.
 */
val devStorePassword = "cfo-dev-only"

/**
 * Runs a command, failing the task with the tool's own output if it fails.
 * Input: [command] — the argv. Output: none.
 */
fun runTool(vararg command: String) {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.first()} failed:\n$output" }
}

/**
 * The OkHttp pin for the certificate under [alias] in [store].
 *
 * Why:    computed in-process rather than by piping through openssl, which is not installed on every
 *         Windows machine and is not needed here: `PublicKey.getEncoded()` already returns exactly
 *         the X.509 SubjectPublicKeyInfo DER that OkHttp hashes.
 * Result: `sha256/<base64>` — the literal string that goes into `NetworkConfig.pins`.
 * Input:  [store] — a PKCS#12 file; [alias] — the key alias. Output: the pin.
 */
fun spkiPin(
    store: File,
    alias: String,
): String {
    val keyStore =
        KeyStore.getInstance("PKCS12").apply {
            store.inputStream().use { load(it, devStorePassword.toCharArray()) }
        }
    val spki = keyStore.getCertificate(alias).publicKey.encoded
    val digest = MessageDigest.getInstance("SHA-256").digest(spki)
    return "sha256/" + Base64.getEncoder().encodeToString(digest)
}

tasks.register("devTls") {
    group = "verification"
    description =
        "Mints the local dev certificates, computes their SPKI pins, and wires the debug trust anchor."

    val localDir = layout.projectDirectory.dir("local").asFile
    val rawDir = rootProject.file("app/src/debug/res/raw")
    val xmlDir = rootProject.file("app/src/debug/res/xml")
    val debugManifest = rootProject.file("app/src/debug/AndroidManifest.xml")
    val keytool = File(File(System.getProperty("java.home"), "bin"), "keytool").absolutePath

    doLast {
        listOf(localDir, rawDir, xmlDir).forEach { it.mkdirs() }

        val pins =
            devAliases.map { (alias, resource) ->
                val store = File(localDir, "$alias.p12")
                if (!store.exists()) {
                    // 10.0.2.2 is how the Android emulator reaches the host loopback; localhost and
                    // 127.0.0.1 cover a device on the same machine and curl. A certificate without the
                    // right SAN fails the hostname check long before pinning is ever consulted.
                    runTool(
                        keytool, "-genkeypair", "-alias", alias,
                        "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA",
                        "-validity", "825",
                        "-dname", "CN=AI Personal CFO dev proxy ($alias),O=local,C=IN",
                        "-ext", "san=ip:10.0.2.2,dns:localhost,ip:127.0.0.1",
                        // A trust anchor has to be a CA, and this certificate is its own.
                        "-ext", "bc:c=ca:true",
                        "-keystore", store.absolutePath, "-storetype", "PKCS12",
                        "-storepass", devStorePassword, "-keypass", devStorePassword,
                    )
                }
                runTool(
                    keytool, "-exportcert", "-rfc", "-alias", alias,
                    "-keystore", store.absolutePath, "-storepass", devStorePassword,
                    "-file", File(rawDir, "$resource.crt").absolutePath,
                )
                alias to spkiPin(store, alias)
            }

        xmlDir.resolve("network_security_config.xml").writeText(devNetworkSecurityConfig)
        debugManifest.writeText(devDebugManifest)
        File(localDir, "pins.txt").writeText(pins.joinToString("\n") { "${it.first}\t${it.second}" } + "\n")

        logger.lifecycle(
            buildString {
                appendLine()
                appendLine("Local TLS is ready. Start the proxy, then install the app pointed at it:")
                appendLine()
                appendLine("  CFO_TLS_KEYSTORE=backend/local/dev.p12 CFO_TLS_ALIAS=dev \\")
                appendLine("  CFO_TLS_PASSWORD=$devStorePassword ./gradlew :backend:run")
                appendLine()
                appendLine("  ./gradlew :app:installDebug \\")
                appendLine("    -Pcfo.market.baseUrl=https://10.0.2.2:8443/ \\")
                appendLine("    -Pcfo.market.pins=" + pins.joinToString(",") { it.second })
                appendLine()
                appendLine("Rotation drill: restart the proxy with CFO_TLS_KEYSTORE=backend/local/dev-next.p12")
                appendLine("and CFO_TLS_ALIAS=dev-next. The app must keep working WITHOUT being rebuilt —")
                appendLine("that is the whole point of shipping two pins.")
            },
        )
    }
}

/** The debug-only trust anchor. `debug-overrides` is ignored entirely in a non-debuggable build. */
val devNetworkSecurityConfig =
    """
    <?xml version="1.0" encoding="utf-8"?>
    <!--
      GENERATED by ./gradlew :backend:devTls (issue 6.7). Gitignored, and debug-only.

      debug-overrides applies ONLY when android:debuggable is true, so this cannot widen what a
      release build trusts. It exists so the app can complete a REAL pinned handshake against the
      local proxy - the one thing issue 6.5 could not test (ADR-0030) - without putting an
      SSLSocketFactory seam into production code, which that ADR rejected and still rejects.
    -->
    <network-security-config>
        <debug-overrides>
            <trust-anchors>
                <certificates src="@raw/cfo_dev_ca" />
                <certificates src="@raw/cfo_dev_ca_next" />
                <certificates src="system" />
            </trust-anchors>
        </debug-overrides>
    </network-security-config>
    """.trimIndent() + "\n"

/** Points the debug application at the trust anchor above. */
val devDebugManifest =
    """
    <?xml version="1.0" encoding="utf-8"?>
    <!-- GENERATED by ./gradlew :backend:devTls (issue 6.7). Gitignored, and debug-only. -->
    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <application android:networkSecurityConfig="@xml/network_security_config" />
    </manifest>
    """.trimIndent() + "\n"
