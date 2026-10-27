package com.emicraftingtree.selftest.benchmixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.GameRenderer;

import com.emicraftingtree.selftest.FrameTimer;

/** Dev-only: times every frame for the benchmark. */
@Mixin(value = GameRenderer.class, remap = false)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void emicraftingtree$start(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        FrameTimer.frameStart();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void emicraftingtree$end(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        FrameTimer.frameEnd();
    }

}
