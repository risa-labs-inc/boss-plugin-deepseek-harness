package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File

/**
 * Whether the harness can run at all.
 *
 * The failure states are kept distinct rather than folded into one "not ready",
 * because the remedy differs and each is a different sentence to put in front of
 * a user: install Node, upgrade Node, install the harness, or nothing (ready).
 */
sealed interface DshInstall {
    /** No Node runtime anywhere we look. The harness cannot be installed either. */
    data object NodeMissing : DshInstall

    /**
     * **Every** Node on this machine predates [DshNode.MIN_VERSION], so the
     * install cannot succeed — offering it anyway is what this state exists to
     * stop. [node] and [version] describe the newest one found, since that is the
     * one the user is most likely to be thinking of.
     *
     * The npm install gets far enough to look like it is working and then dies in
     * a postinstall script on `import.meta.resolve is not a function`, under a
     * screenful of EBADENGINE warnings. That is a miserable way to learn your Node
     * is four majors behind, so say it up front, with the version we actually
     * found.
     *
     * "Every" is load-bearing and was the bug in the first version of this state.
     * It judged whichever `node` came first on PATH, which on the reporting
     * machine was an nvm 18.16.0 shadowing a Homebrew 26.7.0 — so it told someone
     * with a perfectly good Node to go upgrade Node.
     */
    data class NodeTooOld(val node: File, val version: String) : DshInstall

    /** Node is present and new enough, but the `dsh` binary is not. Offer to install it. */
    data class DshMissing(val node: File) : DshInstall

    /** Ready to run. [version] is whatever `dsh --version` printed. */
    data class Ready(val dsh: File, val version: String) : DshInstall

    val ready: Boolean get() = this is Ready
}

/**
 * What the harness needs from the Node it runs on.
 *
 * The floor is the harness's own: `@earendil-works/pi-ai` declares
 * `node >=22.19.0`, and the postinstall that breaks first needs
 * `import.meta.resolve`. Kept next to the parser so the number and the comparison
 * cannot drift apart.
 */
object DshNode {
    /** Lowest Node the harness installs and runs on. */
    val MIN_VERSION = Semver(22, 19, 0)

    /** For messages: what to tell someone whose Node is too old. */
    const val MIN_LABEL = "22.19"

    /**
     * Parse `node --version` output — `v18.16.0`, or `v24.0.0-nightly...`.
     *
     * Returns null for anything unrecognised, and every caller treats null as
     * "don't know, don't block". A version string we cannot read is a reason to
     * stay out of the way, not a reason to disable a Node that may be fine: a
     * false NodeTooOld hides a working setup behind a wrong error, which is worse
     * than the npm failure this whole state exists to pre-empt.
     */
    fun parse(raw: String): Semver? {
        val match = VERSION.find(raw.trim()) ?: return null
        val (major, minor, patch) = match.destructured
        return Semver(major.toInt(), minor.toInt(), patch.toIntOrNull() ?: 0)
    }

    // The comparison against MIN_VERSION lives in DshNodeResolver, not here.
    // A gate that judged one binary was the wrong shape: this machine has three
    // `node`s and the answer depends on all of them.

    private val VERSION = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

    data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
        override fun compareTo(other: Semver): Int =
            compareValuesBy(this, other, Semver::major, Semver::minor, Semver::patch)

        override fun toString(): String = "$major.$minor.$patch"
    }
}

/**
 * State of the supervised `dsh web` server.
 *
 * [Failed] carries the harness's own diagnostic rather than a generic message.
 * A bind collision and a missing credential read identically as "didn't start"
 * and differently to a user trying to fix it.
 */
sealed interface DshServer {
    data object Stopped : DshServer
    data object Starting : DshServer
    data class Running(val port: Int, val pid: Long) : DshServer {
        val url: String get() = "http://127.0.0.1:$port"
    }

    data class Failed(val reason: String) : DshServer
}

/**
 * Where the harness's API key is coming from, for display.
 *
 * The value is deliberately absent from this type. The panel needs to say
 * *whether* a key resolved and from where; it never needs the key itself, and a
 * key that is not in a UI model cannot be rendered into a screenshot, a log
 * line, or a crash report by accident.
 */
enum class DshKeySource {
    /** A DeepSeek provider configured on BOSS's AI Providers settings page. */
    BOSS_PROVIDER,

    /** A secret-manager entry tagged for the harness. */
    BOSS_SECRET,

    /** Exported in the environment BOSS itself was launched with. */
    INHERITED_ENV,

    /** Nothing found. The harness will fail any turn with MISSING_CREDENTIAL. */
    NONE,
}

/** A profile directory under `$DSH_HOME/profiles`. */
data class DshProfile(
    val name: String,
    /** Whether the directory exists. Shipped profiles self-initialize, so absent is normal. */
    val initialized: Boolean,
    /** Bundle list read from the profile's own `package.json`, `dsh.profile.bundles`. */
    val bundles: List<String>,
) {
    /** Shipped profiles the harness creates from templates on first use. */
    val shipped: Boolean get() = DshPaths.isShippedProfile(name)
}

/** One entry in `$DSH_HOME/sessions`, described without decoding it. */
data class DshSession(
    val id: String,
    val sizeBytes: Long,
    val modifiedEpochMs: Long,
)
