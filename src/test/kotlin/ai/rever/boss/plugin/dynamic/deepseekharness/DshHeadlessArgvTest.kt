package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * argv for the one-shot path, including the BOSS MCP bridge overlay.
 *
 * This exists because the overlay silently did not reach this path: the harness's
 * web UI could call every BOSS tool while `dsh_ask` reported having none, since
 * only the server launch was passed `--patch`. Nothing errored - the tools were
 * simply absent, which is the hardest kind of gap to notice.
 */
class DshHeadlessArgvTest {

    private val engine = DshEngine(FakeServices.context())
    private val dsh = File("/opt/homebrew/bin/dsh")
    private val overlay = File("/tmp/boss-mcp.yml")

    @Test
    fun `the overlay is passed when the bridge is on`() {
        val argv = engine.headlessArgv(dsh, "do the thing", overlay)

        assertEquals(
            listOf(dsh.absolutePath, "--profile", "headless", "--patch", overlay.absolutePath, "do the thing"),
            argv,
        )
    }

    @Test
    fun `the overlay is absent when the bridge is off`() {
        val argv = engine.headlessArgv(dsh, "do the thing", null)

        assertEquals(listOf(dsh.absolutePath, "--profile", "headless", "do the thing"), argv)
        assertTrue(argv.none { it == "--patch" })
    }

    @Test
    fun `launcher flags precede the task`() {
        // The harness hands everything after its own flags to the booted app
        // verbatim, so a --patch placed after the task would reach the headless
        // app, which does not know the flag, instead of the launcher.
        val argv = engine.headlessArgv(dsh, "do the thing", overlay)

        assertTrue(argv.indexOf("--patch") < argv.indexOf("do the thing"))
        assertEquals("do the thing", argv.last(), "the task is the app's positional argument")
    }

    @Test
    fun `a task that looks like a flag is still one argument in last position`() {
        // argv is a list, so there is no shell to reinterpret this.
        val argv = engine.headlessArgv(dsh, "--profile web", overlay)

        assertEquals("--profile web", argv.last())
        assertEquals(1, argv.count { it == "--profile" }, "the task must not read as a second --profile")
    }
}
