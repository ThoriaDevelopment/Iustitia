package dev.iustitia.checks.movement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #36: Speed(Momentum) must rest at 0 on vanilla movement, not at VL ~1.0. Drives the pure
 * [MomentumModel] the same way [SpeedEnvelopeCheck] does, against a vanilla horizontal-physics
 * trace (ground: v += input, move, v *= 0.546; air: v += 0.026, move, v *= 0.91; sprint-jump
 * takeoff: ground input + 0.2 impulse, then air friction).
 */
class MomentumModelTest {

    private val groundFriction = MomentumModel.AIR_FRICTION * 0.6

    /** One observed tick, as the tracker would see it. */
    private data class Obs(val offsetH: Double, val deltaY: Double, val grounded: Boolean)

    /** Mirrors the per-tick momentum block of [SpeedEnvelopeCheck.process]. */
    private class Harness(val groundFriction: Double) {
        var lastOffsetH = 0.0
        var advantage = 0.0
        var wasGrounded = false
        var lastDeltaY = 0.0
        var flags = 0
        var maxOffset = 0.0

        fun tick(o: Obs, sprinting: Boolean = true) {
            val friction = if (o.grounded) groundFriction else MomentumModel.AIR_FRICTION
            val takeoff = MomentumModel.isTakeoff(o.deltaY, o.grounded, wasGrounded, lastDeltaY)
            val allowance = MomentumModel.allowance(o.grounded, takeoff, friction, groundFriction, 1.0, sprinting)
            val offset = MomentumModel.excess(o.offsetH, lastOffsetH, allowance)
            advantage = MomentumModel.accumulate(advantage, offset)
            if (o.offsetH > 0.2 && MomentumModel.shouldFlag(offset, advantage)) flags++
            maxOffset = maxOf(maxOffset, offset)
            lastOffsetH = o.offsetH * friction
            wasGrounded = o.grounded
            lastDeltaY = o.deltaY
        }
    }

    /**
     * Vanilla sprint trace: [groundTicks] of flat sprint, then [jumps] held-jump sprint-jumps
     * (re-jumping on the tick after landing, so no grounded tick between hops). [speedScale]
     * multiplies every input — 1.0 is vanilla, 1.2 a +20% speed hack.
     */
    private fun sprintTrace(groundTicks: Int, jumps: Int, speedScale: Double = 1.0): List<Obs> {
        val out = ArrayList<Obs>()
        val groundInput = MomentumModel.groundMovementSpeed(groundFriction, 1.0, true) * speedScale
        val airInput = MomentumModel.airMovementSpeed(1.0) * speedScale
        var v = 0.0
        repeat(groundTicks) {
            v += groundInput
            out += Obs(v, 0.0, grounded = true)
            v *= groundFriction
        }
        repeat(jumps) {
            // takeoff: started on the ground → ground input + sprint-jump impulse, then the
            // player is airborne, so air friction applies at the end of the tick.
            v += groundInput + MomentumModel.JUMP_BOOST * speedScale
            var vy = 0.42
            var y = vy
            out += Obs(v, vy, grounded = false)
            v *= MomentumModel.AIR_FRICTION
            while (true) {
                vy = (vy - 0.08) * 0.98
                val dy = if (y + vy <= 0.0) -y else vy
                y += dy
                v += airInput
                out += Obs(v, dy, grounded = false)
                v *= MomentumModel.AIR_FRICTION
                if (y <= 0.0) break
            }
        }
        return out
    }

    @Test
    fun `vanilla flat sprint has zero excess`() {
        val h = Harness(groundFriction)
        sprintTrace(groundTicks = 200, jumps = 0).forEach(h::tick)
        assertEquals(0, h.flags)
        assertEquals(0.0, h.advantage, 1e-9)
    }

    @Test
    fun `vanilla sprint-jumping never flags and the pool stays empty`() {
        val h = Harness(groundFriction)
        sprintTrace(groundTicks = 40, jumps = 30).forEach(h::tick)
        assertEquals(0, h.flags, "legit sprint-jumps flagged (max excess ${h.maxOffset})")
        assertTrue(h.advantage < 0.05, "pool latched at ${h.advantage}")
    }

    @Test
    fun `legit jumps with an unobserved sprint flag drain instead of latching`() {
        // The tracker's sprint flag can lag, leaving 0.03 of unexplained input per takeoff; the
        // leak must drain it over the hop instead of building toward ADVANTAGE_FLAG.
        val h = Harness(groundFriction)
        // sprint reads false only on the takeoff ticks (Δy = 0.42)
        sprintTrace(groundTicks = 40, jumps = 60).forEach { h.tick(it, sprinting = it.deltaY < 0.4) }
        assertEquals(0, h.flags)
        assertTrue(h.advantage < MomentumModel.ADVANTAGE_FLAG / 4, "pool built to ${h.advantage}")
    }

    @Test
    fun `a full pool does not flag clean movement`() {
        val h = Harness(groundFriction)
        h.advantage = MomentumModel.MAX_ADVANTAGE
        sprintTrace(groundTicks = 200, jumps = 0).forEach(h::tick)
        assertEquals(0, h.flags)
        // and it drains at the leak rate instead of sitting at the cap
        assertEquals(MomentumModel.MAX_ADVANTAGE - 200 * MomentumModel.ADVANTAGE_LEAK, h.advantage, 1e-9)
    }

    @Test
    fun `sustained plus twenty percent overspeed still flags`() {
        val h = Harness(groundFriction)
        sprintTrace(groundTicks = 200, jumps = 0, speedScale = 1.2).forEach(h::tick)
        assertTrue(h.flags > 0, "+20% sprint never flagged (adv ${h.advantage})")
    }

    @Test
    fun `sustained plus ten percent overspeed still flags`() {
        val h = Harness(groundFriction)
        sprintTrace(groundTicks = 300, jumps = 0, speedScale = 1.1).forEach(h::tick)
        assertTrue(h.flags > 0, "+10% sprint never flagged (adv ${h.advantage})")
    }

    @Test
    fun `a sprint-jump hack beyond the vanilla impulse still flags immediately`() {
        val h = Harness(groundFriction)
        val trace = sprintTrace(groundTicks = 40, jumps = 1).toMutableList()
        val takeoff = trace.indexOfFirst { it.deltaY > 0.0 }
        trace[takeoff] = trace[takeoff].copy(offsetH = trace[takeoff].offsetH + 0.15)
        trace.forEach(h::tick)
        assertTrue(h.flags > 0)
    }

    @Test
    fun `takeoff needs a real rise after ground or a fall`() {
        assertTrue(MomentumModel.isTakeoff(0.42, grounded = false, wasGrounded = true, lastDeltaY = 0.0))
        assertTrue(MomentumModel.isTakeoff(0.42, grounded = false, wasGrounded = false, lastDeltaY = -0.3))
        // mid-air rise (the arc itself) is not a new takeoff
        assertTrue(!MomentumModel.isTakeoff(0.33, grounded = false, wasGrounded = false, lastDeltaY = 0.42))
        // a micro-hop gets no impulse
        assertTrue(!MomentumModel.isTakeoff(0.05, grounded = false, wasGrounded = true, lastDeltaY = 0.0))
    }
}
