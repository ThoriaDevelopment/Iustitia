package dev.iustitia

import dev.iustitia.checks.combat.AutoBlockCheck
import dev.iustitia.checks.combat.BacktrackCheck
import dev.iustitia.checks.combat.ClickStatisticsCheck
import dev.iustitia.checks.combat.CriticalsCheck
import dev.iustitia.checks.combat.HitFlickCheck
import dev.iustitia.checks.combat.HitsWithoutSwingCheck
import dev.iustitia.checks.combat.JumpOnHurtCheck
import dev.iustitia.checks.combat.KeepSprintCheck
import dev.iustitia.checks.combat.KillAuraCheck
import dev.iustitia.checks.combat.MaceSmashCheck
import dev.iustitia.checks.combat.MultiTargetCheck
import dev.iustitia.checks.combat.NoKnockbackCheck
import dev.iustitia.checks.combat.ReachCheck
import dev.iustitia.checks.combat.ThroughWallsCheck
import dev.iustitia.checks.combat.TriggerbotCheck
import dev.iustitia.checks.combat.WTapCheck
import dev.iustitia.checks.movement.AimWrapCheck
import dev.iustitia.checks.movement.BackwardSprintCheck
import dev.iustitia.checks.movement.ElytraSpeedCheck
import dev.iustitia.checks.movement.FlyEnvelopeCheck
import dev.iustitia.checks.movement.LongJumpCheck
import dev.iustitia.checks.movement.NoFallDamageCheck
import dev.iustitia.checks.movement.NoSlowCheck
import dev.iustitia.checks.movement.PacketGapCheck
import dev.iustitia.checks.movement.PhaseClipCheck
import dev.iustitia.checks.movement.PitchBoundCheck
import dev.iustitia.checks.movement.RotationSnapBackCheck
import dev.iustitia.checks.movement.RotationTrackingCheck
import dev.iustitia.checks.movement.ScaffoldRotationCheck
import dev.iustitia.checks.movement.SpeedEnvelopeCheck
import dev.iustitia.checks.movement.SpiderCheck
import dev.iustitia.checks.movement.SprintHackCheck
import dev.iustitia.checks.movement.StepHeightCheck
import dev.iustitia.checks.movement.TeleportCheck
import dev.iustitia.checks.movement.WallSprintCheck
import dev.iustitia.checks.movement.WaterWalkCheck
import dev.iustitia.command.IustitiaCommand
import dev.iustitia.config.ConfigManager
import dev.iustitia.keybind.Keybinds
import dev.iustitia.ui.SetupWizardScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Client entrypoint. Wires the check registry to the [Iustitia] facade, drives the
 * per-tick pipeline from `ClientTickEvents.END_CLIENT_TICK`, and registers the
 * `/iustitia` command. The mixin (loaded by `iustitia.mixins.json`) feeds packet
 * signals into the same facade independently.
 */
class IustitiaClientMod : ClientModInitializer {

    companion object {
        @Volatile
        var tick: Int = 0
    }

    override fun onInitializeClient() {
        Iustitia.init()

        // Phase 2 keybinds (snapshot/transcript/session/keybinds/config/note/compact/watch-stub).
        // Registered with Fabric so they appear in Controls → Iustitia; polled each tick below.
        try { Keybinds.register() } catch (_: Throwable) {}

        // Phase B HUD overlays (crosshair confidence panel + server-lag indicator) via the stable
        // HudRenderCallback — no mixin, launch-safe, display-only. Gated by their own config toggles.
        try { dev.iustitia.hud.HudOverlay.register() } catch (_: Throwable) {}

        // Phase B offender-selfie: hooks WorldRenderEvents.END_MAIN to grab the framebuffer on the
        // one frame the CameraMixin repoints the camera at the offender. (The CameraMixin itself is
        // wired via iustitia.mixins.json.)
        try { dev.iustitia.render.SelfieRenderer.register() } catch (_: Throwable) {}

        // Phase B on-world target highlight: tier-colored wireframe box around the crosshair-target
        // player, drawn at WorldRenderEvents.AFTER_ENTITIES (depth-tested, no wallhack).
        try { dev.iustitia.render.TargetHighlightRenderer.register() } catch (_: Throwable) {}

        // Phase B ghost trail: fading breadcrumb trail of recent positions for suspect players
        // (sampled on END_CLIENT_TICK, drawn on AFTER_ENTITIES; depth-tested, no wallhack).
        try { dev.iustitia.render.GhostTrailRenderer.register() } catch (_: Throwable) {}

        // Phase 2 instant-replay renderer: while a replay (/ius replay or /ius playclip) is active,
        // draws translucent ghost copies of every player at the playhead frame's buffered positions
        // (AFTER_ENTITIES, depth-tested) + a bottom-center progress HUD. The hide-live mixin (in
        // iustitia.mixins.json) suppresses the real players during a hide-live replay. Render-only.
        try { dev.iustitia.render.ReplayRenderer.register() } catch (_: Throwable) {}

        // Full check registry — combat (16) + movement/rotation/packet (20).
        Iustitia.register(ReachCheck())
        Iustitia.register(MultiTargetCheck())
        Iustitia.register(ClickStatisticsCheck())
        Iustitia.register(ThroughWallsCheck())
        Iustitia.register(CriticalsCheck())
        Iustitia.register(NoKnockbackCheck())
        Iustitia.register(KeepSprintCheck())
        Iustitia.register(WTapCheck())
        Iustitia.register(JumpOnHurtCheck())
        Iustitia.register(BacktrackCheck())
        // Rain-Anticheat 1.8.9 ports — silent-aim suite + autoblock.
        Iustitia.register(KillAuraCheck())
        Iustitia.register(AutoBlockCheck())
        // Vape/Slinky ports — hit-flick (knockback redirect).
        Iustitia.register(HitFlickCheck())
        // Triggerbot — lax sub-reaction auto-attack detector (modern + 1.8 combat).
        Iustitia.register(TriggerbotCheck())
        // MaceSmash — MaceKill fall-height Y-warp around a mace attack (modern MC, §7.1).
        Iustitia.register(MaceSmashCheck())
        // HitsWithoutSwing — Slinky Hit Select / Grim PacketOrderB (no-swing attack; CORROBORATOR).
        Iustitia.register(HitsWithoutSwingCheck())
        Iustitia.register(SpeedEnvelopeCheck())
        Iustitia.register(FlyEnvelopeCheck())
        // Spider — wall-climb (AvA Spider sustained + NCM ConstantClimb constant YSpeed).
        Iustitia.register(SpiderCheck())
        Iustitia.register(NoFallDamageCheck())
        Iustitia.register(StepHeightCheck())
        Iustitia.register(TeleportCheck())
        Iustitia.register(LongJumpCheck())
        Iustitia.register(NoSlowCheck())
        Iustitia.register(BackwardSprintCheck())
        // WallSprint / SprintHack — Grim SprintE (sprint-into-wall) + SprintG/B/D (water/sneak/blind).
        Iustitia.register(WallSprintCheck())
        Iustitia.register(SprintHackCheck())
        Iustitia.register(WaterWalkCheck())
        Iustitia.register(ElytraSpeedCheck())
        Iustitia.register(RotationTrackingCheck())
        Iustitia.register(RotationSnapBackCheck())
        Iustitia.register(PhaseClipCheck())
        Iustitia.register(PacketGapCheck())
        Iustitia.register(AimWrapCheck())
        Iustitia.register(PitchBoundCheck())
        Iustitia.register(ScaffoldRotationCheck())

        // Self-check: registered check ids == config slice ids. Catches a forgotten slice() branch
        // (which would otherwise fall to slice()'s silent safe-default) or an orphan config slice.
        // Logs only; runs after every register() above.
        Iustitia.verifyCheckRegistry()

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick {
            tick++
            Iustitia.onClientTick(tick)
            try { Keybinds.poll() } catch (_: Throwable) {}
        })

        // First-launch setup wizard: opens once (until wizardCompleted is stamped) on the first
        // tick the player is actually in-game. Guarded so it never fires from a non-foreground
        // context; a failure to open is non-fatal (the user can re-run /ius wizard).
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick {
            try {
                if (!ConfigManager.config.wizardCompleted) {
                    val mc = net.minecraft.client.MinecraftClient.getInstance()
                    if (mc.currentScreen == null && mc.world != null && mc.player != null) {
                        ConfigManager.config.wizardCompleted = true
                        try { ConfigManager.save() } catch (_: Throwable) {}
                        mc.execute { try { mc.setScreen(SetupWizardScreen(null)) } catch (_: Throwable) {} }
                    }
                }
            } catch (_: Throwable) {}
        })

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            IustitiaCommand.register(dispatcher)
        }
    }
}