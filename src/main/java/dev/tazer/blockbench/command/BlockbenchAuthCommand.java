package dev.tazer.blockbench.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import dev.tazer.blockbench.BlockbenchPlugin;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public class BlockbenchAuthCommand extends AbstractAsyncCommand {
    public BlockbenchAuthCommand() {
        super("auth", "Create an authentication key for a Blockbench client");
    }

    @Nonnull
    @Override
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext commandContext) {
        return CompletableFuture.runAsync(() -> {
            String key = BlockbenchPlugin.getBridge().generateKey(commandContext.sender().getDisplayName());
            commandContext.sendMessage(Message.raw("Your Blockbench key is: " + key));
        });
    }
}
