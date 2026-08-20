package ai.rever.boss.plugin.dynamic.deepseekharness

import java.util.concurrent.TimeUnit

/**
 * Killing a harness process, and everything it started.
 *
 * `dsh web` is a Node process that spawns further children — worker threads for
 * the code runtime, an MCP server per configured entry, whatever a bundle adds.
 * Destroying only the direct child leaves those holding the port and, in the MCP
 * case, an inherited pipe that keeps the whole group alive. This workspace has
 * paid for that mistake before at scale: a plugin host that reaped only its
 * direct children leaked 434 JVMs and 27 GB before anyone noticed, because an
 * orphan's parent becomes pid 1 and stops looking like anybody's problem.
 *
 * So every teardown walks [ProcessHandle.descendants] first, and the ordering is
 * deliberate: collect the descendant list *before* destroying the parent, since
 * a dead parent's descendants are reparented away and can no longer be
 * enumerated from it.
 */
object DshProcesses {

    /** How long a process gets to exit on the polite signal before being killed. */
    private const val GRACE_SECONDS = 6L

    /**
     * Stop [process] and its descendants.
     *
     * `destroy()` is SIGTERM on Unix, which the harness treats as a supervisor's
     * ordinary stop request: it drains the plugin tree for up to five seconds and
     * exits 0. The grace period here is deliberately a little longer than that
     * drain, so a clean shutdown is the normal path and [Process.destroyForcibly]
     * is the exception rather than the routine second step.
     *
     * Safe to call on an already-dead process, and safe to call twice — both the
     * explicit stop path and the shutdown-hook backstop run it.
     */
    fun terminate(process: Process) {
        if (!process.isAlive) return

        // Snapshot before the parent dies, or the list empties out from under us.
        val descendants = runCatching { process.toHandle().descendants().toList() }.getOrDefault(emptyList())

        process.destroy()
        descendants.forEach { handle -> runCatching { handle.destroy() } }

        val exited = runCatching { process.waitFor(GRACE_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!exited) process.destroyForcibly()

        // Anything still up after the parent has gone gets no further grace: it
        // is by definition not draining in response to the signal it was sent.
        descendants.filter { it.isAlive }.forEach { handle -> runCatching { handle.destroyForcibly() } }
    }

    /**
     * Stop a process identified only by pid, for a stale server recorded by an
     * earlier plugin load.
     *
     * [expectedCommandMarker] is a required safety check, not a convenience: pids
     * are recycled, and a recorded pid whose owner has since exited may now name
     * something else entirely. Killing it because a file says so would be this
     * plugin reaching outside its own processes. The handle's command line must
     * still contain the marker or nothing is signalled.
     *
     * @return true when a matching process was found and signalled.
     */
    fun terminateStale(pid: Long, expectedCommandMarker: String): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        if (!handle.isAlive) return false

        val commandLine = handle.info().commandLine().orElse("")
        if (!commandLine.contains(expectedCommandMarker)) return false

        val descendants = runCatching { handle.descendants().toList() }.getOrDefault(emptyList())
        handle.destroy()
        descendants.forEach { child -> runCatching { child.destroy() } }
        runCatching { handle.onExit().get(GRACE_SECONDS, TimeUnit.SECONDS) }
        if (handle.isAlive) handle.destroyForcibly()
        descendants.filter { it.isAlive }.forEach { child -> runCatching { child.destroyForcibly() } }
        return true
    }
}
