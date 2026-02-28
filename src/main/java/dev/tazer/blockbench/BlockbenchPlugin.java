package dev.tazer.blockbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.common.util.PathUtil;
import com.hypixel.hytale.common.util.PatternUtil;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.common.events.CommonAssetMonitorEvent;
import com.hypixel.hytale.server.core.io.ServerManager;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.tazer.blockbench.command.BlockbenchCommand;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.logging.Level;

public class BlockbenchPlugin extends JavaPlugin {
    private static BlockbenchPlugin instance;
    private static BlockbenchBridge bridge = null;

    public BlockbenchPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static BlockbenchPlugin get() {
        return instance;
    }

    public static BlockbenchBridge getBridge() {
        return bridge;
    }

    @Override
    protected void setup() {
        this.getCommandRegistry().registerCommand(new BlockbenchCommand());

        getLogger().at(Level.INFO).log("Setting up the Blockbench Bridge...");
        bridge = new BlockbenchBridge();
        getEventRegistry().register(CommonAssetMonitorEvent.class, BlockbenchPlugin::onCommonAssetsMonitor);
    }

    @Override
    protected void start() {
        ServerManager.get().waitForBindComplete();

        int port;
        try {
            port = ServerManager.get().getLocalOrPublicAddress().getPort();
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("Could not get the current local or public address of the server!");
            return;
        }

        bridge.createTCPListener(port);
        bridge.createTCPListener(8651);
//        bridge.createUDPListener();
    }

    @Override
    protected void shutdown() {
        if (bridge != null) {
            bridge.shutdown();
        }
    }

    private static void onCommonAssetsMonitor(CommonAssetMonitorEvent event) {
        if (bridge == null) return;

        JsonObject message = new JsonObject();
        message.addProperty("type", MessageType.UPDATE.value());
        message.addProperty("pack", event.getAssetPack());

        Path commonPath = AssetModule.get().getAssetPack(event.getAssetPack()).getRoot().resolve("Common");

        // TODO: manage empty directories too
        JsonArray added = new JsonArray();
        event.getCreatedOrModifiedFilesToLoad()
                .forEach(path -> {
                    Path relativePath = PathUtil.relativize(commonPath, path);
                    String name = PatternUtil.replaceBackslashWithForwardSlash(relativePath.toString());
                    added.add(name);
                });
        message.add("added", added);

        JsonArray removed = new JsonArray();
        event.getRemovedFilesToUnload()
                .forEach(path -> {
                    Path relativePath = PathUtil.relativize(commonPath, path);
                    String name = PatternUtil.replaceBackslashWithForwardSlash(relativePath.toString());
                    removed.add(name);
                });
        message.add("deleted", removed);

        bridge.getConnections().forEach((_, connection) ->
                connection.sendMessage(message)
        );

        if (!bridge.getConnections().isEmpty()) get().getLogger().at(Level.INFO).log("Updated %d blockbench client(s)", bridge.getConnections().size());
    }
}
