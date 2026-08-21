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

    fun context(): PluginContext = context(emptyMap())

    /**
     * The same all-null context, except that [overrides] answers for the JVM method
     * names it names — `"getSplitViewOperations"` for the `splitViewOperations`
     * property, and so on.
     *
     * Keyed by method name rather than by property so a test can stub a plain
     * function too, and so nothing here has to know which Kotlin members exist.
     */
    fun context(overrides: Map<String, Any?>): PluginContext =
        stub(PluginContext::class.java, overrides) as PluginContext

    fun services(): DshServices = DshServices(context())

    /**
     * A stub of [iface] that records every call to [recorded], for tests that need
     * to assert the plugin asked the host for something rather than just that it
     * survived the host saying no.
     */
    fun recording(iface: Class<*>, recorded: MutableList<Pair<String, List<Any?>>>): Any =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            objectMethod(proxy, iface, method, args)?.let { return@newProxyInstance it }
            recorded += method.name to (args?.toList() ?: emptyList())
            defaultFor(method.returnType)
        }

    private fun stub(iface: Class<*>, overrides: Map<String, Any?> = emptyMap()): Any =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            objectMethod(proxy, iface, method, args)?.let { return@newProxyInstance it }
            if (overrides.containsKey(method.name)) overrides[method.name] else defaultFor(method.returnType)
        }

    /**
     * Object's own methods reach the handler too, and null is not a legal return
     * for hashCode's primitive int. Returns null when [method] is not one of them,
     * which is distinguishable from a stubbed null because these three never are.
     */
    private fun objectMethod(proxy: Any, iface: Class<*>, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "${iface.simpleName}(stub)"
            else -> null
        }

    private fun defaultFor(returnType: Class<*>): Any? =
        when (returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
}
