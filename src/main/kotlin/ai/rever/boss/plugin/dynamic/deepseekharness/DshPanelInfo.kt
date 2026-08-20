package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome

object DshPanelInfo : PanelInfo {
    /**
     * The id and order must match the manifest's `panel` block exactly.
     *
     * The host's panel registry keys on the whole [PanelId] data class, so an
     * order here that disagrees with `defaultOrder` in the manifest produces a
     * panel that registers but can never be found by `openPanel` — a silent miss
     * rather than an error.
     */
    override val id = PanelId("deepseek-harness-panel", 58)
    override val displayName = "DeepSeek Harness"
    override val icon = Icons.Outlined.AutoAwesome
    override val defaultSlotPosition = left.bottom
}
