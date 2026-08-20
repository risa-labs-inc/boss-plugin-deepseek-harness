package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.McpToolDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The RBAC guard: a mutating tool must not ship without a permission.
 *
 * This exists because the failure it prevents is invisible. A
 * `readOnly = false` tool with an empty `requiredPermissions` is exposed to
 * every session, including one with nobody signed in — the host's initial RBAC
 * state is "no admin, no permissions", which an empty requirement list
 * trivially satisfies. Nothing errors; the tool simply becomes available to
 * anyone.
 *
 * The one exception is declared in production code
 * ([DshMcpToolProvider.UNGATED_MUTATING_TOOLS]) rather than duplicated here, so
 * the reason for it lives next to the tool and a second copy cannot drift.
 */
class DshMcpToolRbacTest {

    private fun tools(): List<McpToolDefinition> =
        DshMcpToolProvider("test", FakeServices.services()).tools()

    @Test
    fun `every mutating tool is gated, except the documented exceptions`() {
        val offenders = tools()
            .filter { !it.readOnly }
            .filter { it.name !in DshMcpToolProvider.UNGATED_MUTATING_TOOLS }
            .filter { it.requiredPermissions.isEmpty() && !it.requiresAdmin }
            .map { it.name }

        if (offenders.isNotEmpty()) {
            fail(
                "These tools change state but carry no permission: $offenders. Either gate them " +
                    "with dsh.run/dsh.manage, or add them to " +
                    "DshMcpToolProvider.UNGATED_MUTATING_TOOLS with a reason.",
            )
        }
    }

    @Test
    fun `the ask tool is gated on dsh_run because it spends tokens`() {
        val ask = tools().single { it.name == "dsh_ask" }
        assertEquals(listOf(DshMcpToolProvider.PERMISSION_RUN), ask.requiredPermissions)
        assertTrue(!ask.readOnly, "dsh_ask writes files and spends tokens; it is not read-only")
    }

    @Test
    fun `server and bundle mutations are gated on dsh_manage`() {
        val gated = listOf("dsh_web_start", "dsh_web_stop", "dsh_bundle_add", "dsh_bundle_remove")
        val byName = tools().associateBy { it.name }
        gated.forEach { name ->
            val tool = byName[name] ?: fail("$name is missing from the tool list")
            assertEquals(
                listOf(DshMcpToolProvider.PERMISSION_MANAGE),
                tool.requiredPermissions,
                "$name must require ${DshMcpToolProvider.PERMISSION_MANAGE}",
            )
        }
    }

    @Test
    fun `read-only tools claim no permissions`() {
        // A read-only tool with a permission is not dangerous, but it is a sign
        // the readOnly flag is wrong — which would exclude it from an agent that
        // should be able to diagnose the plugin.
        val readOnlyWithPermissions = tools()
            .filter { it.readOnly && (it.requiredPermissions.isNotEmpty() || it.requiresAdmin) }
            .map { it.name }
        assertEquals(emptyList(), readOnlyWithPermissions)
    }

    @Test
    fun `doctor is read-only and ungated so it can always diagnose`() {
        val doctor = tools().single { it.name == "dsh_doctor" }
        assertTrue(doctor.readOnly)
        assertTrue(doctor.requiredPermissions.isEmpty())
        assertTrue(!doctor.requiresAdmin)
    }

    @Test
    fun `every ungated mutating exception actually exists and is still mutating`() {
        // Guards the guard: an exception left behind for a renamed or deleted tool
        // silently widens the allowlist for whatever later takes that name.
        val byName = tools().associateBy { it.name }
        DshMcpToolProvider.UNGATED_MUTATING_TOOLS.forEach { (name, reason) ->
            val tool = byName[name]
                ?: fail("UNGATED_MUTATING_TOOLS names `$name`, which no longer exists")
            assertTrue(!tool.readOnly, "`$name` is read-only now; remove its exception")
            assertTrue(reason.isNotBlank(), "`$name` needs a stated reason")
        }
    }

    @Test
    fun `tool names are unique and prefixed`() {
        val names = tools().map { it.name }
        assertEquals(names.size, names.distinct().size, "duplicate tool names: $names")
        val unprefixed = names.filter { !it.startsWith("dsh_") }
        assertEquals(emptyList(), unprefixed, "every tool must be namespaced under dsh_")
    }
}
