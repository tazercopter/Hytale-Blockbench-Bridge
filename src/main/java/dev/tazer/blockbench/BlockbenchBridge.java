package dev.tazer.blockbench;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.auth.PlayerAuthentication;
import com.hypixel.hytale.server.core.io.ServerManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public class BlockbenchBridge {
    // TODO sort out all the error messages
    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<SocketAddress, BridgeSession> sessions = new ConcurrentHashMap<>();
    private final Map<SocketAddress, BlockbenchConnection> connections = new ConcurrentHashMap<>();
    private final Map<Instant, BlockbenchKey> keys = new ConcurrentHashMap<>();
    private final List<ServerSocket> serverSockets = new CopyOnWriteArrayList<>();
    private static final HytaleLogger LOGGER = BlockbenchPlugin.get().getLogger();

    public BlockbenchBridge() {
        if (BlockbenchPlugin.getBridge() != null) {
            throw new IllegalStateException("A Blockbench Bridge has already been built!");
        }
    }

    public String generateKey(String username, UUID uuid) {
        StringBuilder code = new StringBuilder(4);

        for (int i = 0; i < 4; i++) {
            int index = RANDOM.nextInt(CHARACTERS.length());
            code.append(CHARACTERS.charAt(index));
        }

        BlockbenchKey key = new BlockbenchKey(username, code.toString(), uuid);
        keys.put(Instant.now(), key);
        return key.getFullKey();
    }

    public Map<SocketAddress, BlockbenchConnection> getConnections() {
        return connections;
    }

    @Nullable
    public BridgeSession getSession(SocketAddress address) {
        return sessions.get(address);
    }

    public void disconnect(SocketAddress address) {
        sessions.remove(address);
        connections.remove(address);
    }

    public void shutdown() {
        serverSockets.forEach(ss -> {
            try { ss.close(); } catch (IOException ignored) {}
        });
        serverSockets.clear();
        new ArrayList<>(sessions.values()).forEach(BridgeSession::close);
    }

    protected void createTCPListener(int port) {
        ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).log("Error while starting the blockbench server socket: {}", String.valueOf(e));
            return;
        }
        serverSockets.add(serverSocket);

        Thread thread = new Thread(() -> {
            LOGGER.at(Level.INFO).log("TCP bridge listening on port %d", port);
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    LOGGER.at(Level.INFO).log("Received connection from: %s", socket.getRemoteSocketAddress());

                    Thread connThread = new Thread(() -> {
                        try {
                            TCPBridgeSession session = new TCPBridgeSession(socket);
                            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = in.readLine()) != null) {
                                    JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                                    handleMessage(session, json);
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.at(Level.WARNING).log("Unexpected error on connection %s: %s", socket.getRemoteSocketAddress(), e);
                        } finally {
                            try { socket.close(); } catch (IOException ignored) {}
                            LOGGER.at(Level.INFO).log("Connection closed: %s", socket.getRemoteSocketAddress());
                        }
                    });
                    connThread.setDaemon(true);
                    connThread.start();
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        LOGGER.at(Level.WARNING).log("Error accepting connection: {}", String.valueOf(e));
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    protected void createUDPListener() {
        for (Channel channel : ServerManager.get().getListeners()) {
            try {
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addFirst("bbchannel", new BridgeUDPChannelHandler());
                LOGGER.at(Level.FINE).log("Registered blockbench channel handler on %s", channel.localAddress());
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).withCause(e).log(
                        "Failed to register blockbench channel handler on %s", channel.localAddress());
            }
        }
    }

    public void handleMessage(BridgeSession session, JsonObject message) {
        SocketAddress address = session.getAddress();

        String typeStr = message.has("type") ? message.get("type").getAsString() : null;

        if (typeStr == null) {
            session.close();
            LOGGER.at(Level.WARNING).log("Missing packet type from connection %s", address);
            return;
        }

        MessageType type = MessageType.fromString(typeStr);
        if (type == null) {
            session.close();
            LOGGER.at(Level.WARNING).log("Unknown packet type '%s' from connection %s", typeStr, address);
            return;
        }

        switch (type) {
            case CREATE -> {
                LOGGER.at(Level.INFO).log("Starting authentication flow for connection %s", address);

                String key = message.has("key") ? message.get("key").getAsString() : null;
                if (key == null || key.isEmpty()) {
                    session.close();
                    LOGGER.at(Level.WARNING).log("Missing blockbench key from connection %s", address);
                    return;
                }

                PlayerAuthentication authentication = null;
                for (Map.Entry<Instant, BlockbenchKey> entry : keys.entrySet()) {
                    Instant instant = entry.getKey();
                    if (Duration.between(instant, Instant.now()).getSeconds() > 360) {
                        keys.remove(instant);
                        continue;
                    }

                    if (key.equals(entry.getValue().getFullKey())) {
                        authentication = new PlayerAuthentication(UUID.randomUUID(), entry.getValue().username());
                        keys.remove(instant);
                        break;
                    }
                }

                if (authentication == null) {
                    session.close();
                    LOGGER.at(Level.WARNING).log("Could not validate authentication for Blockbench connection %s", address);
                    return;
                }

                LOGGER.at(Level.INFO).log("Authentication validated for connection %s", address);

                BridgeSession existing = sessions.get(address);
                if (existing != null) {
                    LOGGER.at(Level.INFO).log("Replacing existing session at %s", address);
                    existing.close();
                }

                BlockbenchConnection connection = new BlockbenchConnection(session, authentication);
                sessions.put(address, session);
                connections.put(session.getAddress(), connection);

                LOGGER.at(Level.INFO).log("Blockbench connection %s (UUID: %s) established!", authentication.getUsername(), authentication.getUuid());

                JsonObject response = new JsonObject();
                response.addProperty("type", MessageType.CREATED.value());
                response.addProperty("uuid", authentication.getUuid().toString());
                connection.sendMessage(response);
            }
            case COMMAND -> {
                String uuidString = message.has("uuid") ? message.get("uuid").getAsString() : null;

                if (uuidString == null) {
                    session.close();
                    LOGGER.at(Level.WARNING).log("Missing UUID from connection %s", address);
                    return;
                }

                BlockbenchConnection connection = connections.get(session.getAddress());
                if (connection == null) {
                    session.close();
                    LOGGER.at(Level.WARNING).log("Nonexistent connection for session %s: %s", address, uuidString);
                    return;
                }

                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidString);
                } catch (Exception e) {
                    session.close();
                    LOGGER.at(Level.WARNING).log("Invalid UUID from connection %s: %s", address, uuidString);
                    return;
                }

                if (!connection.getUuid().equals(uuid)) {
                    session.close();
                    LOGGER.at(Level.WARNING).log("Incorrect UUID from connection %s: %s", address, uuidString);
                    return;
                }

                connection.handleCommand(message);
            }
            default -> LOGGER.at(Level.WARNING).log("Unexpected packet type '%s' from connection %s", typeStr, address);
        }
    }
}
