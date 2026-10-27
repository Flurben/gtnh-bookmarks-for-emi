package com.emicraftingtree.selftest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.emicraftingtree.selftest.TestInput;
import com.mojang.blaze3d.platform.InputConstants;

/** Dev-only: keys held by the self-test read as pressed (the dev runtime uses official names, so no refmap). */
@Mixin(value = InputConstants.class, remap = false)
public abstract class InputConstantsMixin {

    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void emicraftingtree$heldByTest(long window, int key, CallbackInfoReturnable<Boolean> cir) {
        if (TestInput.isHeld(key)) {
            cir.setReturnValue(true);
        }
    }

}
