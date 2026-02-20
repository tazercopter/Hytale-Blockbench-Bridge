package dev.tazer.blockbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.common.util.PathUtil;
import com.hypixel.hytale.common.util.PatternUtil;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.common.events.CommonAssetMonitorEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;
import dev.tazer.blockbench.command.BlockbenchCommand;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.logging.Level;

public class BlockbenchPlugin extends JavaPlugin {
    private static BlockbenchPlugin instance;
    private static BlockbenchBridge bridgeInstance = null;
    private final Config<BlockbenchConfig> config = this.withConfig("BlockbenchConfig", BlockbenchConfig.CODEC);

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

    public BlockbenchConfig config() {
        return get().config.get();
    }

    @Override
    protected void setup() {
        this.getCommandRegistry().registerCommand(new BlockbenchCommand());

        config.save();
        getLogger().at(Level.INFO).log("Setting up the TCP bridge...");
        bridgeInstance = new BlockbenchBridge();
        getEventRegistry().register(CommonAssetMonitorEvent.class, BlockbenchPlugin::onCommonAssetsMonitor);
    }

    private static void onCommonAssetsMonitor(CommonAssetMonitorEvent event) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "update");
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

        bridgeInstance.clients.forEach((_, client) ->
                client.getPacketHandler().sendMessage(message)
        );

        if (!bridgeInstance.clients.isEmpty()) get().getLogger().at(Level.INFO).log("Updated %d blockbench client(s)", bridgeInstance.clients.size());
    }
}
