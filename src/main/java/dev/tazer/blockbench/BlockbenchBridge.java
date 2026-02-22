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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class BlockbenchBridge {
    // TODO sort out all the error messages
    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<SocketAddress, BridgeSession> sessions = new ConcurrentHashMap<>();
    private final Map<SocketAddress, BlockbenchConnection> connections = new ConcurrentHashMap<>();
    private final Map<Instant, BlockbenchKey> keys = new ConcurrentHashMap<>();
    private static final HytaleLogger LOGGER = BlockbenchPlugin.get().getLogger();

    public BlockbenchBridge() {
        if (BlockbenchPlugin.getBridge() != null) {
            throw new IllegalStateException("A Blockbench Bridge has already been built!");
        }
    }

    public String generateKey(@Nullable String username, UUID uuid) {
        StringBuilder builder = new StringBuilder(4);

        for (int i = 0; i < 4; i++) {
            int index = RANDOM.nextInt(CHARACTERS.length());
            builder.append(CHARACTERS.charAt(index));
        }

        String message = (username == null ? "" : username + '-') + builder;
        keys.put(Instant.now(), new BlockbenchKey(message, uuid));
        return message;
    }

    public Map<SocketAddress, BlockbenchConnection> getConnections() {
        return connections;
    }

    public void disconnect(SocketAddress address) {
        BridgeSession session = sessions.get(address);
        connections.remove(session.getAddress());
        sessions.remove(address);
        // TODO send disconnect
    }

    protected void createTCPListener(int port) {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                LOGGER.at(Level.INFO).log("TCP bridge listening on port %d", port);

                while (true) {
                    Socket socket = serverSocket.accept();
                    LOGGER.at(Level.INFO).log("Received connection from: %s", socket.getRemoteSocketAddress());

                    new Thread(() -> {
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                            String line;
                            while ((line = in.readLine()) != null) {
                                JsonObject json = JsonParser.parseString(line).getAsJsonObject();

                                handleMessage(new TCPBridgeSession(socket), json);
                            }
                        } catch (Exception ignored) {
                        } finally {
                            try { socket.close(); } catch (IOException ignored) {}
                            LOGGER.at(Level.INFO).log("Connection aborted: %s", socket.getRemoteSocketAddress());
                        }
                    }).start();
                }

            } catch (IOException e) {
                LOGGER.at(Level.WARNING).log("Error while starting the blockbench server socket: {}", String.valueOf(e));
            }
        }).start();
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

        String type = message.has("type") ? message.get("type").getAsString() : null;

        if (type == null) {
            session.disconnect();
            LOGGER.at(Level.WARNING).log("Missing packet type from connection %s", address);
            return;
        }

        if (type.equals("create")) { // CREATE|key
            LOGGER.at(Level.INFO).log("Starting authentication flow for connection %s", address);

            String key = message.has("key") ? message.get("key").getAsString() : null;
            if (key == null || key.isEmpty()) {
                session.disconnect();
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

                if (entry.getValue().message().equals(key)) {
                    authentication = new PlayerAuthentication(UUID.randomUUID(), key.split("-")[0]);
                    keys.remove(instant);
                    break;
                }
            }

            if (authentication == null) {
                session.disconnect();
                LOGGER.at(Level.WARNING).log("Could not validate authentication for Blockbench connection %s", address);
                return;
            }

            LOGGER.at(Level.INFO).log("Authentication validated for connection %s", address);

            BlockbenchConnection connection = new BlockbenchConnection(session, authentication);
            sessions.put(address, session);
            connections.put(session.getAddress(), connection);

            LOGGER.at(Level.INFO).log("Blockbench connection %s (UUID: %s) established!", authentication.getUsername(), authentication.getUuid());

            JsonObject response = new JsonObject();
            response.addProperty("type", "created");
            response.addProperty("uuid", authentication.getUuid().toString());
            connection.sendMessage(response);
        } else if (type.equals("command")) {
            String uuidString = message.has("uuid") ? message.get("uuid").getAsString() : null;

            if (uuidString == null) {
                session.disconnect();
                LOGGER.at(Level.WARNING).log("Missing UUID from connection %s", address);
                return;
            }

            BlockbenchConnection connection = connections.get(session.getAddress());
            if (connection == null) {
                session.disconnect();
                LOGGER.at(Level.WARNING).log("Nonexistent connection for session %s: %s", address, uuidString);
                return;
            }

            UUID uuid;
            try {
                uuid = UUID.fromString(uuidString);
            } catch (Exception e) {
                session.disconnect();
                LOGGER.at(Level.WARNING).log("Invalid UUID from connection %s: %s", address, uuidString);
                return;
            }

            if (!connection.getUuid().equals(uuid)) {
                session.disconnect();
                LOGGER.at(Level.WARNING).log("Incorrect UUID from connection %s: %s", address, uuidString);
                return;
            }

            connection.handleCommand(message);
        }
    }
}