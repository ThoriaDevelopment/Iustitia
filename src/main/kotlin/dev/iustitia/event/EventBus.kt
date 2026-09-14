package dev.iustitia.event

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal typed publish/subscribe bus. Subscribers register against a concrete
 * event class token; [publish] dispatches to subscribers of the event's exact runtime
 * class. Used for the rare cross-cutting derived events (e.g. AttackEvent). Per-tick
 * check processing goes through the tick driver directly, not the bus.
 */
class EventBus {
    private val subs = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<(Any) -> Unit>>()

    fun <T : Any> subscribe(type: Class<T>, handler: (T) -> Unit) {
        val list = subs.getOrPut(type) { CopyOnWriteArrayList() }
        @Suppress("UNCHECKED_CAST")
        list.add(handler as (Any) -> Unit)
    }

    inline fun <reified T : Any> subscribe(noinline handler: (T) -> Unit) =
        subscribe(T::class.java, handler)

    fun publish(event: Any) {
        // Threading: publishers arrive from two threads — the netty packet thread (mixin
        // handlers) and the client tick thread. Inline dispatch on the packet thread races
        // the tick driver on subscriber state (check VL, TrackedPlayer scalars, inference
        // buffers), so off-thread publishes are handed to the tick driver's defer queue and
        // dispatched on the client thread at the next tick start (events carry explicit
        // tick stamps; the ≤1-tick latency does not affect any window). On-thread publishes
        // dispatch inline, unchanged — a MinecraftClient.execute{} hop would run inline here
        // anyway (client==render thread on 1.21.11).
        if (!dev.iustitia.Iustitia.onClientThread()) {
            dev.iustitia.Iustitia.defer { publish(event) }
            return
        }
        val list = subs[event.javaClass] ?: return
        for (h in list) {
            try {
                h(event)
            } catch (_: Throwable) {
                // fail-open: a single subscriber exception must not break dispatch
            }
        }
    }
}