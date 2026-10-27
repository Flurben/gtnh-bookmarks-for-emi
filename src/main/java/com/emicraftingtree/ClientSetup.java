package com.emicraftingtree;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import com.emicraftingtree.bookmark.AutoCrafting;
import com.emicraftingtree.bookmark.BookmarkPanel;
import com.emicraftingtree.bookmark.BookmarkStorage;
import com.emicraftingtree.chain.RecipeKey;
import com.emicraftingtree.network.ChatLinksClient;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.screen.EmiScreenBase;

/**
 * Client-only wiring: config, key bindings, ticking (saving, auto-crafting) and the overlay drawn above EMI.
 */
public final class ClientSetup {

    private ClientSetup() {}

    public static void init() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientSetup::registerKeys);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, ClientSetup::onRenderPost);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onLoggingOut);
        MinecraftForge.EVENT_BUS.addListener(ChatLinksClient::registerCommand);

        RecipeKey.setResolver(new RecipeKey.Resolver() {

            @Override
            public @Nullable EmiRecipe resolve(String id) {
                if (!EmiReloadManager.isLoaded()) {
                    return null;
                }

                final ResourceLocation location = ResourceLocation.tryParse(id);
                return location != null ? EmiApi.getRecipeManager().getRecipe(location) : null;
            }

            @Override
            public int generation() {
                return BookmarkPanel.currentRecipeGeneration();
            }
        });
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        Keys.ALL.forEach(event::register);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        try {
            AutoCrafting.tick();
            BookmarkStorage.tick();
        } catch (RuntimeException e) {
            EmiCraftingTree.LOGGER.error("Bookmark tick failed", e);
        }
    }

    private static void onRenderPost(ScreenEvent.Render.Post event) {
        if (EmiScreenBase.of(event.getScreen()).isEmpty()) {
            return;
        }

        try {
            BookmarkPanel.renderOverlay(event.getScreen(), event.getGuiGraphics(), event.getMouseX(),
                    event.getMouseY(), Minecraft.getInstance().getFrameTime());
        } catch (RuntimeException e) {
            EmiCraftingTree.LOGGER.error("Failed to draw the bookmark overlay", e);
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AutoCrafting.stop();

        if (Config.worldSpecific()) {
            BookmarkStorage.unload();
        } else {
            BookmarkStorage.flush();
        }
    }

}
