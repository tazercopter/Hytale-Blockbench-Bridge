package dev.tazer.blockbench;

import com.hypixel.hytale.server.core.auth.PlayerAuthentication;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.permissions.PermissionHolder;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public class BlockbenchClient implements PermissionHolder {
    private final UUID uuid;
    private final String username;
    private final PlayerAuthentication auth;
    private final BlockbenchPacketHandler packetHandler;

    public BlockbenchClient(PlayerAuthentication auth, BlockbenchPacketHandler packetHandler) {
        this.uuid = auth.getUuid();
        this.username = auth.getUsername();
        this.auth = auth;
        this.packetHandler = packetHandler;
    }

    public boolean hasPermission(@Nonnull String id) {
        return PermissionsModule.get().hasPermission(this.uuid, id);
    }

    public boolean hasPermission(@Nonnull String id, boolean def) {
        return PermissionsModule.get().hasPermission(this.uuid, id, def);
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public PlayerAuthentication getAuth() {
        return auth;
    }

    public BlockbenchPacketHandler getPacketHandler() {
        return packetHandler;
    }
}
