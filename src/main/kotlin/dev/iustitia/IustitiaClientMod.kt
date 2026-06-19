package dev.iustitia

import dev.iustitia.checks.combat.AutoBlockCheck
import dev.iustitia.checks.combat.BacktrackCheck
import dev.iustitia.checks.combat.ClickStatisticsCheck
import dev.iustitia.checks.combat.CriticalsCheck
import dev.iustitia.checks.combat.HitFlickCheck
import dev.iustitia.checks.combat.JumpOnHurtCheck
import dev.iustitia.checks.combat.KeepSprintCheck
import dev.iustitia.checks.combat.KillAuraCheck
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
import dev.iustitia.checks.movement.StepHeightCheck
import dev.iustitia.checks.movement.TeleportCheck
import dev.iustitia.checks.movement.TimerRateCheck
import dev.iustitia.checks.movement.WaterWalkCheck
import dev.iustitia.command.IustitiaCommand
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

        // Full check registry — combat (14) + movement/rotation/packet (18).
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
        Iustitia.register(SpeedEnvelopeCheck())
        Iustitia.register(FlyEnvelopeCheck())
        Iustitia.register(NoFallDamageCheck())
        Iustitia.register(StepHeightCheck())
        Iustitia.register(TeleportCheck())
        Iustitia.register(LongJumpCheck())
        Iustitia.register(NoSlowCheck())
        Iustitia.register(BackwardSprintCheck())
        Iustitia.register(WaterWalkCheck())
        Iustitia.register(ElytraSpeedCheck())
        Iustitia.register(RotationTrackingCheck())
        Iustitia.register(RotationSnapBackCheck())
        Iustitia.register(PhaseClipCheck())
        Iustitia.register(PacketGapCheck())
        Iustitia.register(TimerRateCheck())
        Iustitia.register(AimWrapCheck())
        Iustitia.register(PitchBoundCheck())
        Iustitia.register(ScaffoldRotationCheck())

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick {
            tick++
            Iustitia.onClientTick(tick)
        })

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            IustitiaCommand.register(dispatcher)
        }
    }
}