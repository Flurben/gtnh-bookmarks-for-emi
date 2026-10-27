package com.emicraftingtree.chain;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

/**
 * Handles items that leave something behind when crafted with (buckets, damageable tools). Kept behind an interface
 * so the chain math can run without a Minecraft environment.
 */
public interface ContainerResolver {

    ContainerResolver NONE = new ContainerResolver() {

        @Override
        public boolean hasContainerItem(ChainStack stack) {
            return false;
        }

        @Override
        public @Nullable ItemStack single(ChainStack stack) {
            return null;
        }

        @Override
        public boolean sameType(ChainStack stack, ItemStack itemStack) {
            return false;
        }

        @Override
        public ContainerItemResult use(ItemStack stack, long steps) {
            return new ContainerItemResult(stack, steps, null);
        }

        @Override
        public ItemStack withCount(ItemStack stack, int count) {
            return stack;
        }

        @Override
        public int getCount(ItemStack stack) {
            return 0;
        }
    };

    class ContainerItemResult {

        public @Nullable ItemStack stack;
        public @Nullable ItemStack containerItem;
        public long leftSteps;

        public ContainerItemResult(@Nullable ItemStack stack, long leftSteps, @Nullable ItemStack containerItem) {
            this.stack = stack;
            this.leftSteps = leftSteps;
            this.containerItem = containerItem;
        }
    }

    boolean hasContainerItem(ChainStack stack);

    /** A single working copy of the stack that can be "used up" by {@link #use}. */
    @Nullable
    ItemStack single(ChainStack stack);

    boolean sameType(ChainStack stack, ItemStack itemStack);

    /**
     * Uses the stack for up to {@code steps} crafts. Returns what is left of it (null when used up), how many steps
     * could not be covered, and the different item it turned into, if any (an empty bucket).
     */
    ContainerItemResult use(ItemStack stack, long steps);

    ItemStack withCount(ItemStack stack, int count);

    int getCount(ItemStack stack);

}
