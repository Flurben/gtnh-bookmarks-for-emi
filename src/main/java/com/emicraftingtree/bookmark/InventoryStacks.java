package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.ChainStack;
import com.emicraftingtree.chain.Stacks;

/**
 * Counts what the player can use in the open container (NEI's AutoCraftingManager.getInventoryItems): every slot that
 * can be taken from, except crafting result slots.
 */
public final class InventoryStacks {

    public static final class Entry {

        public final ChainStack stack;
        public long amount;

        Entry(ChainStack stack, long amount) {
            this.stack = stack;
            this.amount = amount;
        }
    }

    private InventoryStacks() {}

    public static Map<String, Entry> of(@Nullable Screen screen) {
        return of(screen, null);
    }

    /** @param excluded a slot to leave out too (a crafting result slot some mods do not make a ResultSlot) */
    public static Map<String, Entry> of(@Nullable Screen screen, @Nullable Slot excluded) {
        final Map<String, Entry> result = new LinkedHashMap<>();
        final Player player = Minecraft.getInstance().player;

        if (player == null) {
            return result;
        }

        final AbstractContainerMenu menu = screen instanceof AbstractContainerScreen<?> containerScreen
                ? containerScreen.getMenu()
                : player.inventoryMenu;

        for (Slot slot : menu.slots) {
            final ItemStack stack = slot.getItem();

            if (!stack.isEmpty() && slot != excluded && !(slot instanceof ResultSlot) && slot.mayPickup(player)) {
                final ChainStack chainStack = Stacks.of(stack);
                result.computeIfAbsent(chainStack.key, key -> new Entry(chainStack.withAmount(1), 0)).amount +=
                        stack.getCount();
            }
        }

        return result;
    }

    /**
     * The inventory as the chain math should see it: a stack EMI treats as one of the chain's items or accepted
     * alternatives is counted under that item's key. EMI compares items without their NBT unless told otherwise, so a
     * worn, renamed or GregTech tool counts as the tool the recipe shows, exactly as EMI's recipe filling uses it.
     */
    public static Map<String, Entry> forChain(Map<String, Entry> inventory, Collection<ChainItem> items) {
        final Set<String> known = new HashSet<>();
        final Map<Item, List<ChainStack>> byItem = new HashMap<>();

        for (ChainItem chainItem : items) {
            for (ChainStack perm : chainItem.permutations.values()) {
                if (known.add(perm.key) && perm.emi != null && perm.emi.getKey() instanceof Item item) {
                    byItem.computeIfAbsent(item, k -> new ArrayList<>()).add(perm);
                }
            }
        }

        final Map<String, Entry> result = new LinkedHashMap<>();

        for (Entry entry : inventory.values()) {
            ChainStack stack = entry.stack;

            if (!known.contains(stack.key) && stack.emi != null) {
                final ItemStack itemStack = stack.emi.getItemStack();

                for (ChainStack perm : byItem.getOrDefault(itemStack.getItem(), List.of())) {
                    if (perm.emi.isEqual(stack.emi)) {
                        stack = perm.withAmount(1);
                        break;
                    }
                }
            }

            final ChainStack key = stack;
            result.computeIfAbsent(key.key, k -> new Entry(key, 0)).amount += entry.amount;
        }

        return result;
    }

    public static long count(Map<String, Entry> inventory, String key) {
        final Entry entry = inventory.get(key);
        return entry != null ? entry.amount : 0;
    }

}
