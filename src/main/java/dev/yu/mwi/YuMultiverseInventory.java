package dev.yu.mwi;

import com.mojang.logging.LogUtils;
import dev.yu.mwi.platform.MwiServerController;
import dev.yu.mwi.platform.NeoForgeEventHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** NeoForge entry point for the server-authoritative inventory isolation runtime. */
@Mod(YuMultiverseInventory.MOD_ID)
public final class YuMultiverseInventory {
    public static final String MOD_ID = "yu_multiverse_inventory";
    public static final Logger LOGGER = LogUtils.getLogger();

    public YuMultiverseInventory(IEventBus modEventBus) {
        MwiServerController controller = new MwiServerController(LOGGER);
        NeoForge.EVENT_BUS.register(new NeoForgeEventHandler(controller, LOGGER));
        NeoForge.EVENT_BUS.register(new dev.yu.mwi.platform.RecoveryProtectionEvents());
    }
}
