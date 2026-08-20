package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext

/**
 * The harness tab: a browser view onto the server this plugin supervises.
 *
 * Embedding the harness's own UI rather than reimplementing it is a deliberate
 * choice. The harness is in developer preview and its `/api` contract is
 * generated and explicitly unstable, so a native chat panel would be a
 * hand-written client against a moving target. The web UI, by contrast, is
 * shipped *with* the version of the harness that is running, so streaming, tool
 * calls, approvals, the session list and the model picker all stay correct for
 * free.
 */
class DshWebTabComponent(
    ctx: ComponentContext,
    override val config: TabInfo,
    private val services: DshServices,
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = DshWebTabType

    @Composable
    override fun Content() {
        BossTheme {
            HarnessTab(services)
        }
    }
}

@Composable
private fun HarnessTab(services: DshServices) {
    val serverState by services.engine.server.state.collectAsState()

    // Starting from the tab means a user who opened it directly does not have to
    // go back to the panel to make it useful.
    LaunchedEffect(Unit) {
        if (serverState is DshServer.Stopped) services.engine.startServer()
    }

    when (val state = serverState) {
        is DshServer.Running -> EmbeddedHarness(state, services)
        DshServer.Starting -> Centered("Starting DeepSeek Harness…", showSpinner = true)
        DshServer.Stopped -> Centered("DeepSeek Harness is not running.")
        is DshServer.Failed -> Centered("DeepSeek Harness could not start.\n\n${state.reason}")
    }
}

@Composable
private fun EmbeddedHarness(state: DshServer.Running, services: DshServices) {
    val browserService: BrowserService? = services.context.browserService

    // No browser engine means no embed. Degrade to a real alternative rather than
    // a blank pane: the harness is serving fine, it just cannot be shown here.
    if (browserService == null || !browserService.isAvailable()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Embedded view unavailable",
                color = BossThemeColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "DeepSeek Harness is serving ${state.url}. Open it in a browser tab instead.",
                color = BossThemeColors.TextMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            BossPrimaryButton(
                text = "Open in browser tab",
                onClick = { services.openUrl(state.url, "DeepSeek Harness") },
                icon = Icons.Outlined.OpenInBrowser,
            )
        }
        return
    }

    // Keyed on the port: a restarted server gets a new port, and a handle still
    // pointed at the old one would show a dead page forever.
    var handle by remember(state.port) { mutableStateOf<BrowserHandle?>(null) }

    LaunchedEffect(state.port) {
        handle?.dispose()
        handle = browserService.createBrowser(BrowserConfig(url = state.url))
    }

    DisposableEffect(state.port) {
        onDispose {
            handle?.dispose()
            handle = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.url,
                color = BossThemeColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { handle?.reload() }) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "Reload",
                    tint = BossThemeColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = { services.openUrl(state.url, "DeepSeek Harness") }) {
                Icon(
                    Icons.Outlined.OpenInBrowser,
                    contentDescription = "Open in browser tab",
                    tint = BossThemeColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Divider(color = BossThemeColors.BorderColor)
        Box(Modifier.fillMaxSize()) {
            val current = handle
            if (current != null && current.isValid) {
                current.Content()
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun Centered(message: String, showSpinner: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(
            message,
            color = BossThemeColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}
