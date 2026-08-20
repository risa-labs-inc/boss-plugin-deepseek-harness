package ai.rever.boss.plugin.dynamic.deepseekharness

import java.io.File

/**
 * Whether the harness can run at all.
 *
 * The three failure states are kept distinct rather than folded into one
 * "not ready", because the remedy differs and each is a different sentence to
 * put in front of a user: install Node, install the harness, or nothing (ready).
 */
sealed interface DshInstall {
    /** No Node runtime anywhere we look. The harness cannot be installed either. */
    data object NodeMissing : DshInstall

    /** Node is present but the `dsh` binary is not. Offer to install it. */
    data class DshMissing(val node: File) : DshInstall

    /** Ready to run. [version] is whatever `dsh --version` printed. */
    data class Ready(val dsh: File, val version: String) : DshInstall

    val ready: Boolean get() = this is Ready
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
