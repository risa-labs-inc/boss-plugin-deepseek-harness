package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy

class DshPanelComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val services: DshServices,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = DshPanelViewModel(services)

    init {
        lifecycle.doOnDestroy { viewModel.dispose() }
    }

    @Composable
    override fun Content() {
        BossTheme {
            DshPanel(viewModel)
        }
    }
}

@Composable
private fun DshPanel(viewModel: DshPanelViewModel) {
    val install by viewModel.install.collectAsState()
    val server by viewModel.server.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val keySource by viewModel.keySource.collectAsState()
    val bridgeEnabled by viewModel.bridgeEnabled.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val expanded by viewModel.expanded.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        Header(busy, viewModel::refresh)
        Divider(color = BossThemeColors.BorderColor)

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(
                DshPanelViewModel.Section.STATUS,
                expanded,
                viewModel::toggleSection,
            )
            if (DshPanelViewModel.Section.STATUS in expanded) {
                StatusBody(install, keySource, viewModel)
            }

            SectionHeader(
                DshPanelViewModel.Section.SERVER,
                expanded,
                viewModel::toggleSection,
            )
            if (DshPanelViewModel.Section.SERVER in expanded) {
                ServerBody(server, install.ready, viewModel)
            }

            SectionHeader(
                DshPanelViewModel.Section.PROFILES,
                expanded,
                viewModel::toggleSection,
            )
            if (DshPanelViewModel.Section.PROFILES in expanded) {
                ProfilesBody(profiles, viewModel.homePath)
            }

            SectionHeader(
                DshPanelViewModel.Section.BRIDGE,
                expanded,
                viewModel::toggleSection,
            )
            if (DshPanelViewModel.Section.BRIDGE in expanded) {
                BridgeBody(bridgeEnabled, viewModel)
            }
        }
    }
}

@Composable
private fun Header(busy: String?, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "DeepSeek Harness",
            color = BossThemeColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (busy != null) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(8.dp))
            Text(busy, color = BossThemeColors.TextMuted, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.Outlined.Refresh,
            contentDescription = "Refresh",
            tint = BossThemeColors.TextMuted,
            modifier = Modifier.size(14.dp).clickable(onClick = onRefresh),
        )
    }
}

@Composable
private fun SectionHeader(
    section: DshPanelViewModel.Section,
    expanded: Set<DshPanelViewModel.Section>,
    onToggle: (DshPanelViewModel.Section) -> Unit,
) {
    val isOpen = section in expanded
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(section) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isOpen) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = BossThemeColors.TextMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            section.label,
            color = BossThemeColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StatusBody(install: DshInstall, keySource: DshKeySource, viewModel: DshPanelViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        when (install) {
            DshInstall.NodeMissing -> {
                Field("Node", "not found")
                Note(
                    "DeepSeek Harness runs on Node. Install Node 22.19 or newer, then come back " +
                        "and install the harness here.",
                )
            }

            is DshInstall.DshMissing -> {
                Field("Node", install.node.absolutePath)
                Field("Harness", "not installed")
                Spacer(Modifier.height(8.dp))
                BossPrimaryButton(text = "Install DeepSeek Harness", onClick = viewModel::install)
                Note("Installs ${DshCli.PACKAGE} globally. It takes a few minutes and runs in a terminal tab.")
            }

            is DshInstall.Ready -> {
                Field("Harness", install.version)
                Field("API key", keySource.label())
                if (keySource == DshKeySource.NONE) {
                    Note(
                        "Without a key the harness cannot run a turn. Add a DeepSeek provider on " +
                            "the AI Providers settings page, and BOSS will pass the key to the " +
                            "harness for each run without writing it to disk.",
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerBody(server: DshServer, canStart: Boolean, viewModel: DshPanelViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        when (server) {
            is DshServer.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        server.url,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy URL",
                        tint = BossThemeColors.TextMuted,
                        modifier = Modifier.size(13.dp).clickable(onClick = viewModel::copyUrl),
                    )
                }
                Field("Process", "pid ${server.pid}")
                Spacer(Modifier.height(8.dp))
                Row {
                    BossPrimaryButton(
                        text = "Open tab",
                        onClick = viewModel::openTab,
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    )
                    Spacer(Modifier.width(6.dp))
                    BossSecondaryButton(text = "Stop", onClick = viewModel::stopServer, isDestructive = true)
                }
            }

            DshServer.Starting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Starting…", color = BossThemeColors.TextMuted, fontSize = 11.sp)
                }
            }

            DshServer.Stopped -> {
                Text("Not running.", color = BossThemeColors.TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                BossPrimaryButton(text = "Start", onClick = viewModel::startServer, enabled = canStart)
            }

            is DshServer.Failed -> {
                Text(
                    server.reason,
                    color = BossThemeColors.TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                BossPrimaryButton(text = "Try again", onClick = viewModel::startServer, enabled = canStart)
            }
        }
    }
}

@Composable
private fun ProfilesBody(profiles: List<DshProfile>, homePath: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        Field("Home", homePath)
        Spacer(Modifier.height(4.dp))
        if (profiles.isEmpty()) {
            Text("No profiles yet.", color = BossThemeColors.TextMuted, fontSize = 11.sp)
        } else {
            profiles.forEach { profile ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        profile.name,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(76.dp),
                    )
                    Text(
                        if (profile.initialized) {
                            profile.bundles.joinToString(", ").ifBlank { "no bundles" }
                        } else if (profile.shipped) {
                            "created on first use"
                        } else {
                            "not initialized"
                        },
                        color = BossThemeColors.TextMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BridgeBody(enabled: Boolean, viewModel: DshPanelViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Serve BOSS tools to the harness",
                color = BossThemeColors.TextPrimary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = viewModel::setBridgeEnabled,
                colors = SwitchDefaults.colors(checkedThumbColor = BossThemeColors.AccentColor),
            )
        }
        Note(
            "When on, harness agents can call every BOSS tool as " +
                "mcp__${DshMcpBridge.SERVER_NAME}__*. An MCP server is trusted code running " +
                "outside the harness's own agent sandbox, which is why the harness enables none " +
                "by default. Restart the server to apply a change.",
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            color = BossThemeColors.TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.width(64.dp),
        )
        Text(
            value,
            color = BossThemeColors.TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Note(text: String) {
    Box(Modifier.padding(top = 6.dp)) {
        Text(text, color = BossThemeColors.TextMuted, fontSize = 10.sp)
    }
}
