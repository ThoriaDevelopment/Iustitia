package dev.iustitia.selftest

import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import dev.iustitia.event.EffectSignal
import dev.iustitia.event.HurtSource
import dev.iustitia.event.HurtSignal
import dev.iustitia.event.SwingSignal
import dev.iustitia.event.VelocitySignal
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.block.Block
import net.minecraft.block.Blocks
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.sound.SoundCategory
import net.minecraft.util.Hand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * The self-test engine: spawns scriptable bot players in a real flat world, drives
 * them through vanilla-accurate or cheat-accurate motion, and runs the real pipeline
 * over the result.
 *
 * ## Why bots instead of packets
 *
 * Iustitia's tracker polls the live client world (`EntityTrackerManager.poll`), so a
 * bot that is a real `OtherClientPlayerEntity` in the world IS a tracked player --
 * no injection seam, no packet replay. Combat and effect signals that the real
 * pipeline learns from rebroadcast packets are injected through the same public
 * [Iustitia.bus] the packet mixin publishes to, so the inference layer (swing/hurt
 * correlation, protocol gating) runs for real. Nothing about the pipeline is
 * bypassed; only the transport differs, and only where the client never observes it
 * anyway.
 *
 * ## Determinism
 *
 * Scenarios advance the game tick-by-tick via [ClientGameTestContext.waitTicks], so
 * bot scripts are expressed in ticks and the whole run is reproducible. The gametest
 * world is a fixed-seed flat world with weather/mob spawning off (framework
 * defaults).
 *
 * ## Safety
 *
 * Bots are display entities the server would send; they are created client-side
 * only, never sent anywhere, and are discarded with the gametest world at the end
 * of each scenario. No packet leaves the process at any point. `SelfTestHooks`
 * recording is dev-gated on the `fabric.selftest` JVM property; without it the
 * harness cannot observe flags (and there is nothing to observe in normal play).
 */
object SelfTest {

    /** Total budget the inter-scenario teardown barrier ([awaitServerTeardown]) will wait. */
    private const val SERVER_TEARDOWN_TIMEOUT_MS = 120_000L

    /** Natural-teardown grace before the barrier forces a disconnect on a lingering server. */
    private const val SERVER_TEARDOWN_GRACE_MS = 5_000L

    /** Poll interval for the teardown barrier. */
    private const val SERVER_TEARDOWN_POLL_MS = 250L

    /** Set when the teardown barrier gave up on a frozen integrated server (see
     *  [awaitServerTeardown]); [runAll] aborts the suite on the next loop iteration
     *  instead of letting every later scenario cascade-fail. */
    private var suiteFatal: String? = null

    /** A scriptable other-player bot. The handle exposes a tick-aligned action API. */
    class BotHandle internal constructor(
        val name: String,
        val uuid: UUID,
    ) {
        internal var entity: net.minecraft.client.network.OtherClientPlayerEntity? = null

        /**
         * Move the bot to an absolute position (instant, one tick). Movement checks
         * sample per tick, so stepwise small [teleportTo] calls every tick are how
         * "legit walking" is scripted; a single large jump is how a teleport is
         * scripted.
         */
        fun teleportTo(x: Double, y: Double, z: Double) {
            ClientThread.runOnClient { _ ->
                entity?.setPosition(x, y, z)
            }
        }

        /**
         * Set the entity's on-ground flag -- the client-side stand-in for the movement
         * packet flag the server would send. A bot left standing on the surface must be
         * on-ground, or hover-style checks correctly read it as floating.
         */
        fun setOnGround(on: Boolean) {
            ClientThread.runOnClient { _ ->
                try { entity?.setOnGround(on) } catch (_: Throwable) { }
            }
        }

        /**
         * Set the sprint metadata flag. Several checks gate on it (sprint-only knockback is
         * the precondition for the no-knockback check, sprint-through-attack is keep-sprint),
         * so scenarios must be able to present a genuinely sprinting bot.
         */
        fun sprint(on: Boolean) {
            ClientThread.runOnClient { _ ->
                try { entity?.setSprinting(on) } catch (_: Throwable) { }
            }
        }

        /** Face the bot (yaw/pitch in degrees). */
        fun look(yaw: Float, pitch: Float) {
            ClientThread.runOnClient { _ ->
                val e = entity ?: return@runOnClient
                e.setYaw(yaw); e.setPitch(pitch)
                e.bodyYaw = yaw; e.headYaw = yaw
            }
        }

        /** The bot's live entity id -- used as [hurt]'s attacker id so attack inference can
         *  resolve the attacker directly instead of falling back to nearest-player guessing. */
        val entityId: Int get() = try { entity?.id ?: -1 } catch (_: Throwable) { -1 }

        /** Spawn position, captured by [ScenarioBuilder.bot] -- the origin motion drives build on. */
        internal var spawnX: Double = 0.0
        internal var spawnY: Double = 0.0
        internal var spawnZ: Double = 0.0

        /**
         * Swing the bot's main arm now. Published through the same bus the packet
         * mixin uses, so attack inference correlates it with a subsequent hurt for
         * real; the entity also plays the vanilla swing animation for replay capture.
         */
        fun swing() {
            ClientThread.runOnClient { _ ->
                Iustitia.bus.publish(
                    SwingSignal(
                        attacker = uuid,
                        tick = Iustitia.tickCounter,
                        nanoTime = System.nanoTime(),
                        animationId = 0,
                    )
                )
                entity?.swingHand(Hand.MAIN_HAND)
            }
        }

        /** Inject a hurt for this bot (it was damaged) on the Iustitia clock. */
        fun hurt(attackerEntityId: Int = -1, source: HurtSource = HurtSource.ENTITY_DAMAGE) {
            publishFromClient {
                HurtSignal(
                    victim = uuid,
                    tick = Iustitia.tickCounter,
                    attackerEntityId = attackerEntityId,
                    source = source,
                )
            }
        }

        /** Inject a velocity update (knockback) for this bot. */
        fun velocity(vx: Double, vy: Double, vz: Double) {
            publishFromClient { VelocitySignal(uuid, Iustitia.tickCounter, Vec3d(vx, vy, vz)) }
        }

        /** Apply or remove a Speed effect (amplifier 0 = Speed I). */
        fun speedEffect(added: Boolean, amplifier: Int = 0) {
            publishFromClient {
                EffectSignal(
                    entity = uuid,
                    tick = Iustitia.tickCounter,
                    isSpeed = true,
                    speedAmplifier = amplifier,
                    added = added,
                )
            }
        }

        /** Apply or remove Blindness -- the EffectSignal path sprintHack reads. */
        fun blindness(added: Boolean) {
            publishFromClient {
                EffectSignal(
                    entity = uuid,
                    tick = Iustitia.tickCounter,
                    isSpeed = false,
                    speedAmplifier = -1,
                    isBlind = true,
                    blindAmplifier = 0,
                    added = added,
                )
            }
        }

        /** Sneak metadata (the tracker polls `isSneaking`; sprint-while-sneaking is a cheat). */
        fun sneak(on: Boolean) {
            ClientThread.runOnClient { _ -> try { entity?.setSneaking(on) } catch (_: Throwable) { } }
        }

        /**
         * Glide (elytra) metadata. The tracker polls `isGliding`, which vanilla backs with the
         * GLIDING entity flag, so entering the glide through the same public API the game uses
         * (`PlayerEntity.startGliding` / `stopGliding`) presents exactly the state the server
         * would rebroadcast when a player deploys or stows an elytra.
         */
        fun gliding(on: Boolean) {
            ClientThread.runOnClient { _ ->
                try { if (on) entity?.startGliding() else entity?.stopGliding() } catch (_: Throwable) { }
            }
        }

        /** Swim metadata (`isSwimming`) -- the guard several movement checks exempt on. */
        fun swimming(on: Boolean) {
            ClientThread.runOnClient { _ -> try { entity?.setSwimming(on) } catch (_: Throwable) { } }
        }

        /** Hold [stack] in the main hand; the tracker reads that stack's use action. */
        fun hold(stack: ItemStack) {
            ClientThread.runOnClient { _ ->
                try { entity?.equipStack(EquipmentSlot.MAINHAND, stack) } catch (_: Throwable) { }
            }
        }

        /** Wear [stack] in [slot] (chest for an elytra, head for a helmet). */
        fun equip(slot: EquipmentSlot, stack: ItemStack) {
            ClientThread.runOnClient { _ ->
                try { entity?.equipStack(slot, stack) } catch (_: Throwable) { }
            }
        }

        /**
         * Begin using the held item (`usingItem`), which is what the server rebroadcasts when a
         * player eats, drinks or raises a shield. Combined with the held stack's use action the
         * tracker derives `isUsingConsumable` (EAT/DRINK) or `isBlocking` (BLOCK), so the drive
         * that reproduces "eating" or "shield up" is exactly the metadata pair a real client sends.
         *
         * Both halves of that pair are needed and `setCurrentHand` only does one of them, which is
         * a real trap: `LivingEntity.setCurrentHand` writes the live tracked-data
         * `USING_ITEM` bit **only server-side** (`if (!world.isClient())`), so on a client-side
         * `OtherClientPlayerEntity` it sets `activeItemStack` and leaves `isUsingItem()` false. A
         * *real* observant client only ever sees that bit because the server rebroadcasts the
         * `LivingEntity` metadata byte, so the harness must perform the same write the metadata
         * packet performs. See [setUsingItemBit].
         */
        fun startUsing() {
            ClientThread.runOnClient { _ ->
                try { entity?.setCurrentHand(Hand.MAIN_HAND) } catch (_: Throwable) { }
                setUsingItemBit(true)
            }
        }

        /** Stop using the held item (clears `usingItem`). */
        fun stopUsing() {
            ClientThread.runOnClient { _ ->
                try { entity?.stopUsingItem() } catch (_: Throwable) { }
                setUsingItemBit(false)
            }
        }

        /**
         * Write the `LivingEntity` `USING_ITEM` tracked bit -- the exact byte the server
         * rebroadcasts in its entity-metadata packet when a player starts or stops eating,
         * drinking or blocking, and the byte `LivingEntity.isUsingItem()` reads back.
         *
         * `setLivingFlag(int, boolean)` is `protected`, and no public API reaches the tracked
         * data from outside the entity, so this is done reflectively. That is confined to the
         * harness: this class only ever runs inside the gametest dev runtime (never in the shipped
         * mod), the call is on the client thread, and every failure is swallowed -- if the write
         * ever fails, the drive simply behaves as it did before instead of failing a scenario.
         */
        private fun setUsingItemBit(on: Boolean) {
            val e = entity ?: return
            try {
                val m = net.minecraft.entity.LivingEntity::class.java.getDeclaredMethod(
                    "setLivingFlag", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                )
                m.isAccessible = true
                m.invoke(e, LIVING_FLAG_USE_ITEM, on)
            } catch (_: Throwable) {
            }
        }

        /**
         * The `LivingEntity` living-flags bit the server sets while a player is using an item
         * (`setCurrentHand` writes `setLivingFlag(1, true)` on the server; the matching
         * `OFF_HAND` bit is 2). Kept as a named constant so the reflection above reads as intent.
         */
        private val LIVING_FLAG_USE_ITEM = 1

        /**
         * A whole melee exchange in one call: swing, the victim's hurt (carrying this bot's
         * entity id so attack inference attributes the hit directly), and -- unless suppressed --
         * the vanilla knockback impulse the server would broadcast on the victim.
         *
         * [knockback] = false is the anti-knockback drive: the hit lands, the victim never
         * moves, which is the exact signature `noKnockback` looks for.
         */
        fun strike(victim: BotHandle, knockback: Boolean = true) {
            swing()
            victim.hurt(attackerEntityId = entityId)
            if (knockback) victim.velocity(0.0, 0.36, 0.28)
        }

        /**
         * Build [event] and publish it **on the client thread** -- the thread the real packet
         * path dispatches on, and the only thread on which `MinecraftClient.getInstance()` is
         * legal inside the gametest runtime.
         *
         * This is not cosmetic. Fabric's gametest API instruments `MinecraftClient.getInstance()`
         * to throw on the gametest (test) thread, and a scenario body runs there -- so any signal
         * that forwarded its check's handler on that thread would hit that throw. Detectors are
         * fail-open, so the throw is swallowed and the check silently observes nothing: measured
         * live, `cheat-throughput-vape` reported throughWalls as a bypass while the check's own
         * world read was throwing `IllegalStateException` on every single hit. Building the event
         * inside the hop also means `Iustitia.tickCounter` is sampled on the client thread, so the
         * tick matches the swing's (which is published the same way).
         */
        private fun <T : Any> publishFromClient(signal: () -> T) {
            ClientThread.runOnClient { _ -> Iustitia.bus.publish(signal()) }
        }
    }

    /**
     * A recurrence assertion: [checkId] must produce at least [atLeast] distinct alert **episodes**
     * for [bot] under [label].
     *
     * Kept as its own type rather than a fourth element on the expectation triple because
     * `expectations` is read positionally and serialized into the report as `Map<String, Boolean>`,
     * which `scripts/live_selftest.py` consumes for `--coverage` and `--matrix`. Widening it would
     * break a published JSON contract in three python paths for no gain.
     */
    internal data class AlertCountExpectation(
        val bot: UUID,
        val checkId: String,
        val label: String,
        val atLeast: Int,
    )

    /** Builder DSL for a scenario body. */
    class ScenarioBuilder internal constructor(
        private val ctx: ClientGameTestContext,
    ) {
        internal val bots = mutableListOf<BotHandle>()
        internal val tickActions = mutableListOf<Pair<Int, TickAction>>() // (everyN, action)
        internal val expectations = mutableListOf<Triple<UUID, String, Boolean>>() // bot, check, mustAlert
        internal val alertCounts = mutableListOf<AlertCountExpectation>() // bot, check, label, atLeast
        internal val knownOpen = mutableListOf<Triple<UUID, String, String>>() // bot, check, note
        internal val documentedFp = mutableListOf<Triple<UUID, String, String>>() // bot, check, note
        internal val driveGaps = mutableListOf<Triple<UUID, String, String>>() // bot, check, note
        internal var startTimeMs: Long = 0L

        /**
         * Standing ground level for this world: the local player already stands on the
         * flat surface, and a flat world has one surface height everywhere, so this is
         * the correct feet-Y for a bot that should be standing. Spawning bots in mid-air
         * makes them legitimately flaggable as hover/fly, which is why this exists
         * (verified live: a y=100 floating bot tripped `flyEnvelope` with peakVL 15.5).
         */
        internal val groundY: Double by lazy {
            ClientThread.computeOnClient { mc ->
                (mc.player ?: error("no local player -- the client world is not ready")).y
            }
        }

        /** Spawn a bot standing on the world surface at (x, z). */
        fun bot(name: String, x: Double, z: Double): BotHandle =
            bot(name, x, groundY, z).also { it.setOnGround(true) }

        /**
         * Spawn a bot at an explicit (x, y, z) with a unique stable name. Use this for
         * air starts (falls, flight) and leave the bot marked off-ground.
         */
        fun bot(name: String, x: Double, y: Double, z: Double): BotHandle {
            val unique = if (bots.any { it.name == name }) "$name-${bots.size}" else name
            val handle = ClientThread.computeOnClient { mc ->
                val world = mc.world ?: error("no client world -- create the gametest world first")
                val uuid = UUID.nameUUIDFromBytes("iustitia-selftest-$unique".toByteArray())
                val profile = com.mojang.authlib.GameProfile(uuid, unique)
                val e = net.minecraft.client.network.OtherClientPlayerEntity(world, profile)
                e.setPosition(x, y, z)
                try { e.setOnGround(false) } catch (_: Throwable) { }
                world.addEntity(e)
                val h = BotHandle(unique, uuid)
                h.entity = e
                h
            }
            handle.spawnX = x
            handle.spawnY = y
            handle.spawnZ = z
            bots.add(handle)
            // Let the tracker poll the new bot before scripts run.
            ctx.waitTick()
            return handle
        }

        /**
         * Declare the pass expectation: [mustAlert]=true means the cheat pass expects
         * [checkId] to alert on [bot] (a miss is a BYPASS); false means the legit pass
         * expects [checkId] to stay silent (a hit is a FALSE POSITIVE).
         */
        fun expect(bot: BotHandle, checkId: String, mustAlert: Boolean) {
            expectations.add(Triple(bot.uuid, checkId, mustAlert))
        }

        /** Declare that [checkId] must stay silent for [bot] (a legit-pass guard). */
        fun expectQuiet(bot: BotHandle, vararg checkIds: String) {
            for (c in checkIds) expect(bot, c, mustAlert = false)
        }

        /** Declare that every check with an id in [checkIds] must stay silent for [bot]. */
        fun expectQuiet(bot: BotHandle, checkIds: Collection<String>) {
            for (c in checkIds) expect(bot, c, mustAlert = false)
        }

        /**
         * Assert **recurrence**: [checkId] must cross its setback in at least [atLeast] distinct
         * alert episodes under [label].
         *
         * The verdict assertions ([expect] / [expectQuiet]) only say *whether* a check ever fired, so
         * they cannot see a check whose episode latch never re-arms — the first episode always
         * satisfies them and the second is invisible. This is the assertion for that class of bug:
         * drive the pattern, break it, drive it again, and require two.
         *
         * What it measures is the alert *event* recurring, not a VL number or a latch bit — a check
         * is free to re-arm however it likes. Two crossings closer than
         * [SelfTestHooks.SAME_EPISODE_TICKS] count as one, because a slow-decay VL rings above the
         * setback for tens of ticks after a single `flagEpisode`. Drive the second episode far
         * enough from the first that the gap exceeds that window.
         *
         * Also registers the plain `mustAlert` expectation, so `missed`, `--coverage` and `--matrix`
         * keep working and a count failure is never mistaken for "check never fired".
         */
        fun expectAlertCount(bot: BotHandle, checkId: String, label: String, atLeast: Int) {
            require(atLeast >= 2) { "use expect(bot, checkId, mustAlert = true) for a single alert" }
            expectations.add(Triple(bot.uuid, checkId, true))
            alertCounts.add(AlertCountExpectation(bot.uuid, checkId, label, atLeast))
        }

        /**
         * Declare a **known-open finding**: a behavior the check is expected to catch but
         * currently cannot, with the reason. Known-open entries never fail the run -- they are
         * printed on every run (and copied into the PR) so the gap stays visible until someone
         * closes it, at which point the entry flips to "promote me" and the scenario is tightened.
         *
         * Use this only for a *detector* limitation you have verified (e.g. a setback that decay
         * makes unreachable). A scenario you simply have not driven hard enough is not known-open;
         * raise its intensity instead.
         */
        fun expectKnownOpen(bot: BotHandle, checkId: String, mustAlert: Boolean = true, note: String) {
            knownOpen.add(Triple(bot.uuid, checkId, note))
        }

        /**
         * A **verified detector false positive** that this change is not responsible for fixing.
         *
         * Distinct from [expectKnownOpen]: that records "the detector cannot catch this yet"
         * (a miss), this records "the detector fires on legitimate play" (a wrong flag). Wrong
         * flags are release-blocking bugs, so they are printed in their own loud section on every
         * run, counted in the report, and turned into failures by `live_selftest.py --strict`.
         * They are non-blocking by default only so a scenario that documents an existing FP does
         * not turn every contributor's unrelated run red.
         *
         * Use it ONLY with a note that names the observed drive and the reason; an unexplained
         * documented FP is exactly the greenwashing this mechanism must not enable.
         */
        fun expectDocumentedFp(bot: BotHandle, checkId: String, note: String) {
            documentedFp.add(Triple(bot.uuid, checkId, note))
        }

        /**
         * This scenario's DRIVE does not yet reach [checkId] -- a harness gap, not a detector
         * finding.
         *
         * Use it when the check logged *no flag at all* during the scenario, which means the
         * bot never produced the input the check reacts to. The alert assertion is therefore not
         * established either way, and writing it as a plain expectation would leave the suite
         * permanently red for a reason that is about this file, not about Iustitia. The entry
         * carries the observed peak VL, so a reviewer can tell the two apart instantly: 0.00
         * means the check was never asked, anything above 0 means it reacted and the driver is
         * close.
         *
         * Do NOT use it to retire a real bypass you have not diagnosed -- that is the greenwashing
         * [expectKnownOpen] and [expectDocumentedFp] exist to prevent. `docs/automated-live-testing.md`
         * lists the driven-check queue these entries build.
         */
        fun expectDriveGap(bot: BotHandle, checkId: String, note: String) {
            driveGaps.add(Triple(bot.uuid, checkId, note))
        }

        /** Run [action] every [everyN] ticks for the remainder of the scenario. */
        fun everyTick(everyN: Int = 1, action: TickAction) {
            tickActions.add(everyN to action)
        }

        /** Run [action] once per tick (sugar for [everyTick] with everyN = 1). */
        fun everyTick(action: TickAction) = everyTick(1, action)

        // ------------------------------------------------------------------
        // World surgery -- the drives that need a wall, a pool or a pocket.
        // ------------------------------------------------------------------

        /** Force one block in the client world (harness-side world surgery). */
        fun place(x: Int, y: Int, z: Int, block: Block) {
            ClientThread.runOnClient { mc ->
                val w = mc.world ?: return@runOnClient
                try { w.setBlockState(BlockPos(x, y, z), block.defaultState, 3) } catch (_: Throwable) { }
            }
        }

        /** Force an inclusive block region -- walls, floors, pools and pockets. */
        fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, block: Block) {
            ClientThread.runOnClient { mc ->
                val w = mc.world ?: return@runOnClient
                for (x in minOf(x1, x2)..maxOf(x1, x2)) {
                    for (y in minOf(y1, y2)..maxOf(y1, y2)) {
                        for (z in minOf(z1, z2)..maxOf(z1, z2)) {
                            try { w.setBlockState(BlockPos(x, y, z), block.defaultState, 3) } catch (_: Throwable) { }
                        }
                    }
                }
            }
        }

        /** Remove a block (air). */
        fun clear(x: Int, y: Int, z: Int) = place(x, y, z, Blocks.AIR)

        // ------------------------------------------------------------------
        // Motion primitives -- every drive is these plus a counter.
        // ------------------------------------------------------------------

        /**
         * Move [bot] at a fixed per-tick velocity (blocks/tick) from its spawn, for the
         * scenario's life. The primitive every movement drive is built from: a sprint pace is
         * `0.28`, a fly is `+0.1` vertical, a teleport is one tick of a huge vector.
         *
         * [grounded] keeps the on-ground flag consistent with the motion (a grounded walk must
         * be on-ground or the hover checks legitimately read it as airborne).
         *
         * **[span] bounds the horizontal travel.** Past it the bot is teleported back to its
         * spawn and keeps going. This is not cosmetic: the gametest world's loaded region does
         * not extend indefinitely, and outside it every world query fails open -- `isSolidBelow`
         * goes false, so a bot standing perfectly still on the ground reads as *airborne*, which
         * the flight checks then legitimately flag. That was a real harness bug (verified live: a
         * 95-block walk produced `Fly(Hover)`/`Fly(FlyB)`/`NoFall` false positives at the far end
         * of the lane). Keeping the drive inside the loaded region is what makes those assertions
         * meaningful. Vertical travel is unbounded -- the sky is loaded all the way up.
         */
        fun drive(
            bot: BotHandle,
            dx: Double,
            dy: Double,
            dz: Double,
            grounded: Boolean? = null,
            span: Double = 40.0,
        ) {
            var t = 0
            everyTick {
                val i = t++
                val ox = dx * i
                val oz = dz * i
                val wrapped = if (kotlin.math.hypot(ox, oz) > span) 0 else i
                bot.teleportTo(bot.spawnX + dx * wrapped, bot.spawnY + dy * i, bot.spawnZ + dz * wrapped)
                if (grounded != null) bot.setOnGround(grounded)
            }
        }

        /**
         * Repeat a full melee exchange between [attacker] and [victim] every [every] ticks,
         * aiming at yaw [aimYaw] each tick. [knockback] = false drives anti-knockback.
         */
        fun strike(
            attacker: BotHandle,
            victim: BotHandle,
            every: Int = 5,
            knockback: Boolean = true,
            aimYaw: Float? = 0f,
            aimPitch: Float = 0f,
        ) {
            var since = 0
            everyTick {
                if (aimYaw != null) attacker.look(aimYaw, aimPitch)
                if (since++ >= every) { attacker.strike(victim, knockback); since = 0 }
            }
        }

        /** Advance the game by [ticks] client ticks, running all registered tick actions. */
        fun runFor(ticks: Int) {
            startTimeMs = System.currentTimeMillis()
            val start = Iustitia.tickCounter
            repeat(ticks) { i ->
                val t = start + i + 1
                for ((everyN, action) in tickActions) {
                    if (t % everyN == 0) action.run(t)
                }
                ctx.waitTick()
            }
        }
    }

    /** A scripted behavior applied every N ticks by [ScenarioBuilder.everyTick]. */
    fun interface TickAction {
        fun run(tick: Int)
    }

    /** One named verification scenario (a member of the legit or the cheat pass). */
    abstract class Scenario(
        val name: String,
        val pass: String,
        /**
         * The reference client (or `vanilla`) this scenario's behavior reproduces, e.g.
         * `LiquidBounce`, `Meteor`, `Itami`, `Koid`, `Vape`, `Slinky`, `LionClient`,
         * `Raven`, `Fusion`, `FDPClient`, `OpenSakura`. Cheat scenarios MUST name one -- the
         * runner builds the per-client catch matrix from it, and a matrix cell labeled
         * "LiquidBounce missed" sends the reader to a real client's module list.
         */
        val source: String = "vanilla",
        /** Coarse behavior tags: `combat`, `movement`, `packet`, `world`, `replay`, `guard`. */
        val tags: Set<String> = emptySet(),
    ) {
        abstract fun run(builder: ScenarioBuilder)

        /** Stable `PASS/name` label for listing and reports. */
        val label: String get() = "$pass/$name"
    }

    /** Run a single scenario in a fresh world and return its report. */
    fun runScenario(ctx: ClientGameTestContext, scenario: Scenario): ScenarioReport {
        ClientThread.bind(ctx)
        val snapshot = TestConfigSnapshot.captureAndApply()
        try {
            // Fresh detection state per scenario so prior scenarios can't bleed in.
            ClientThread.runOnClient { _ ->
                SelfTestHooks.clear()
                SelfTestHooks.startRecording()
                Iustitia.resetAll()
            }

            ctx.worldBuilder().create().use { world ->
                world.getClientWorld().waitForChunksRender()
                val builder = ScenarioBuilder(ctx)
                val scenarioStart = System.currentTimeMillis()
                var error: String? = null
                try {
                    scenario.run(builder)
                } catch (t: Throwable) {
                    error = "${t.javaClass.simpleName}: ${t.message}"
                } finally {
                    // Always stop the recorder, even on assertion failure.
                    ClientThread.runOnClient { _ -> SelfTestHooks.stopRecording() }
                }

                val result = evaluate(builder)
                val duration = System.currentTimeMillis() - scenarioStart
                return ScenarioReport(
                    scenario = scenario.name,
                    pass = scenario.pass,
                    source = scenario.source,
                    tags = scenario.tags,
                    expectations = builder.expectations.associate { it.second to it.third },
                    passed = error == null && result.missed.isEmpty() && result.falsePositives.isEmpty() &&
                        result.alertCountMisses.isEmpty(),
                    vl = result.vl,
                    alertedChecks = result.alerted,
                    missedChecks = result.missed,
                    falsePositives = result.falsePositives,
                    alertCounts = result.alertCounts,
                    alertCountMisses = result.alertCountMisses,
                    knownOpen = result.knownOpen,
                    driveGaps = result.driveGaps,
                    durationMs = duration,
                    error = error,
                )
            }
        } catch (t: Throwable) {
            return ScenarioReport(
                scenario = scenario.name,
                pass = scenario.pass,
                source = scenario.source,
                tags = scenario.tags,
                passed = false,
                vl = emptyMap(),
                alertedChecks = emptySet(),
                missedChecks = emptySet(),
                falsePositives = emptySet(),
                driveGaps = emptyList(),
                durationMs = 0,
                error = "${t.javaClass.simpleName}: ${t.message}",
            )
        } finally {
            awaitServerTeardown(scenario.name)
            snapshot.restore()
            ClientThread.unbind()
        }
    }

    /**
     * Defensive post-close barrier between scenarios: wait until the scenario's integrated
     * server is *really* gone before letting the next one create a world.
     *
     * The framework's world close already blocks for teardown -- but with a finite timeout
     * that throws instead of keeping its promise. When one slow teardown blows that budget
     * the server is still running as the next scenario calls `worldBuilder().create()`, and
     * everything after it cascade-fails with "Cannot create a world when a server is running"
     * (the known full-run flake; shard re-runs pass only because each shard has more
     * wall-clock slack per scenario). This barrier gives teardown a far more generous
     * budget, and when a server still lingers past a short grace window it forces a
     * disconnect on the client thread instead of giving up. It prints a line whenever it
     * had to intervene, so the slow scenario is identifiable in the run output. Fail-open
     * like the rest of the harness: a broken barrier must never mask the scenario's result.
     *
     * When it has to give up entirely, the server is frozen for good: thread dumps of that
     * state show the gametest framework's own client/server synchronizers parked forever on
     * unreleased semaphores (nothing Iustitia touches is in the deadlock, and a forced
     * disconnect does not release them). Every later scenario then cascade-fails on "Cannot
     * create a world when a server is running" at ~2 min apiece, so continuing is pure
     * waste -- [suiteFatal] is set and [runAll] aborts the suite instead. The runner's
     * incomplete-run guard reports the aborted run honestly (exit 1, no report clobbered).
     */
    private fun awaitServerTeardown(scenarioName: String) {
        try {
            val deadline = System.currentTimeMillis() + SERVER_TEARDOWN_TIMEOUT_MS
            val graceDeadline = System.currentTimeMillis() + SERVER_TEARDOWN_GRACE_MS
            var intervened = false
            while (System.currentTimeMillis() < deadline) {
                if (!ClientThread.computeOnClient { mc -> mc.server != null || mc.isIntegratedServerRunning }) return
                if (!intervened && System.currentTimeMillis() >= graceDeadline) {
                    intervened = true
                    println("[iustitia-selftest] WARN teardown barrier: server still running after '$scenarioName' closed -- forcing disconnect")
                    ClientThread.runOnClient { mc -> mc.disconnectWithSavingScreen() }
                }
                Thread.sleep(SERVER_TEARDOWN_POLL_MS)
            }
            println("[iustitia-selftest] WARN teardown barrier: gave up waiting for the server after '$scenarioName' (${SERVER_TEARDOWN_TIMEOUT_MS / 1000}s) -- later scenarios may fail to create worlds")
            suiteFatal = ("integrated server frozen after '$scenarioName' teardown (fabric-client-gametest-api-v1 network-sync deadlock; " +
                "client restart required) -- aborting the suite, re-run to retry")
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /** Collect observed VL/alerts and check them against the declared expectations. */
    private fun evaluate(builder: ScenarioBuilder): EvalResult {
        val vl = mutableMapOf<String, Double>()
        val alerted = mutableSetOf<String>()
        val missed = mutableListOf<String>()
        val fps = mutableListOf<String>()

        for ((uuid, checkId, mustAlert) in builder.expectations) {
            val isAlerted = checkId in SelfTestHooks.alertedChecksFor(uuid)
            val peak = SelfTestHooks.peakVlFor(uuid)[checkId] ?: 0.0
            if (peak > (vl[checkId] ?: 0.0)) vl[checkId] = peak
            if (isAlerted) alerted.add(checkId)
            if (mustAlert && !isAlerted) missed.add(checkId)
            if (!mustAlert && isAlerted) fps.add(checkId)
        }

        // Known-open findings: reported every run, never part of the verdict. If one starts
        // alerting, say so explicitly -- that is the cue to promote it to a real expectation.
        val knownOpen = mutableListOf<String>()
        for ((uuid, checkId, note) in builder.knownOpen) {
            val isAlerted = checkId in SelfTestHooks.alertedChecksFor(uuid)
            val peak = SelfTestHooks.peakVlFor(uuid)[checkId] ?: 0.0
            if (peak > (vl[checkId] ?: 0.0)) vl[checkId] = peak
            knownOpen.add(
                if (isAlerted) "KNOWN-OPEN CLOSED: '$checkId' alerted (peakVL=$peak) -- promote it to expect(mustAlert = true). Note: $note"
                else "KNOWN-OPEN: '$checkId' did not alert (peakVL=$peak). Note: $note"
            )
        }

        // Verified detector false positives: reported loudly, counted, non-blocking unless
        // --strict. If one STOPS firing the entry says so, which is the cue to delete it.
        for ((uuid, checkId, note) in builder.documentedFp) {
            val isAlerted = checkId in SelfTestHooks.alertedChecksFor(uuid)
            val peak = SelfTestHooks.peakVlFor(uuid)[checkId] ?: 0.0
            if (peak > (vl[checkId] ?: 0.0)) vl[checkId] = peak
            knownOpen.add(
                if (isAlerted) "FALSE POSITIVE (documented): '$checkId' fired on legitimate play (peakVL=$peak). Note: $note"
                else "FALSE POSITIVE (resolved?): '$checkId' no longer fires (peakVL=$peak) -- delete this entry. Note: $note"
            )
        }

        // Harness gaps: the drive never reached the check. The peak VL is included because it
        // tells a reviewer which of the two failure modes this is -- 0.00 the check was never
        // asked, >0 it reacted and the driver needs amplitude/sequencing, not a rewrite.
        val driveGaps = mutableListOf<String>()
        for ((uuid, checkId, note) in builder.driveGaps) {
            val peak = SelfTestHooks.peakVlFor(uuid)[checkId] ?: 0.0
            if (peak > (vl[checkId] ?: 0.0)) vl[checkId] = peak
            // "%.2f" so the entry never leaks double noise like 1.0000000000000027 into a report.
            val reached = if (peak > 0.0) "reacted (peakVL=${"%.2f".format(peak)})" else "never logged a flag"
            driveGaps.add("DRIVE GAP: '$checkId' $reached. Note: $note")
        }
        // Recurrence assertions: did the check alert MORE THAN ONCE? A check whose episode latch
        // never re-arms passes every verdict assertion above (episode 1 satisfies them) while being
        // effectively one-shot per session, so this bucket is what makes that visible. A miss here is
        // a hard failure, like `missed` -- unlike knownOpen/documentedFp it is not a documented
        // finding, it is a regression test that did not pass.
        val countMisses = mutableListOf<String>()
        val observedCounts = mutableMapOf<String, Int>()
        for (e in builder.alertCounts) {
            val observed = SelfTestHooks.alertCountFor(e.bot, e.checkId, e.label)
            val peak = SelfTestHooks.peakVlFor(e.bot)[e.checkId] ?: 0.0
            if (peak > (vl[e.checkId] ?: 0.0)) vl[e.checkId] = peak
            observedCounts["${e.checkId}[${e.label}]"] = observed
            if (observed < e.atLeast) {
                countMisses.add(
                    "ALERT COUNT: '${e.checkId}' [${e.label}] crossed its setback $observed time(s), " +
                        "expected >= ${e.atLeast} (peakVL=${"%.2f".format(peak)}). " +
                        "The check alerted but never RE-ARMED; the episode latch is still held from the first episode."
                )
            }
        }

        return EvalResult(vl, alerted, missed.toSet(), fps.toSet(), knownOpen, driveGaps, observedCounts, countMisses)
    }

    private data class EvalResult(
        val vl: Map<String, Double>,
        val alerted: Set<String>,
        val missed: Set<String>,
        val falsePositives: Set<String>,
        val knownOpen: List<String>,
        val driveGaps: List<String>,
        /** Observed alert episodes, keyed `checkId[label]`. */
        val alertCounts: Map<String, Int>,
        /** Recurrence assertions that did not reach their required episode count. */
        val alertCountMisses: List<String>,
    )

    /**
     * TEMPORARY drive diagnostic (delete before merge): print what the tracker actually sees for
     * [bot] so a drive gap can be attributed to the drive instead of guessed at.
     */
    fun probe(label: String, bot: BotHandle, extra: String = "") {
        try {
            val s = ClientThread.computeOnClient { _ ->
                val tp = dev.iustitia.tracking.EntityTrackerManager.get(bot.uuid)
                if (tp == null) "untracked" else buildString {
                    append("sprint=").append(tp.sprinting)
                    append(" ground=").append(tp.onGroundPacket)
                    append(" gproxy=").append(tp.groundedProxy)
                    append(" dY=").append("%.3f".format(tp.deltaY))
                    append(" dxz=").append("%.3f".format(kotlin.math.hypot(tp.delta.x, tp.delta.z)))
                    append(" yaw=").append("%.1f".format(tp.yaw))
                    append(" pos=").append("%.2f,%.2f,%.2f".format(tp.pos.x, tp.pos.y, tp.pos.z))
                    append(" lastAtk=").append(tp.lastAttackTick)
                    append(" hurt=").append(tp.hurtTick)
                    append(" velTick=").append(tp.velocityTick)
                    append(" tpTick=").append(tp.lastTeleportTick)
                    append(" using=").append(tp.usingItem)
                    append(" blocking=").append(tp.isBlocking)
                    append(" tick=").append(dev.iustitia.Iustitia.tickCounter)
                }
            }
            println("[probe $label] $s $extra")
        } catch (_: Throwable) {
        }
    }

    /**
     * The two-pass runner: runs the scenarios in order and returns all reports. A
     * scenario that throws yields a failed report instead of aborting the run, so one
     * bad scenario never masks the rest. The one exception is a frozen integrated server
     * (see [awaitServerTeardown] / [suiteFatal]): once the teardown barrier gives up, every
     * later scenario is guaranteed to cascade-fail, so the suite aborts after logging that
     * scenario's own FAIL line -- the runner's incomplete-run guard surfaces it as NOT RUN.
     */
    fun runAll(ctx: ClientGameTestContext, scenarios: List<Scenario>): List<ScenarioReport> {
        silenceClientAudio(ctx)
        val reports = mutableListOf<ScenarioReport>()
        for (s in scenarios) {
            val r = runScenario(ctx, s)
            reports.add(r)
            val tag = if (r.passed) "PASS" else "FAIL"
            println("[iustitia-selftest] $tag ${s.pass}/${s.name} missed=${r.missedChecks} fps=${r.falsePositives} err=${r.error}")
            for (entry in r.alertCountMisses) println("[iustitia-selftest]   $entry")
            for (entry in r.knownOpen) println("[iustitia-selftest]   $entry")
            for (entry in r.driveGaps) println("[iustitia-selftest]   $entry")
            suiteFatal?.let { fatal ->
                println("[iustitia-selftest] ABORT $fatal")
                throw IllegalStateException(fatal)
            }
        }
        return reports
    }

    /**
     * Mute the client this run owns.
     *
     * A live suite that blasts game audio for the several minutes it takes to boot, create a world
     * and run seventy scenarios is unusable for whoever is sitting next to it -- and the entire
     * point of this harness is that it runs unattended, repeatedly, while someone works. Modern
     * Minecraft has no `--nosound` launch argument, so the mute goes through the same API the sound
     * options screen uses: every [SoundCategory] volume is driven to 0.0, then the live mixer is
     * refreshed and whatever is already playing is stopped. The refresh-and-stop matters because a
     * sound instance that has already started would otherwise keep playing out its own length.
     *
     * Deliberately **in-memory only**: options are never written back to disk, so this cannot change
     * the contributor's own client settings. Fail-open, like the rest of the harness: an audio
     * problem must never fail a detection run -- it prints a line and carries on.
     */
    fun silenceClientAudio(ctx: ClientGameTestContext) {
        try {
            ClientThread.bind(ctx)
            try {
                ClientThread.runOnClient { mc ->
                    try {
                        val categories = SoundCategory.values()
                        val volumes = categories.map { mc.options.getSoundVolumeOption(it) }
                        // Idempotent: this is called from both the entrypoint (before anything that
                        // can throw) and runAll (API-level guarantee), and the second call must not
                        // re-log or re-stop anything. If every category is already mute, we are done.
                        if (volumes.all { it.value == 0.0 }) return@runOnClient
                        for (volume in volumes) volume.setValue(0.0)
                        val sounds = mc.soundManager
                        for (category in categories) sounds.refreshSoundVolumes(category)
                        sounds.stopAll()
                        mc.musicTracker.stop()
                        println(
                            "[iustitia-selftest] audio muted: ${categories.size} SoundCategory " +
                                "volumes = 0.0, mixer refreshed, playing sounds stopped (in-memory only)"
                        )
                    } catch (t: Throwable) {
                        println("[iustitia-selftest] audio mute failed (${t.javaClass.simpleName}); the run may be audible")
                    }
                }
            } finally {
                ClientThread.unbind()
            }
        } catch (t: Throwable) {
            println("[iustitia-selftest] audio mute skipped: ${t.javaClass.simpleName}")
        }
    }

    /** Print the aggregate JSON report for scripts/live_selftest.py to parse. */
    fun printReport(reports: List<ScenarioReport>) {
        println("IUSTITIA_SELFTEST_REPORT=${jsonArray(reports.map { it.toJson() })}")
    }

    /**
     * Print the scenario/check inventory for the runner's offline list + coverage modes.
     *
     * Emitted BEFORE the scenarios run (so it survives a mid-suite crash), and sourced from
     * the live check registry ([Iustitia.allChecks]) rather than a hand-maintained list -- a
     * newly registered check appears here the moment it is wired up, which is what makes the
     * coverage report trustworthy.
     *
     * Expectations are not known until a scenario's body runs, so they arrive in the report
     * JSON instead; the runner merges the two into `build/selftest-manifest.json`.
     */
    fun printManifest(scenarios: List<Scenario>) {
        val checks = try { Iustitia.allChecks.map { it.id }.sorted() } catch (_: Throwable) { emptyList() }
        val body = buildString {
            append("{\"checks\": ").append(jsonValue(checks))
            append(", \"scenarios\": ").append(jsonValue(scenarios.map { s ->
                mapOf(
                    "scenario" to s.name,
                    "pass" to s.pass,
                    "source" to s.source,
                    "tags" to s.tags.sorted(),
                )
            }))
            append("}")
        }
        println("IUSTITIA_SELFTEST_MANIFEST=$body")
    }

    /** Minimal JSON writer for the report/manifest (no dependency on the mod's Gson). */
    internal fun jsonArray(items: List<Map<String, Any?>>): String = jsonValue(items)

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is Collection<*> -> v.joinToString(",", "[", "]") { q -> jsonValue(q) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k2, v2) -> "\"$k2\": " + jsonValue(v2) }
        else -> "\"$v\""
    }
}
