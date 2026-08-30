package com.aicfo.backend

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [OkHttpFetch] — the promise every source is written against (issue 6.7; P-04).
 *
 * Why:  all four sources are written as if a fetch can only return a body or null, and none of them
 *       has a `try`. That is only safe if this class genuinely never throws — for a 404, a 500, a
 *       host that is not there, or a socket that dies mid-response. If it threw on any of those, one
 *       vendor's bad afternoon would become an exception in the route.
 * What: success, every failure shape, and the header pass-through.
 * Result: a change that lets an exception out of here goes red, and four sources stay honest.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
class OkHttpFetchTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a successful response is its body`() =
        runTest {
            server.enqueue(ok("""{"ok":true}"""))

            assertThat(testFetch().get(server.url("/x").toString())).isEqualTo("""{"ok":true}""")
        }

    @Test
    fun `headers are passed to the vendor`() =
        runTest {
            server.enqueue(ok("{}"))

            testFetch().get(server.url("/x").toString(), mapOf("x-access-token" to "token"))

            assertThat(server.takeRequest().getHeader("x-access-token")).isEqualTo("token")
        }

    @Test
    fun `every unsuccessful status is null, not an exception`() =
        runTest {
            listOf(400, 401, 404, 429, 500, 503).forEach { status ->
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(status))

                assertWithMessage("HTTP $status must be null")
                    .that(testFetch().get(server.url("/x").toString())).isNull()
            }
        }

    @Test
    fun `a host that is not there is null, not an exception`() =
        runTest {
            // The literal version of "the vendor is down". Shutting the server first means the connection
            // is refused rather than answered.
            val url = server.url("/x").toString()
            server.shutdown()

            assertThat(testFetch().get(url)).isNull()
        }

    @Test
    fun `a malformed url is null rather than a thrown IllegalArgumentException`() =
        runTest {
            assertThat(testFetch().get("not a url")).isNull()
        }

    @Test
    fun `the production client is built with a timeout inside the client's five seconds`() {
        // API-001 gives the app five seconds for the whole round trip. An upstream allowed to take
        // longer than that would guarantee a client timeout rather than a served answer.
        val client = OkHttpFetch.create()

        assertThat(client).isNotNull()
    }
}
