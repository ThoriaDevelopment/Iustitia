package dev.iustitia.tracking

/**
 * Server tick clock, adapted from MeteorClient's `utils.world.TickRate` (the counter behind
 * its "Time since last tick" HUD).
 * https://github.com/MeteorDevelopment/meteor-client/blob/master/src/main/java/meteordevelopment/meteorclient/utils/world/TickRate.java
 *
 * Reading the server's own clock replaces the per-player position-delta heuristic this used to
 * feed (how many tracked players had |Δ|≈0): fakeable by one Blinker freezing in a lobby, one
 * snapshot late, and blind in a 1v1.
 *
 * [onWorldTimeUpdate] runs on the netty thread, straight from the packet handler, so the stamp
 * carries no tick-queue latency; the fields it writes are volatile — readers only need
 * "roughly now".
 */
object ServerTickRate {

    /**
     * Elapsed seconds beyond which the server has missed a 20-tick sync. A healthy connection
     * oscillates 0 → ~1.0 s between syncs, so jitter alone rarely crosses it; a real hitch
     * adds its whole duration to the gap.
     */
    const val LAG_SECONDS = 1.1f

    /** Timestamp (ms) of the last sync, or -1 before the first one of this session. */
    @Volatile
    private var lastUpdate: Long = -1L

    @Volatile
    private var joinedAt: Long = 0L

    /**
     * True once a sync has arrived this session. Some servers/proxies never send the packet;
     * without this guard the elapsed time would grow forever and exempt every check permanently
     * — silently disabling the anticheat. Unsynced means no lag signal, i.e. checks run at full
     * strength (the fail-open-toward-detection direction).
     */
    @Volatile
    var synced: Boolean = false
        private set

    fun onWorldTimeUpdate(now: Long = System.currentTimeMillis()) {
        lastUpdate = now
        synced = true
    }

    /**
     * Seconds since the last 20-tick sync, or 0 during the first 4 s after join (Meteor's
     * warm-up window, which swallows the join-burst packet flood). [now] is a test seam.
     */
    fun timeSinceLastTick(now: Long = System.currentTimeMillis()): Float {
        val last = lastUpdate
        if (last < 0L) return 0f
        if (now - joinedAt < 4000L) return 0f
        return (now - last) / 1000f
    }

    /** Clear per-session state. Called from [EntityTrackerManager.reset] on join / world change. */
    fun reset(now: Long = System.currentTimeMillis()) {
        lastUpdate = -1L
        joinedAt = now
        synced = false
    }
}
