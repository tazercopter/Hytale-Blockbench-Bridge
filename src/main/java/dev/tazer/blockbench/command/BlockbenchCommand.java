package dev.tazer.blockbench.command;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import dev.tazer.blockbench.BlockbenchPlugin;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public class BlockbenchCommand extends AbstractAsyncCommand {
    public BlockbenchCommand() {
        super("blockbench", "Create an authentication key for a Blockbench client");
        setPermissionGroup(GameMode.Creative);
        addAliases("bb", "bb key", "bb auth", "blockbench key", "blockbench auth");
    }

    @Nonnull
    @Override
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext commandContext) {
        return CompletableFuture.runAsync(() -> {
            String key = BlockbenchPlugin.getBridge().generateKey(commandContext.sender().getDisplayName());
            commandContext.sendMessage(Message.raw("Input key (" + key + ") in Blockbench to connect"));
        });
    }
}
