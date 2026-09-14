package dev.iustitia.selftest

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.MinecraftClient

/**
 * Thin bridge between the gametest thread and the client thread. The gametest
 * framework's own [ClientGameTestContext.runOnClient] is the canonical path, but
 * several harness components (the config snapshot, the reset helper) need a client
 * hop *after* the context is already closed or before it is passed around, so the
 * harness keeps one reference to the current context and exposes the same semantics.
 *
 * The context reference is set once per scenario by the runner; helpers resolve it
 * lazily so they never capture a stale context across scenarios.
 */
object ClientThread {
    @Volatile
    private var ctx: ClientGameTestContext? = null

    fun bind(context: ClientGameTestContext) {
        ctx = context
    }

    fun unbind() {
        ctx = null
    }

    private fun context(): ClientGameTestContext =
        ctx ?: throw IllegalStateException("no ClientGameTestContext bound -- call ClientThread.bind(context) first")

    /**
     * Run [action] on the client thread and wait for it to complete. Throws the
     * action's exception on the gametest thread, so failures propagate as test
     * failures instead of being swallowed.
     *
     * The Fabric API parameterizes the checked exception (`E extends Throwable`) purely
     * for interop with its `FailableConsumer`; the harness only ever throws unchecked
     * exceptions, so `E` is pinned to `RuntimeException` here rather than exposed to
     * every call site (which the Kotlin compiler cannot infer from a non-throwing
     * lambda).
     */
    fun runOnClient(action: (MinecraftClient) -> Unit) {
        context().runOnClient<RuntimeException> { mc -> action(mc) }
    }

    /** Run [action] on the client thread and return its result. */
    fun <T : Any> computeOnClient(action: (MinecraftClient) -> T): T {
        return context().computeOnClient<T, RuntimeException> { mc -> action(mc) }
    }
}
