package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.ui.graphics.vector.ImageVector

object DshWebTabType : TabTypeInfo {
    override val typeId = TabTypeId("deepseek-harness-web", DshServices.PLUGIN_ID)
    override val displayName = "DeepSeek Harness"
    override val icon: ImageVector = Icons.Outlined.AutoAwesome
}

/**
 * The single harness tab.
 *
 * There is deliberately only one, with a fixed id: the harness's own web UI owns
 * its session list and switching between sessions happens inside it, so a second
 * tab would be two browser views onto the same server competing for the same
 * state. A fixed id also means [DshServices.openWebTab] focuses the existing tab
 * rather than stacking duplicates that share one component.
 */
data class DshWebTabInfo(
    override val id: String = TAB_ID,
    override val typeId: TabTypeId = DshWebTabType.typeId,
    override val title: String = "DeepSeek Harness",
    override val icon: ImageVector = Icons.Outlined.AutoAwesome,
) : TabInfo {
    companion object {
        const val TAB_ID = "deepseek-harness-web"
    }
}
