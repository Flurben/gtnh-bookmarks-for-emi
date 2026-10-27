package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.RecipeChainMath;
import com.emicraftingtree.chain.Stacks;

/**
 * Pulls the items a bookmark group needs out of an open storage container into the player inventory (NEI's
 * pullBookmarkItems with the default chest handler). With Shift only what the inventory is still missing is pulled.
 */
public final class ItemPuller {

    private ItemPuller() {}

    public static boolean isStorage(@Nullable Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return false;
        }

        final AbstractContainerMenu menu = containerScreen.getMenu();
        return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu
                || menu instanceof DispenserMenu;
    }

    public static boolean pull(@Nullable RecipeChainMath math, boolean onlyMissing) {
        final Minecraft minecraft = Minecraft.getInstance();
        final Player player = minecraft.player;

        if (math == null || player == null || minecraft.gameMode == null || !isStorage(minecraft.screen)) {
            return false;
        }

        final AbstractContainerMenu menu = ((AbstractContainerScreen<?>) minecraft.screen).getMenu();
        final Map<String, Long> inventory = onlyMissing ? count(menu, player, true) : new LinkedHashMap<>();
        final Map<String, Long> pull = new LinkedHashMap<>();

        if (math.outputRecipes.isEmpty()) {

            for (ChainItem item : math.initialItems) {
                final long amount = item.getAmount() - inventory.getOrDefault(item.stack.key, 0L);
                if (amount > 0) {
                    pull.merge(item.stack.key, amount, Long::sum);
                }
            }

        } else {
            final Map<String, Long> storage = count(menu, player, false);
            final List<ChainItem> initialItems = new ArrayList<>();

            for (ChainItem item : math.initialItems) {
                final long amount = item.getAmount() - inventory.getOrDefault(item.stack.key, 0L)
                        - storage.getOrDefault(item.stack.key, 0L);

                if (amount > 0) {
                    initialItems.add(item.copyWithAmount(amount));
                }
            }

            math.initialItems.clear();
            math.initialItems.addAll(initialItems);

            final Map<String, ChainItem> inventoryItems = new LinkedHashMap<>();
            final Map<String, ChainItem> storageItems = new LinkedHashMap<>();

            for (Slot slot : menu.slots) {
                final ItemStack stack = slot.getItem();

                if (!stack.isEmpty()) {
                    final boolean playerSlot = slot.container instanceof Inventory;
                    final Map<String, ChainItem> target = playerSlot ? inventoryItems : storageItems;

                    if (playerSlot && !onlyMissing) {
                        continue;
                    }

                    final String key = Stacks.keyOf(stack);
                    final ChainItem existing = target.get(key);

                    target.put(key, ChainItem.ofItem(playerSlot ? -1 : -2, Stacks.of(stack).withAmount(1),
                            (existing != null ? existing.multiplier : 0) + stack.getCount()));
                }
            }

            math.initialItems.addAll(inventoryItems.values());
            math.initialItems.addAll(storageItems.values());
            math.refresh();

            for (ChainItem item : math.initialItems) {
                final long required = math.requiredAmount.getOrDefault(item, -1L);

                if (item.groupId == -2 && required > 0) {
                    pull.merge(item.stack.key, required, Long::sum);
                }
            }
        }

        if (pull.isEmpty() || !menu.getCarried().isEmpty()) {
            return false;
        }

        boolean moved = false;

        for (Map.Entry<String, Long> entry : pull.entrySet()) {
            long remaining = entry.getValue();

            for (Slot slot : menu.slots) {
                if (remaining <= 0) {
                    break;
                }

                final ItemStack stack = slot.getItem();

                if (slot.container instanceof Inventory || stack.isEmpty() || !slot.mayPickup(player)
                        || !Stacks.keyOf(stack).equals(entry.getKey())) {
                    continue;
                }

                final int transfer = (int) Math.min(remaining, stack.getCount());
                moved |= transfer(menu, player, slot, transfer);
                remaining -= transfer;
            }
        }

        return moved;
    }

    private static Map<String, Long> count(AbstractContainerMenu menu, Player player, boolean playerSlots) {
        final Map<String, Long> counts = new LinkedHashMap<>();

        for (Slot slot : menu.slots) {
            final ItemStack stack = slot.getItem();

            if (!stack.isEmpty() && (slot.container instanceof Inventory) == playerSlots && slot.mayPickup(player)) {
                counts.merge(Stacks.keyOf(stack), (long) stack.getCount(), Long::sum);
            }
        }

        return counts;
    }

    /** Moves {@code amount} items from a container slot into the player inventory. */
    private static boolean transfer(AbstractContainerMenu menu, Player player, Slot source, int amount) {
        final Minecraft minecraft = Minecraft.getInstance();
        final int containerId = menu.containerId;

        if (amount >= source.getItem().getCount()) {
            minecraft.gameMode.handleInventoryMouseClick(containerId, source.index, 0, ClickType.QUICK_MOVE, player);
            return true;
        }

        final ItemStack template = source.getItem().copy();
        final Slot target = findTarget(menu, template);

        if (target == null) {
            return false;
        }

        minecraft.gameMode.handleInventoryMouseClick(containerId, source.index, 0, ClickType.PICKUP, player);

        for (int i = 0; i < amount && !menu.getCarried().isEmpty(); i++) {
            minecraft.gameMode.handleInventoryMouseClick(containerId, target.index, 1, ClickType.PICKUP, player);
        }

        if (!menu.getCarried().isEmpty()) {
            minecraft.gameMode.handleInventoryMouseClick(containerId, source.index, 0, ClickType.PICKUP, player);
        }

        return true;
    }

    private static @Nullable Slot findTarget(AbstractContainerMenu menu, ItemStack template) {
        Slot empty = null;

        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) {
                continue;
            }

            final ItemStack stack = slot.getItem();

            if (stack.isEmpty()) {
                if (empty == null) {
                    empty = slot;
                }
            } else if (ItemStack.isSameItemSameTags(stack, template) && stack.getCount() < stack.getMaxStackSize()) {
                return slot;
            }
        }

        return empty;
    }

}
