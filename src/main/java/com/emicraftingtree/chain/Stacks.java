package com.emicraftingtree.chain;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

import dev.emi.emi.api.stack.EmiStack;

/**
 * Stack identity helpers. Two stacks are "the same type" when item/fluid and NBT match, ignoring tool durability
 * (NEI's areStacksSameTypeCraftingWithNBT).
 */
public final class Stacks {

    private Stacks() {}

    public static String keyOf(EmiStack stack) {
        final Object key = stack.getKey();
        CompoundTag nbt = stack.getNbt();

        if (key instanceof Item item) {
            return itemKey(item, nbt);
        }

        final String prefix = key instanceof Fluid ? "fluid:" : "other:";
        return prefix + stack.getId() + (nbt != null && !nbt.isEmpty() ? nbt.toString() : "");
    }

    public static String keyOf(ItemStack stack) {
        return itemKey(stack.getItem(), stack.getTag());
    }

    private static String itemKey(Item item, CompoundTag nbt) {

        if (nbt != null && item.canBeDepleted() && nbt.contains("Damage")) {
            nbt = nbt.copy();
            nbt.remove("Damage");
        }

        return "item:" + BuiltInRegistries.ITEM.getKey(item) + (nbt != null && !nbt.isEmpty() ? nbt.toString() : "");
    }

    public static boolean isFluid(EmiStack stack) {
        return stack.getKey() instanceof Fluid;
    }

    public static ChainStack of(EmiStack stack) {
        return of(stack, stack.getAmount());
    }

    public static ChainStack of(EmiStack stack, long amount) {
        return new ChainStack(keyOf(stack), amount, isFluid(stack), stack.copy().setAmount(1));
    }

    public static ChainStack of(ItemStack stack) {
        return new ChainStack(keyOf(stack), stack.getCount(), false, EmiStack.of(stack, 1));
    }

    /** EMI chances are floats in [0, 1]; the chain math works in parts of {@link ChainItem#CHANCE_FULL}. */
    public static long chance(float chance) {
        return Math.max(0, Math.round((double) chance * ChainItem.CHANCE_FULL));
    }

}
