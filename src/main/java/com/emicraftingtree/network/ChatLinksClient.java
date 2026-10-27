package com.emicraftingtree.network;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;

import com.emicraftingtree.bookmark.ItemShortcuts;

/**
 * Client side of NEI's chat links: sending the hovered item, caching received links and the command their chat text
 * runs when clicked.
 */
public final class ChatLinksClient {

    private static final int MAX_CACHED = 64;

    private record Payload(ItemStack stack, String recipeId) {}

    private static final Map<String, Payload> CACHE = new LinkedHashMap<>() {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Payload> eldest) {
            return size() > MAX_CACHED;
        }
    };

    private ChatLinksClient() {}

    /** Sends an item link, if the server has the link channel (NEI needs its server side too). */
    public static boolean send(ItemStack stack, String recipeId) {
        final Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.getConnection() == null || stack.isEmpty()
                || !ChatLinks.CHANNEL.isRemotePresent(minecraft.getConnection().getConnection())) {
            return false;
        }

        ChatLinks.CHANNEL.sendToServer(new ChatLinks.LinkC2S(stack.copy(), recipeId));
        return true;
    }

    static void cache(String payloadId, ItemStack stack, String recipeId) {
        CACHE.put(payloadId, new Payload(stack, recipeId));
    }

    public static void registerCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ChatLinks.COMMAND)
                .then(Commands.argument("id", StringArgumentType.word()).executes(context -> {
                    final Payload payload = CACHE.get(StringArgumentType.getString(context, "id"));

                    if (payload != null && ItemShortcuts.addLinkedBookmark(payload.stack, payload.recipeId)) {
                        Minecraft.getInstance().gui.getChat().addMessage(
                                Component.translatable("emicraftingtree.chat.bookmark_added"));
                    }

                    return 1;
                })));
    }

}
