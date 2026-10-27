package com.emicraftingtree.chain;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import com.emicraftingtree.EmiCraftingTree;

/**
 * Container handling for real item stacks, based on the crafting remainder (Forge's
 * {@code getCraftingRemainingItem}): a water bucket leaves a bucket, a damageable tool comes back with more damage.
 */
public final class ItemContainerResolver implements ContainerResolver {

    public static final ItemContainerResolver INSTANCE = new ItemContainerResolver();

    /** Safety net for remainders that change NBT forever without ever breaking or repeating. */
    private static final int MAX_SIMULATED_STEPS = 100_000;

    private ItemContainerResolver() {}

    private static @Nullable ItemStack itemStack(ChainStack stack) {
        if (stack.emi == null || stack.fluid) {
            return null;
        }

        final ItemStack itemStack = stack.emi.getItemStack();
        return itemStack.isEmpty() ? null : itemStack;
    }

    private static ItemStack remainder(ItemStack stack) {
        try {
            return stack.hasCraftingRemainingItem() ? stack.getCraftingRemainingItem() : ItemStack.EMPTY;
        } catch (Throwable th) {
            EmiCraftingTree.LOGGER.debug("Failed to query crafting remainder of {}", stack, th);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public boolean hasContainerItem(ChainStack stack) {
        final ItemStack itemStack = itemStack(stack);
        return itemStack != null && !remainder(itemStack.copyWithCount(1)).isEmpty();
    }

    @Override
    public @Nullable ItemStack single(ChainStack stack) {
        final ItemStack itemStack = itemStack(stack);
        return itemStack != null ? itemStack.copyWithCount(1) : null;
    }

    @Override
    public boolean sameType(ChainStack stack, ItemStack itemStack) {
        return !itemStack.isEmpty() && stack.key.equals(Stacks.keyOf(itemStack));
    }

    @Override
    public ContainerItemResult use(ItemStack stack, long steps) {

        if (stack.isEmpty() || steps <= 0) {
            return new ContainerItemResult(stack.isEmpty() ? null : stack, Math.max(0, steps), null);
        }

        final ItemStack unit = stack.copyWithCount(1);
        final ItemStack remainder = remainder(unit);

        if (remainder.isEmpty() || remainder.getItem() != unit.getItem()) {
            // every craft eats one item and maybe leaves a different one behind (bucket of water -> bucket)
            final long used = Math.min(stack.getCount(), steps);
            final ItemStack left = stack.getCount() > used ? stack.copyWithCount((int) (stack.getCount() - used))
                    : null;
            final ItemStack container = remainder.isEmpty() ? null
                    : remainder.copyWithCount((int) Math.min(Integer.MAX_VALUE, remainder.getCount() * used));

            return new ContainerItemResult(left, steps - used, container);
        }

        if (unit.isDamageableItem() && remainder.isDamageableItem()) {
            final int damagePerCraft = remainder.getDamageValue() - unit.getDamageValue();

            if (damagePerCraft == 0) {
                return new ContainerItemResult(unit, 0, null);
            }

            if (damagePerCraft > 0) {
                final long maxDamage = unit.getMaxDamage();
                final long damage = unit.getDamageValue();
                final long leftSteps = (maxDamage - damage + damagePerCraft - 1) / damagePerCraft;
                final long availableSteps = Math.min(steps, Math.max(1, leftSteps));

                if (damage + availableSteps * damagePerCraft >= maxDamage || leftSteps <= 0) {
                    return new ContainerItemResult(null, steps - availableSteps, null);
                }

                final ItemStack used = unit.copy();
                used.setDamageValue((int) (damage + availableSteps * damagePerCraft));
                return new ContainerItemResult(used, steps - availableSteps, null);
            }
        }

        ItemStack current = unit;
        int simulated = 0;

        while (current != null && steps > 0) {
            final ItemStack next = remainder(current);
            steps--;

            if (next.isEmpty()) {
                current = null;
            } else if (next.getItem() != unit.getItem()) {
                return new ContainerItemResult(null, steps, next);
            } else if (ItemStack.matches(next, current) || ++simulated > MAX_SIMULATED_STEPS) {
                // comes back unchanged: it never wears out
                steps = 0;
            } else {
                current = next;
            }
        }

        return new ContainerItemResult(current, steps, null);
    }

    @Override
    public ItemStack withCount(ItemStack stack, int count) {
        return stack.copyWithCount(count);
    }

    @Override
    public int getCount(ItemStack stack) {
        return stack.getCount();
    }

}
