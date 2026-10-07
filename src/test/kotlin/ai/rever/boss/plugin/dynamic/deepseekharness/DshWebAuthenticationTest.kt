package ai.rever.boss.plugin.dynamic.deepseekharness

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Exercise readiness against the token-to-cookie redirect introduced in dsh 0.2. */
class DshWebAuthenticationTest {
    @Test
    fun `startup and navigation preserve authentication without exposing it in state`() = runBlocking<Unit> {
        // Other existing process tests also use /bin/sh; npm installation itself
        // is verified separately on Windows by the harness-pin workflow.
        assumeTrue(Files.isExecutable(java.nio.file.Path.of("/bin/sh")))
        val dir = Files.createTempDirectory("dsh-web-auth-test").toFile()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val authenticated = AtomicInteger()
        val rejected = AtomicInteger()
        http.createContext("/") { request ->
            if (request.requestURI.rawQuery == "token=test-session-token") {
                authenticated.incrementAndGet()
                request.responseHeaders.add("Set-Cookie", "dsh=test-session-token; HttpOnly; SameSite=Strict")
                request.responseHeaders.add("Location", "./")
                request.sendResponseHeaders(303, -1)
            } else {
                rejected.incrementAndGet()
                request.sendResponseHeaders(401, -1)
            }
            request.close()
        }
        http.start()
        val port = http.address.port
        val url = "http://127.0.0.1:$port/?token=test-session-token"
        val executable = dir.resolve("dsh")
        executable.writeText("#!/bin/sh\nprintf '%s\\n' 'dsh web: $url'\nsleep 30\n")
        check(executable.setExecutable(true))
        val server = DshWebServer(mapOf("DSH_HOME" to dir.resolve("home").path), startupTimeoutMs = 3_000)
        try {
            val bare = URL("http://127.0.0.1:$port/").openConnection() as java.net.HttpURLConnection
            assertEquals(401, bare.responseCode)
            bare.disconnect()
            rejected.set(0)
            val running = assertIs<DshServer.Running>(server.start(executable, dir, emptyMap(), null))
            assertEquals(url, server.navigationUrl(running))
            assertEquals("http://127.0.0.1:$port", running.url)
            assertFalse(running.toString().contains("test-session-token"))
            assertEquals(1, authenticated.get())
            assertEquals(0, rejected.get(), "readiness must not follow the redirect without its cookie")
            assertNull(server.navigationUrl(running.copy(pid = running.pid + 1)))
            server.stop()
            assertNull(server.navigationUrl(running), "stopping must discard the authenticated URL")
        } finally {
            server.disposeNow()
            http.stop(0)
            dir.deleteRecursively()
        }
    }
}
