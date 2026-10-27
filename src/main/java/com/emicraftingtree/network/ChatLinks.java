package com.emicraftingtree.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import com.emicraftingtree.EmiCraftingTree;

/**
 * NEI's item chat links (Ctrl + L): the server posts "&lt;Player&gt; Click to add bookmark: [Item]" and sends the item
 * to every client with this mod, where clicking the link bookmarks it. The channel is optional on both sides, so like
 * NEI the key simply does nothing on servers without the mod.
 */
public final class ChatLinks {

    public static final String COMMAND = "emicraftingtree_bookmark";

    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(EmiCraftingTree.MOD_ID, "main"))
            .networkProtocolVersion(() -> VERSION)
            .clientAcceptedVersions(version -> true)
            .serverAcceptedVersions(version -> true)
            .simpleChannel();

    private ChatLinks() {}

    /** An item link sent by a player. */
    public record LinkC2S(ItemStack stack, String recipeId) {

        static void encode(LinkC2S message, FriendlyByteBuf buf) {
            buf.writeItem(message.stack);
            buf.writeUtf(message.recipeId);
        }

        static LinkC2S decode(FriendlyByteBuf buf) {
            return new LinkC2S(buf.readItem(), buf.readUtf());
        }
    }

    /** The linked item, cached by every client until its chat link is clicked. */
    public record CacheS2C(String payloadId, ItemStack stack, String recipeId) {

        static void encode(CacheS2C message, FriendlyByteBuf buf) {
            buf.writeUtf(message.payloadId);
            buf.writeItem(message.stack);
            buf.writeUtf(message.recipeId);
        }

        static CacheS2C decode(FriendlyByteBuf buf) {
            return new CacheS2C(buf.readUtf(), buf.readItem(), buf.readUtf());
        }
    }

    public static void init() {
        CHANNEL.messageBuilder(LinkC2S.class, 0, NetworkDirection.PLAY_TO_SERVER).encoder(LinkC2S::encode)
                .decoder(LinkC2S::decode).consumerMainThread(ChatLinks::onLink).add();
        CHANNEL.messageBuilder(CacheS2C.class, 1, NetworkDirection.PLAY_TO_CLIENT).encoder(CacheS2C::encode)
                .decoder(CacheS2C::decode).consumerMainThread(ChatLinks::onCache).add();
    }

    private static void onLink(LinkC2S message, Supplier<NetworkEvent.Context> context) {
        final ServerPlayer sender = context.get().getSender();

        if (sender == null || message.stack.isEmpty()) {
            return;
        }

        final String payloadId = UUID.randomUUID().toString().substring(0, 8);
        CHANNEL.send(PacketDistributor.ALL.noArg(), new CacheS2C(payloadId, message.stack, message.recipeId));

        final MutableComponent clickable = message.stack.getDisplayName().copy();
        clickable.setStyle(clickable.getStyle()
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + COMMAND + " " + payloadId)));

        final Component text = Component.literal("<").append(sender.getDisplayName()).append("> ")
                .append(Component.literal("Click to add bookmark: ").withStyle(ChatFormatting.GOLD))
                .append(clickable);

        sender.server.getPlayerList().broadcastSystemMessage(text, false);
    }

    private static void onCache(CacheS2C message, Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ChatLinksClient.cache(message.payloadId, message.stack, message.recipeId));
    }

}
