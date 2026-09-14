package ai.rever.boss.plugin.dynamic.deepseekharness

import com.sun.net.httpserver.HttpServer
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 0.1.2-rc.1's client-connection exchanges a launch token for a cookie and 303. */
class DshWebAuthTest {
    @Test
    fun `readiness preserves the token and ignores the optional LAN address`() {
        val url = "http://127.0.0.1:3080/?token=synthetic-launch-token"
        val parsed = assertNotNull(DshWebServer.parseBrowserUrl("dsh web: $url (LAN: http://192.168.1.2:3080/?token=synthetic-launch-token)"))
        val running = DshServer.Running(3080, 123, parsed)
        assertEquals(url, running.browserUrl)
        assertEquals("http://127.0.0.1:3080", running.url)
        assertFalse(running.toString().contains("synthetic-launch-token"))
    }

    @Test
    fun `probe accepts token exchange without following the cookieless redirect`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            if (request.requestURI.rawQuery == "token=synthetic-launch-token") {
                request.responseHeaders.add("Location", "/")
                request.responseHeaders.add("Set-Cookie", "dsh-session=synthetic-cookie; HttpOnly; SameSite=Strict")
                request.sendResponseHeaders(303, -1)
            } else {
                request.sendResponseHeaders(401, -1)
            }
            request.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/"
            assertFalse(DshWebServer.probe(url), "The clean origin does not authenticate a fresh client")
            assertTrue(DshWebServer.probe("${url}?token=synthetic-launch-token"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `legacy harness URLs remain usable`() {
        val url = "http://127.0.0.1:62375"
        assertEquals(url, DshWebServer.parseBrowserUrl("dsh web: $url"))
        assertEquals(url, DshServer.Running(62375, 123).browserUrl)
    }

    @Test
    fun `failed startup diagnostics do not expose launch tokens`() {
        val failure = DshWebServer.failureText("Error: could not open http://127.0.0.1:3080/?token=synthetic-launch-token")
        assertFalse(failure.contains("synthetic-launch-token"))
        assertTrue(failure.contains("could not open"))
    }
    @Test
    fun `supervisor authenticates readiness and retains the browser handoff`() = runBlocking {
        val home = Files.createTempDirectory("dsh-auth-test").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            val authorized = request.requestURI.rawQuery == "token=synthetic-launch-token"
            if (authorized) request.responseHeaders.add("Location", "/")
            request.sendResponseHeaders(if (authorized) 303 else 401, -1)
            request.close()
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/?token=synthetic-launch-token"
        val dsh = home.resolve("dsh").apply {
            writeText("#!/bin/sh\necho 'dsh web: $url'\nexec /bin/sleep 30\n")
            setExecutable(true)
        }
        val supervisor = DshWebServer(mapOf("DSH_HOME" to home.absolutePath))
        try {
            val state = withTimeout(5_000) { supervisor.start(dsh, home, emptyMap(), null) }
            assertTrue(state is DshServer.Running)
            assertEquals(url, state.browserUrl)
            assertFalse(state.toString().contains("synthetic-launch-token"))
        } finally {
            supervisor.disposeNow()
            server.stop(0)
            home.deleteRecursively()
        }
    }

    @Test
    fun `browser fallback sends authentication to navigation but never to a failure toast`() {
        val url = "http://127.0.0.1:3080/?token=synthetic-launch-token"
        val navigation = mutableListOf<Pair<String, List<Any?>>>()
        val ops = FakeServices.recording(SplitViewOperations::class.java, navigation)
        val available = DshServices(FakeServices.context(mapOf("getSplitViewOperations" to ops)))
        val notices = mutableListOf<Pair<String, List<Any?>>>()
        val notifications = FakeServices.recording(NotificationProvider::class.java, notices)
        val unavailable = DshServices(FakeServices.context(mapOf("getNotificationProvider" to notifications)))
        try {
            available.openUrl(url, "DeepSeek Harness")
            assertEquals(url, navigation.single { it.first == "openUrlInActivePanel" }.second.first())
            unavailable.openUrl(url, "DeepSeek Harness")
            assertTrue(notices.any { it.first == "showToast" })
            assertFalse(notices.toString().contains("synthetic-launch-token"))
        } finally {
            available.dispose()
            unavailable.dispose()
        }
    }

}
