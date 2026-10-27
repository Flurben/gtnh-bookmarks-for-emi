package com.emicraftingtree.selftest.benchmixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.ForgeHooksClient;

import com.emicraftingtree.selftest.FrameTimer;

/** Dev-only: times the screen part of every frame (the screen, EMI and what mods draw over it) for the benchmark. */
@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class ForgeHooksClientMixin {

    @Inject(method = "drawScreen", at = @At("HEAD"))
    private static void emicraftingtree$start(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
            float partialTick, CallbackInfo ci) {
        FrameTimer.screenStart();
    }

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private static void emicraftingtree$end(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
            float partialTick, CallbackInfo ci) {
        FrameTimer.screenEnd();
    }

}
