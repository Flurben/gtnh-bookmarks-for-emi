package com.emicraftingtree;

import org.slf4j.Logger;

import com.emicraftingtree.network.ChatLinks;
import com.mojang.logging.LogUtils;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(EmiCraftingTree.MOD_ID)
public class EmiCraftingTree {

    public static final String MOD_ID = "emicraftingtree";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EmiCraftingTree() {
        ChatLinks.init();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init);
    }

}
