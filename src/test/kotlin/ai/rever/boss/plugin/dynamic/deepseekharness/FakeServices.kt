package ai.rever.boss.plugin.dynamic.deepseekharness

import ai.rever.boss.plugin.api.PluginContext
import java.lang.reflect.Proxy

/**
 * A [PluginContext] that answers null for every host provider, and the
 * [DshServices] built on top of it.
 *
 * Null is the legal answer for every provider on `PluginContext`, and the plugin
 * is required to degrade rather than crash when one is absent — so a stub that
 * answers null everywhere is not a weak fake, it is the hostile case. Any test
 * that passes against it has also shown the plugin survives a host with no
 * browser engine, no secrets, no terminal and no project open.
 *
 * A dynamic proxy rather than a hand-written class: `PluginContext` has dozens of
 * members and grows every api release, and a hand-written fake would need a new
 * override each time — a compile break in tests for a member no test uses.
 */
object FakeServices {

    fun context(): PluginContext = stub(PluginContext::class.java) as PluginContext

    fun services(): DshServices = DshServices(context())

    private fun stub(iface: Class<*>): Any =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            // Object's own methods reach the handler too, and null is not a legal
            // return for hashCode's primitive int.
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "${iface.simpleName}(stub)"
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        }
}
