package dev.tazer.blockbench;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.asset.FileCommonAsset;
import com.hypixel.hytale.server.core.auth.PlayerAuthentication;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;

public class BlockbenchConnection {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final BridgeSession session;
    private final UUID uuid;

    public BlockbenchConnection(BridgeSession session, PlayerAuthentication auth) {
        this.session = session;
        this.uuid = auth.getUuid();
    }

    public UUID getUuid() {
        return uuid;
    }

    public void handleCommand(JsonObject message) {
        String command = message.has("command") ? message.get("command").getAsString() : null;
        if (command == null) {
            LOGGER.at(Level.WARNING).log("Missing command from connection (UUID: %s)", uuid);
            return;
        }

        CommandType cmdType = CommandType.fromString(command);
        if (cmdType == null) {
            LOGGER.at(Level.WARNING).log("Unknown command '%s' from connection (UUID: %s)", command, uuid);
            return;
        }

        switch (cmdType) {
            case FILE_TREE -> sendFileTree();
            case FILE -> handleFileRequest(message);
            case DELETE_FILE -> handleDeleteFile(message);
            case DELETE_FOLDER -> handleDeleteFolder(message);
            case RENAME_FOLDER -> handleRenameFolder(message);
            case RENAME_FILE -> handleRenameFile(message);
            case SAVE -> handleSave(message);
            case DISCONNECT -> session.close();
        }
    }

    public void sendMessage(JsonObject message) {
        session.write(message);
    }

    private void sendFileTree() {
        int size = 0;

        Map<String, JsonObject> packEntries = new HashMap<>();
        Map<String, Boolean> packImmutable = new HashMap<>();

        for (AssetPack pack : AssetModule.get().getAssetPacks()) {
            packEntries.put(pack.getName(), new JsonObject());
            packImmutable.put(pack.getName(), pack.isImmutable());
        }

        List<List<CommonAssetRegistry.PackAsset>> allAssets = CommonAssetRegistry.getAllAssets().stream().sorted(
                Comparator.comparing(list -> list.getLast().asset().getName())
        ).toList();
        for (List<CommonAssetRegistry.PackAsset> duplicateAssets : allAssets) {
            for (CommonAssetRegistry.PackAsset packAsset : duplicateAssets) {
                CommonAsset asset = packAsset.asset();
                String packName = packAsset.pack();
                String fullPath = asset.getName();

                JsonObject current = packEntries.get(packName);
                if (current == null) continue;

                String[] parts = fullPath.split("/");

                for (int i = 0; i < parts.length; i++) {
                    String part = parts[i];
                    boolean isFile = i == parts.length - 1;

                    if (isFile) {
                        current.add(part, new JsonPrimitive(true));
                    } else {
                        if (!current.has(part) || (current.get(part).isJsonPrimitive() && current.get(part).getAsJsonPrimitive().getAsBoolean())) {
                            JsonObject folder = new JsonObject();
                            current.add(part, folder);
                            current = folder;
                        } else {
                            current = current.getAsJsonObject(part);
                        }
                    }
                }

                size++;
            }
        }

        JsonObject packs = new JsonObject();
        for (Map.Entry<String, JsonObject> entry : packEntries.entrySet()) {
            JsonObject pack = new JsonObject();
            pack.addProperty("immutable", packImmutable.getOrDefault(entry.getKey(), false));
            pack.add("entries", entry.getValue());
            packs.add(entry.getKey(), pack);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", MessageType.FILE_TREE.value());
        response.add("packs", packs);

        LOGGER.at(Level.INFO).log("Sending connection (UUID: %s) file tree (%d files)", uuid, size);

        sendMessage(response);
    }

    private void handleFileRequest(JsonObject message) {
        String name = message.has("path") ? message.get("path").getAsString() : null;

        if (name == null) {
            LOGGER.at(Level.WARNING).log("Missing requested file path from connection (UUID: %s)", uuid);
            return;
        }

        AssetPack pack = getPackFromMessage(message);
        if (pack == null) {
            LOGGER.at(Level.WARNING).log("Missing request asset pack from connection (UUID: %s)", uuid);
            return;
        }

        CommonAsset asset = CommonAssetRegistry.getByName(name); // TODO: find conflicting assets and get the one of THIS pack

        if (asset == null) {
            LOGGER.at(Level.WARNING).log("Unknown asset requested: %s (UUID: %s)", name, uuid);
            return;
        }

        if (asset instanceof FileCommonAsset file) {
            sendFile(file);
        } else LOGGER.at(Level.WARNING).log("Invalid asset requested: %s", name);
    }

    private void sendFile(FileCommonAsset file) {
        JsonObject response = new JsonObject();
        response.addProperty("type", MessageType.FILE.value());
        response.addProperty("path", file.getName());

        file.getBlob0().thenAccept(bytes -> {
            if (file.getName().endsWith(".blockymodel")) {
                String jsonString = new String(bytes, StandardCharsets.UTF_8);
                JsonObject modelJson = JsonParser.parseString(jsonString).getAsJsonObject();

                response.add("data", modelJson);
            } else if (file.getName().endsWith(".png")) {
                String base64 = Base64.getEncoder().encodeToString(bytes);
                response.addProperty("data", base64);
            } else {
                LOGGER.at(Level.WARNING).log("Unsupported file type requested: %s", file.getName());
            }

            sendMessage(response);
        }).exceptionally(error -> {
            LOGGER.at(Level.WARNING).log("Failed to read asset data: %s, %s", file.getName(), error);
            return null;
        });
    }

    private void handleSave(JsonObject message) {
        String name = message.has("path") ? message.get("path").getAsString() : null;

        if (name == null) {
            LOGGER.at(Level.WARNING).log("Missing file path for save from connection (UUID: %s)", uuid);
            return;
        }

        AssetPack pack = getPackFromMessage(message);
        if (pack == null) {
            LOGGER.at(Level.WARNING).log("Missing asset pack for save from connection (UUID: %s)", uuid);
            return;
        }

        String data = message.has("data") ? message.get("data").getAsString() : null;

        if (data == null) {
            LOGGER.at(Level.WARNING).log("Missing file data for save from connection (UUID: %s)", uuid);
            return;
        }

        byte[] bytes;
        if (name.endsWith(".blockymodel")) {
            bytes = data.getBytes(StandardCharsets.UTF_8);
        } else if (name.endsWith(".png")) {
            try {
                bytes = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                LOGGER.at(Level.WARNING).log("Invalid Base64 data for save from connection (UUID: %s): %s", uuid, e.getMessage());
                return;
            }
        } else {
            LOGGER.at(Level.WARNING).log("Unsupported file type sent: %s", name);
            return;
        }

        Path fileLocation = safeResolveCommonPath(pack, name);
        if (fileLocation == null) {
            LOGGER.at(Level.WARNING).log("Invalid file path for save from connection (UUID: %s): %s", uuid, name);
            return;
        }

        try {
            if (Files.isDirectory(pack.getPackLocation())) {
                Files.createDirectories(fileLocation.getParent());
                Files.write(fileLocation, bytes);
                FileCommonAsset file = new FileCommonAsset(fileLocation, name, bytes);
                CommonAssetModule.get().addCommonAsset(pack.getName(), file);
                LOGGER.at(Level.INFO).log("Created new file common asset: %s at %s", name, fileLocation);
            }
        } catch (IOException error) {
            LOGGER.at(Level.WARNING).log("Error saving file %s, %s", name, error);
        }
    }

    private void handleRenameFolder(JsonObject message) {
        String path = message.has("path") ? message.get("path").getAsString() : null;
        String name = message.has("name") ? message.get("name").getAsString() : null;

        if (path == null) {
            LOGGER.at(Level.WARNING).log("Missing path for folder rename from connection (UUID: %s)", uuid);
            return;
        }

        if (name == null) {
            LOGGER.at(Level.WARNING).log("Missing name for folder rename from connection (UUID: %s)", uuid);
            return;
        }

        AssetPack pack = getPackFromMessage(message);
        if (pack == null) {
            LOGGER.at(Level.WARNING).log("Missing asset pack for folder rename from connection (UUID: %s)", uuid);
            return;
        }

        Path oldFolderPath = safeResolveCommonPath(pack, path);
        if (oldFolderPath == null) {
            LOGGER.at(Level.WARNING).log("Invalid path for folder rename from connection (UUID: %s): %s", uuid, path);
            return;
        }

        Path newFolderPath = oldFolderPath.getParent().resolve(name);

        try {
            Files.move(oldFolderPath, newFolderPath);
            LOGGER.at(Level.INFO).log("Renamed folder from %s to %s", oldFolderPath, newFolderPath);
        } catch (IOException error) {
            LOGGER.at(Level.WARNING).log("Error renaming folder %s to %s, %s", oldFolderPath, newFolderPath, error);
        }
    }

    private void handleRenameFile(JsonObject message) {
        String path = message.has("path") ? message.get("path").getAsString() : null;
        String name = message.has("name") ? message.get("name").getAsString() : null;

        if (path == null) {
            LOGGER.at(Level.WARNING).log("Missing path for file rename from connection (UUID: %s)", uuid);
            return;
        }

        if (name == null) {
            LOGGER.at(Level.WARNING).log("Missing name for file rename from connection (UUID: %s)", uuid);
            return;
        }

        AssetPack pack = getPackFromMessage(message);
        if (pack == null) {
            LOGGER.at(Level.WARNING).log("Missing asset pack for file rename from connection (UUID: %s)", uuid);
            return;
        }

        Path oldFilePath = safeResolveCommonPath(pack, path);
        if (oldFilePath == null) {
            LOGGER.at(Level.WARNING).log("Invalid path for file rename from connection (UUID: %s): %s", uuid, path);
            return;
        }

        Path newFilePath = oldFilePath.getParent().resolve(name);

        try {
            Files.move(oldFilePath, newFilePath);
            LOGGER.at(Level.INFO).log("Renamed file from %s to %s", oldFilePath, newFilePath);
        } catch (IOException error) {
            LOGGER.at(Level.WARNING).log("Error renaming file %s to %s, %s", oldFilePath, newFilePath, error);
        }
    }

    private void handleDeleteFolder(JsonObject message) {
        String name = message.has("path") ? message.get("path").getAsString() : null;

        if (name == null) {
            LOGGER.at(Level.WARNING).log("Missing path for folder deletion from connection (UUID: %s)", uuid);
            return;
        }

        AssetPack pack = getPackFromMessage(message);
        if (pack == null) {
            LOGGER.at(Level.WARNING).log("Missing asset pack for folder deletion from connection (UUID: %s)", uuid);
            return;
        }

        Path folderPath = safeResolveCommonPath(pack, name);
        if (folderPath == null) {
            LOGGER.at(Level.WARNING).log("Invalid path for folder deletion from connection (UUID: %s): %s", uuid, name);
            return;
        }

        try {
            AssetPack finalPack = pack;
            Files.walk(folderPath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach((file -> {
                        if (file.delete()) {
                            String assetName = name + "/" + file.getName();
                            CommonAssetRegistry.removeCommonAssetByName(finalPack.getName(), assetName);
                        }
                    }));
            LOGGER.at(Level.INFO).log("Deleted folder %s", folderPath);
        } catch (IOException error) {
            LOGGER.at(Level.WARNING).log("Error deleting folder %s, %s", folderPath, error);
        }
    }

    private void handleDeleteFile(JsonObject message) {
        String name = message.has("path") ? message.get("path").getAsString() : null;

        if (name == null) {
            LOGGER.at(Level.WARNING).log("Missing path for file deletion from connection (UUID: %s)", uuid);
            return;
        }

        AssetPack pack = getPackFromMessage(message);
        if (pack == null) {
            LOGGER.at(Level.WARNING).log("Missing asset pack for file deletion from connection (UUID: %s)", uuid);
            return;
        }

        Path file = safeResolveCommonPath(pack, name);
        if (file == null) {
            LOGGER.at(Level.WARNING).log("Invalid path for file deletion from connection (UUID: %s): %s", uuid, name);
            return;
        }

        try {
            if (Files.deleteIfExists(file)) {
                CommonAssetRegistry.removeCommonAssetByName(pack.getName(), name);
                LOGGER.at(Level.INFO).log("Deleted file %s", file);
            }
        } catch (IOException error) {
            LOGGER.at(Level.WARNING).log("Error deleting file %s, %s", file, error);
        }
    }

    @Nullable
    private static AssetPack getPackFromMessage(JsonObject message) {
        String packName = message.has("pack") ? message.get("pack").getAsString() : null;
        if (packName == null) return null;
        for (AssetPack assetPack : AssetModule.get().getAssetPacks()) {
            if (assetPack.getName().equals(packName)) return assetPack;
        }
        return null;
    }

    @Nullable
    private static Path safeResolveCommonPath(AssetPack pack, String relativePath) {
        Path base = pack.getPackLocation().resolve("Common").normalize();
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base)) return null;
        return resolved;
    }
}
