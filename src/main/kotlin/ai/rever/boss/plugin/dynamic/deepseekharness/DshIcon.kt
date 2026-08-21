package ai.rever.boss.plugin.dynamic.deepseekharness

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Canonical simple-icons deepseek.svg path data (CC0), 24x24 viewBox, verbatim.
 *
 * Declared BEFORE its consumer and as a `const`, so [DeepSeekIcon] has no
 * initialization-order dependency on it. That is deliberate: as a `val` declared
 * after [DeepSeekIcon], the file facade initialized them in declaration order and
 * only `by lazy` deferred the read far enough to work. Removing `by lazy` - an
 * entirely reasonable "why is a static icon lazy?" cleanup - then failed with
 * `Variable 'DEEPSEEK_WHALE_PATH' must be initialized`. A compile error rather
 * than a silent NPE, so it could not have shipped, but a trap with no reason to
 * exist. As a `const` declared first there is nothing left to trip over and
 * `by lazy` is a free choice.
 *
 * `internal` rather than `private` so DshIconTest can pin its length and digest.
 * That pin is the assertion that actually delivers the "diffable against
 * upstream" property this file claims: the node-count floor catches gross
 * truncation only, and a single mistyped digit changes zero nodes.
 */
internal const val DEEPSEEK_WHALE_PATH: String =
    "M23.748 4.651c-.254-.124-.364.113-.512.233-.051.04-.094.09-.137.137-.372.397-.806.657-1.373." +
    "626-.829-.046-1.537.214-2.163.848-.133-.782-.575-1.248-1.247-1.548-.352-.155-.708-.311-.955-" +
    ".65-.172-.24-.219-.509-.305-.774-.055-.16-.11-.323-.293-.35-.2-.031-.278.136-.356.276-.313.5" +
    "72-.434 1.202-.422 1.84.027 1.436.633 2.58 1.838 3.393.137.094.172.187.129.323-.082.28-.18.5" +
    "53-.266.833-.055.179-.137.218-.328.14a5.5 5.5 0 0 1-1.737-1.179c-.857-.828-1.631-1.743-2.597" +
    "-2.46a12 12 0 0 0-.689-.47c-.985-.957.13-1.743.387-1.836.27-.098.094-.433-.778-.428-.872.003" +
    "-1.67.295-2.687.685a3 3 0 0 1-.465.136 9.6 9.6 0 0 0-2.883-.101c-1.885.21-3.39 1.1-4.497 2.6" +
    "22C.082 8.776-.231 10.854.152 13.02c.403 2.284 1.568 4.175 3.36 5.653 1.857 1.533 3.997 2.28" +
    "4 6.438 2.14 1.482-.085 3.132-.284 4.994-1.86.47.234.962.328 1.78.398.629.058 1.235-.031 1.7" +
    "05-.129.735-.155.684-.836.418-.961-2.155-1.004-1.682-.595-2.112-.926 1.095-1.295 2.768-3.598" +
    " 3.284-6.733.05-.346.115-.834.108-1.114-.004-.171.035-.238.23-.257a4.2 4.2 0 0 0 1.545-.475c" +
    "1.397-.763 1.96-2.016 2.093-3.517.02-.23-.004-.467-.247-.588M11.58 18.168c-2.088-1.642-3.101" +
    "-2.183-3.52-2.16-.39.024-.32.472-.234.763.09.288.207.487.371.74.114.167.192.416-.113.603-.67" +
    "3.416-1.842-.14-1.897-.168-1.361-.801-2.5-1.86-3.301-3.306-.775-1.393-1.225-2.888-1.299-4.48" +
    "2-.02-.385.094-.522.477-.592a4.7 4.7 0 0 1 1.53-.038c2.131.311 3.946 1.264 5.467 2.774.868.8" +
    "6 1.525 1.887 2.202 2.89.72 1.066 1.494 2.082 2.48 2.915.348.291.626.513.892.677-.802.09-2.1" +
    "4.109-3.055-.615zm1.001-6.44a.306.306 0 0 1 .415-.287.3.3 0 0 1 .113.074.3.3 0 0 1 .086.214c" +
    "0 .17-.136.307-.308.307a.303.303 0 0 1-.306-.307m3.11 1.596c-.2.081-.4.151-.591.16a1.25 1.25" +
    " 0 0 1-.798-.254c-.274-.23-.47-.358-.551-.758a1.7 1.7 0 0 1 .015-.588c.07-.327-.007-.537-.23" +
    "8-.727-.188-.156-.426-.199-.689-.199a.6.6 0 0 1-.254-.078.253.253 0 0 1-.114-.358 1 1 0 0 1 " +
    ".192-.21c.356-.202.767-.136 1.146.016.352.144.618.408 1.001.782.392.451.462.576.685.915.176." +
    "264.336.536.446.848.066.194-.02.353-.25.45"

/**
 * The DeepSeek whale, used for the sidebar panel and the harness tab - one alias
 * so the two can never drift apart.
 *
 * Hand-carried because nothing supplies it: the simple-icons Compose port the
 * host bundles is 1.1.1, which predates DeepSeek and ships no such icon, and the
 * host provides no whale of its own. Note that boss-plugin-docker's icon is also
 * a whale, so the two sit side by side in the sidebar - this is the DeepSeek
 * silhouette, not a second Docker.
 *
 * The path is parsed rather than transcribed into PathBuilder calls on purpose:
 * the outline uses 15 elliptical arcs, and hand-converting ~2,000 characters of
 * arc parameters is a silent-corruption risk with no upside. Keeping the string
 * intact also makes a future logo change a copy-paste, and lets it be diffed
 * against upstream.
 *
 * Fill and viewport match the simple-icons convention exactly - opaque black at
 * 24x24 - because callers tint it. A vector hardcoding DeepSeek blue would ignore
 * the tint and stay one colour in both themes, which is what makes a sidebar
 * item's selected and disabled states read wrong. DshIconTest asserts the fill,
 * not just the path count, because that swap keeps every other assertion green.
 *
 * `by lazy` so the 1,974-character parse happens on first draw rather than at
 * class load. Nothing depends on it any more (see [DEEPSEEK_WHALE_PATH]).
 */
val DeepSeekIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "DeepSeek",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(DEEPSEEK_WHALE_PATH).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
