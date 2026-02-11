package dev.tazer.blockbench;

import com.hypixel.hytale.server.core.asset.common.events.CommonAssetMonitorEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.tazer.blockbench.command.BlockbenchCommand;

import javax.annotation.Nonnull;
import java.util.logging.Level;

public class BlockbenchPlugin extends JavaPlugin {
    private static BlockbenchPlugin instance;
    private static BlockbenchBridge bridgeInstance = null;

    public BlockbenchPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static BlockbenchPlugin get() {
        return instance;
    }

    public static BlockbenchBridge getBridge() {
        return bridgeInstance;
    }

    @Override
    protected void setup() {
        this.getCommandRegistry().registerCommand(new BlockbenchCommand());

        getLogger().at(Level.INFO).log("Setting up the TCP bridge...");
        bridgeInstance = new BlockbenchBridge();
        getEventRegistry().register(CommonAssetMonitorEvent.class, BlockbenchPlugin::onCommonAssetsMonitor);
    }

    private static void onCommonAssetsMonitor(CommonAssetMonitorEvent event) {
        bridgeInstance.clients.forEach(((uuid, blockbenchClient) -> {
            blockbenchClient.getPacketHandler().sendFileTree();
        }));
    }
}
