package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File

/**
 * Which of the machine's `node` binaries the harness should run on.
 *
 * A machine has more than one Node far more often than not, and `which`-style
 * first-match is the wrong rule when the binary has a *minimum version*. The
 * reporting machine had three — an nvm 18.16.0 first on PATH, a Homebrew 26.7.0,
 * and a 22.23.2 in `~/.local/bin` — and first-match picked the only one that
 * cannot run the harness. The install then failed in a postinstall script, and
 * the first fix for that failure told the user to upgrade a Node they had
 * already upgraded twice.
 *
 * So: probe the candidates and pick one that qualifies. [DshInstall.NodeTooOld]
 * means *none* did, which is the only situation where "upgrade Node" is honest.
 */
object DshNodeResolver {

    /** What the search concluded. */
    sealed interface Resolution {
        /** No `node` anywhere we looked. */
        data object NoNode : Resolution

        /**
         * Run on this one. [version] is null when the probe could not be read —
         * see the fail-open rule in [resolve].
         */
        data class Usable(val node: File, val version: DshNode.Semver?) : Resolution

        /** Every candidate was below the floor. Carries the newest of them. */
        data class AllTooOld(val node: File, val version: DshNode.Semver) : Resolution
    }

    /**
     * Probe [candidates] in order and pick the first that meets
     * [DshNode.MIN_VERSION].
     *
     * Order of preference, and each part of it matters:
     *
     * 1. **First qualifying candidate wins.** Not the newest — the caller orders
     *    candidates by how much the user meant them (their PATH first, then the
     *    conventional install directories), and overriding that to grab a newer
     *    Node from somewhere they did not ask for is not this function's call.
     * 2. **Then any candidate whose version could not be read**, on the same
     *    fail-open rule as [DshNode.parse]: an unreadable version is a reason to
     *    stay out of the way, not to declare the machine unusable. Ranked below
     *    a known-good Node because a version we can vouch for beats one we
     *    cannot.
     * 3. **Then [Resolution.AllTooOld]** carrying the newest, because if the user
     *    is going to be told to upgrade, the number quoted at them should be the
     *    best they have and not whichever happened to be first.
     *
     * [probe] returns raw `node --version` output, or null when the binary would
     * not run. Injected so the ordering can be tested without a machine that
     * happens to have the right mix of Nodes installed on it.
     */
    suspend fun resolve(
        candidates: List<File>,
        probe: suspend (File) -> String?,
    ): Resolution {
        var unreadable: File? = null
        var newestTooOld: Pair<File, DshNode.Semver>? = null

        for (candidate in candidates) {
            val version = probe(candidate)?.let { DshNode.parse(it) }
            when {
                version == null -> if (unreadable == null) unreadable = candidate
                version >= DshNode.MIN_VERSION -> return Resolution.Usable(candidate, version)
                newestTooOld == null || version > newestTooOld.second ->
                    newestTooOld = candidate to version
            }
        }

        unreadable?.let { return Resolution.Usable(it, null) }
        newestTooOld?.let { return Resolution.AllTooOld(it.first, it.second) }
        return Resolution.NoNode
    }
}
