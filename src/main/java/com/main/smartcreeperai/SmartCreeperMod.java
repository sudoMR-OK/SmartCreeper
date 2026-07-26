package com.main.smartcreeperai;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartCreeperMod implements ModInitializer {
    public static final String MOD_ID = "smartcreeperai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Smart Creeper AI Mod Phase 1 Initialized (Spawn Inventory loaded)!");
    }
}
