package ai.rever.boss.plugin.dynamic.deepseekharness

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Process spawning: absolute paths, no shell, and honest failure codes.
 *
 * These run real child processes, but only ones every POSIX box has (`/bin/sh`,
 * `/bin/echo`), so they are hermetic without needing the harness installed.
 */
class DshCliTest {

    @Test
    fun `which resolves a binary that exists and returns an absolute path`() {
        val sh = assertNotNull(DshCli.which("sh"), "sh should be resolvable")
        assertTrue(sh.isAbsolute, "resolution must yield an absolute path, got $sh")
        assertTrue(sh.canExecute(), "resolved binary must be executable")
    }

    @Test
    fun `which returns null rather than a bare name for something absent`() {
        // A bare name would "work" until the packaged host launched from Finder
        // with an almost-empty PATH, which is the whole reason resolution exists.
        assertEquals(null, DshCli.which("definitely-not-a-real-binary-xyzzy"))
    }

    @Test
    fun `a task containing shell metacharacters stays one argument`() = runTest {
        // The failure being prevented: task text comes from a model or a text
        // field. Under a shell string this would run `echo hi` and then `touch`.
        val hostile = "hi; touch /tmp/dsh-should-not-exist-\$(date +%s)"
        val result = DshCli.exec(listOf("/bin/echo", hostile))

        assertTrue(result.ok, "echo should succeed: ${result.message}")
        assertEquals(hostile, result.stdout.trim(), "the argument must arrive verbatim, unexpanded")
        assertFalse(result.stdout.contains("\n" + "touch"), "no second command may have run")
    }

    @Test
    fun `a missing binary reports CLI_MISSING rather than a generic failure`() = runTest {
        val result = DshCli.exec(listOf("/nonexistent/path/to/dsh", "--version"))

        assertEquals(DshExec.EXIT_CLI_MISSING, result.exitCode)
        assertTrue(result.missing, "callers branch on `missing` to say 'not installed'")
        assertFalse(result.ok)
    }

    @Test
    fun `a non-zero exit is not confused with a missing binary`() = runTest {
        val result = DshCli.exec(listOf("/bin/sh", "-c", "exit 3"))

        assertEquals(3, result.exitCode)
        assertFalse(result.missing, "an exit code of 3 means the binary ran and said no")
        assertFalse(result.timedOut)
    }

    @Test
    fun `stderr is captured separately from stdout`() = runTest {
        val result = DshCli.exec(listOf("/bin/sh", "-c", "echo out; echo err >&2"))

        assertEquals("out", result.stdout.trim())
        assertEquals("err", result.stderr.trim())
    }

    @Test
    fun `message prefers stderr because that is where diagnostics go`() {
        // Mirrors the harness: the answer is on stdout, the reason it failed is on
        // stderr. Surfacing stdout on failure would show a user an empty string.
        val exec = DshExec(1, stdout = "partial answer", stderr = "MISSING_CREDENTIAL: no key")
        assertEquals("MISSING_CREDENTIAL: no key", exec.message)
    }

    @Test
    fun `message falls back to stdout when stderr is empty`() {
        val exec = DshExec(1, stdout = "something went wrong", stderr = "   ")
        assertEquals("something went wrong", exec.message)
    }

    @Test
    fun `a child that outruns its budget reports TIMEOUT and is killed`() = runTest {
        val result = DshCli.exec(listOf("/bin/sh", "-c", "sleep 30"), timeoutSeconds = 1)

        assertEquals(DshExec.EXIT_TIMEOUT, result.exitCode)
        assertTrue(result.timedOut)
        assertFalse(result.missing, "a timeout is not an absent binary")
    }

    @Test
    fun `output larger than a pipe buffer does not deadlock`() = runTest {
        // Draining stdout and stderr in sequence deadlocks as soon as the child
        // fills the pipe nobody is reading. The harness is talkative on both.
        val result = DshCli.exec(
            listOf("/bin/sh", "-c", "i=0; while [ \$i -lt 4000 ]; do echo out-\$i; echo err-\$i >&2; i=\$((i+1)); done"),
            timeoutSeconds = 60,
        )

        assertTrue(result.ok, "should complete rather than hang: ${result.exitCode}")
        assertTrue(result.stdout.lineSequence().count() > 3000, "stdout was truncated")
        assertTrue(result.stderr.lineSequence().count() > 3000, "stderr was truncated")
    }

    @Test
    fun `a null extraEnv value removes the variable instead of emptying it`() = runTest {
        // The distinction is load-bearing: the harness treats a set-but-empty
        // DEEPSEEK_API_KEY as the inherited layer having supplied a value, which
        // would shadow a working key the user stored through its own Models page.
        val result = DshCli.exec(
            listOf("/bin/sh", "-c", "if [ -z \"\${DSH_TEST_VAR+set}\" ]; then echo absent; else echo present; fi"),
            extraEnv = mapOf("DSH_TEST_VAR" to null),
        )

        assertEquals("absent", result.stdout.trim())
    }

    @Test
    fun `a non-null extraEnv value reaches the child`() = runTest {
        val result = DshCli.exec(
            listOf("/bin/sh", "-c", "echo \"\$DSH_TEST_VAR\""),
            extraEnv = mapOf("DSH_TEST_VAR" to "hello"),
        )

        assertEquals("hello", result.stdout.trim())
    }

    @Test
    fun `the child PATH contains the widened search directories`() = runTest {
        val result = DshCli.exec(listOf("/bin/sh", "-c", "echo \"\$PATH\""))

        assertTrue(result.ok)
        // /usr/bin is in the fallback list on every platform this ships to, so its
        // presence proves the widened PATH was applied rather than inherited raw.
        assertTrue(
            result.stdout.contains("/usr/bin"),
            "child PATH should include the fallback directories, got: ${result.stdout.trim()}",
        )
    }
}
