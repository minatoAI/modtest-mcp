package io.github.minatoai.modtest.forge.mixin;

import io.github.minatoai.modtest.forge.ModtestHarnessMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects after vanilla has computed this tick's player input.
 *
 * <p>The write happens <b>after</b> {@code Input.tick(ZF)V} inside {@code LocalPlayer.aiStep}: that
 * is the last point where an injected value survives to be used by movement in the same tick. The
 * injection never decides permission — it calls into the harness, which goes through
 * {@code Guard.GuardedInputWriter} and therefore refuses remote sessions before touching anything.
 */
@Mixin(LocalPlayer.class)
public abstract class InputInjectionMixin {

    @Inject(
            method = "aiStep",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/Input;tick(ZF)V",
                    shift = At.Shift.AFTER))
    private void modtest$afterVanillaInput(CallbackInfo ci) {
        ModtestHarnessMod.onAiStepAfterInput(Minecraft.getInstance());
    }
}
