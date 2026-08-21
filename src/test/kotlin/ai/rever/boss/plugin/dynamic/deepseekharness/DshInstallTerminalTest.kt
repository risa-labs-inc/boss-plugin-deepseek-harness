package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Where the Install button puts the install.
 *
 * The first implementation called `TerminalTabPluginAPI.setPendingSidebarCommand`,
 * which queues a command for the *sidebar* terminal to pick up whenever it next
 * renders. Nothing opened and nothing ran — while the button's own note said it
 * "runs in a terminal tab". Worse, reaching into another plugin's API
 * implementation is what turned the click into a host-killing `StackOverflowError`
 * when that implementation's delegate turned out to be self-recursive.
 *
 * So these assert the destination, not just that the click survived: a terminal
 * tab, opened through the host's own [SplitViewOperations], carrying the install
 * command. A regression to any queue-it-somewhere approach opens no tab and fails
 * here.
 */
class DshInstallTerminalTest {

    /** Concurrent: [DshPanelViewModel.install] records from its own dispatcher. */
    private fun recorder(): MutableList<Pair<String, List<Any?>>> = CopyOnWriteArrayList()

    private fun servicesWithSplitView(calls: MutableList<Pair<String, List<Any?>>>): DshServices {
        val ops = FakeServices.recording(SplitViewOperations::class.java, calls)
        return DshServices(FakeServices.context(mapOf("getSplitViewOperations" to ops)))
    }

    private fun openedTabs(calls: List<Pair<String, List<Any?>>>) =
        calls.filter { it.first == "openTab" }.map { it.second.first() }

    @Test
    fun `opens a terminal tab in the main panel carrying the install command`() = runTest {
        val calls = recorder()
        val services = servicesWithSplitView(calls)
        val command = services.engine.installCommand()

        assertTrue(services.openInstallTerminal(command), "the tab was not reported as opened")

        val tabs = openedTabs(calls)
        assertEquals(1, tabs.size, "expected exactly one tab, got: $tabs")

        val tab = assertIs<TerminalTabInfo>(tabs.single(), "the install must open a terminal tab")
        assertEquals(command, tab.initialCommand, "the tab was opened without running the install")
        assertEquals(TerminalTabType.typeId, tab.typeId)
        assertEquals(DshServices.INSTALL_TAB_ID, tab.id)
        assertTrue(tab.title.isNotBlank())
    }

    /**
     * `openTab` puts a tab in the active main panel. Nothing in this path may go
     * near the sidebar queue — that is the behaviour being replaced, and it is
     * silent when it is wrong, so assert on the absence rather than trust it.
     */
    @Test
    fun `does not route the install through the sidebar`() = runTest {
        val calls = recorder()
        val services = servicesWithSplitView(calls)

        services.openInstallTerminal(services.engine.installCommand())

        val names = calls.map { it.first }
        assertEquals(listOf("openTab"), names, "the install touched something other than openTab: $names")
    }

    /** The command that actually gets run is a global npm install of the harness. */
    @Test
    fun `the command installs the harness package globally`() = runTest {
        val calls = recorder()
        val services = servicesWithSplitView(calls)

        services.openInstallTerminal(services.engine.installCommand())

        val command = assertIs<TerminalTabInfo>(openedTabs(calls).single()).initialCommand.orEmpty()
        assertTrue(command.startsWith("npm install -g "), "not a global npm install: $command")
        assertTrue(command.contains(DshCli.PACKAGE), "does not install ${DshCli.PACKAGE}: $command")
    }

    /**
     * A host with no split-view operations is the legal null case, and the panel
     * falls back to copying the command out. It must say so rather than claim a
     * terminal opened.
     */
    @Test
    fun `reports failure when the host offers no way to open a tab`() = runTest {
        val services = FakeServices.services()

        assertFalse(services.openInstallTerminal(services.engine.installCommand()))
    }

    /**
     * The button, not just the helper underneath it.
     *
     * Without this the guard has a hole exactly the shape of the original bug:
     * [DshServices.openInstallTerminal] can be perfectly correct and fully covered
     * while [DshPanelViewModel.install] quietly queues the command somewhere else,
     * which is precisely what shipped. So drive the click.
     *
     * Real dispatcher, real coroutine — `install` launches into the view model's
     * own scope — so this polls for the effect instead of assuming it has landed.
     */
    @Test
    fun `clicking Install opens the terminal tab`() {
        val calls = recorder()
        val services = servicesWithSplitView(calls)
        val viewModel = DshPanelViewModel(services)
        try {
            viewModel.install()

            val deadline = System.currentTimeMillis() + CLICK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && openedTabs(calls).isEmpty()) {
                Thread.sleep(POLL_MS)
            }

            val tab = assertIs<TerminalTabInfo>(
                openedTabs(calls).singleOrNull(),
                "clicking Install opened no terminal tab; host calls were ${calls.map { it.first }}",
            )
            assertEquals(services.engine.installCommand(), tab.initialCommand)
        } finally {
            viewModel.dispose()
        }
    }

    private companion object {
        const val CLICK_TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
