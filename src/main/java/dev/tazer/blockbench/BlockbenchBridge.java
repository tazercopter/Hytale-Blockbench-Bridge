package dev.tazer.blockbench;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.common.events.CommonAssetMonitorEvent;
import com.hypixel.hytale.server.core.auth.PlayerAuthentication;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class BlockbenchBridge {
    private static final int PORT = 8651; // TODO: config
    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new Gson();

    private final Map<Socket, UUID> connections = new ConcurrentHashMap<>();
    public final Map<UUID, BlockbenchClient> clients = new ConcurrentHashMap<>();
    private final Map<Instant, String> keys = new ConcurrentHashMap<>();
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public BlockbenchBridge() {
        if (BlockbenchPlugin.getBridge() != null) {
            throw new IllegalStateException("A Blockbench Bridge has already been built!");
        }

        new Thread(this::startServer).start();
    }

    private void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            LOGGER.at(Level.INFO).log("TCP bridge listening on port %d", PORT);

            while (true) {
                Socket socket = serverSocket.accept();
                LOGGER.at(Level.INFO).log("Received connection from: %s", socket.getRemoteSocketAddress());

                new Thread(() -> handleConnection(socket)).start();
            }

        } catch (IOException e) {
            LOGGER.at(Level.WARNING).log(String.valueOf(e));
        }
    }

    public String generateKey(String username) {
        StringBuilder builder = new StringBuilder(4);

        for (int i = 0; i < 4; i++) {
            int index = RANDOM.nextInt(CHARACTERS.length());
            builder.append(CHARACTERS.charAt(index));
        }

        String key = (username.equals("Console") ? "" : username + '-') + builder;
        keys.put(Instant.now(), key);
        return key;
    }

    private void handleConnection(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject json;
                try {
                    json = GSON.fromJson(line, JsonObject.class);
                } catch (JsonSyntaxException e) {
                    LOGGER.at(Level.WARNING).log("Invalid JSON from connection %s: %s", socket.getRemoteSocketAddress(), line);
                    continue;
                }

                String type = json.has("type") ? json.get("type").getAsString() : null;

                if (type == null) {
                    LOGGER.at(Level.WARNING).log("Missing packet type from connection %s", socket.getRemoteSocketAddress());
                    continue;
                }

                if (type.equals("create") && !connections.containsKey(socket)) { // CREATE|key
                    LOGGER.at(Level.INFO).log("Starting authenticated flow for connection %s", socket.getRemoteSocketAddress());

                    String key = json.has("key") ? json.get("key").getAsString() : null;
//                    key = "hi-9410"; // TODO: when not testing
                    if (key == null) {
                        LOGGER.at(Level.WARNING).log("Missing authentication key from connection %s", socket.getRemoteSocketAddress());
                        continue;
                    }

                    PlayerAuthentication authentication = null;
                    long currentSecond = Instant.now().getEpochSecond();

                    for (Map.Entry<Instant, String> entry : new HashMap<>(keys).entrySet()) {
                        Instant instant = entry.getKey();
                        if (currentSecond - instant.getEpochSecond() > 30) {
                            keys.remove(instant);
                            continue;
                        }

                        if (entry.getValue().equals(key)) {
                            authentication = new PlayerAuthentication(UUID.randomUUID(), key.split("-")[0]);
                            keys.remove(instant);
                            break;
                        }
                    }

                    // TODO: when not testing
//                    if (authentication == null) authentication = new PlayerAuthentication(UUID.randomUUID(), "consolee");


                    if (authentication == null) {
                        invalidateConnection(socket);
                        return;
                    }

                    LOGGER.at(Level.INFO).log("Authentication validated for connection %s", socket.getRemoteSocketAddress());

                    BlockbenchPacketHandler packetHandler = new BlockbenchPacketHandler(writer, authentication);
                    BlockbenchClient client = packetHandler.getBlockbenchClient();
                    connections.put(socket, authentication.getUuid());
                    clients.put(authentication.getUuid(), client);

                    LOGGER.at(Level.INFO).log("Blockbench client %s (UUID: %s) connected!", authentication.getUsername(), authentication.getUuid());

                    JsonObject response = new JsonObject();
                    response.addProperty("type", "created");
                    response.addProperty("uuid", authentication.getUuid().toString());
                    writer.write(GSON.toJson(response) + "\n");
                    writer.flush();
                } else if (type.equals("command")) {
                    String uuidString = json.has("uuid") ? json.get("uuid").getAsString() : null;

                    if (uuidString == null) {
                        LOGGER.at(Level.WARNING).log("Missing UUID from connection %s", socket.getRemoteSocketAddress());
                        continue;
                    }

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidString);
                    } catch (Exception e) {
                        LOGGER.at(Level.WARNING).log("Invalid UUID from connection %s: %s", socket.getRemoteSocketAddress(), uuidString);
                        continue;
                    }

                    BlockbenchClient client = clients.get(uuid);
                    if (client == null) {
                        LOGGER.at(Level.WARNING).log("Nonexistent UUID from connection %s: %s", socket.getRemoteSocketAddress(), uuidString);
                        continue;
                    }

                    client.getPacketHandler().handle(json);
                }
            }
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).log(String.valueOf(e));
        } finally {
            cleanupConnection(socket);
        }
    }

    private void invalidateConnection(Socket socket) throws IOException {
        LOGGER.at(Level.WARNING).log("Could not validate authentication for Blockbench connection %s", socket.getRemoteSocketAddress());
        socket.close();
    }

    private void cleanupConnection(Socket socket) {
        UUID uuid = connections.get(socket);
        connections.remove(socket);
        if (uuid != null) clients.remove(uuid);
        try {
            socket.close();
        } catch (IOException ignored) {}
        LOGGER.at(Level.INFO).log("Connection aborted: %s", socket.getRemoteSocketAddress());
    }

    public void cleanupConnection(UUID uuid) {
        for (Map.Entry<Socket, UUID> connection : connections.entrySet()) {
            if (connection.getValue().equals(uuid)) {
                cleanupConnection(connection.getKey());
                break;
            }
        }
    }
}