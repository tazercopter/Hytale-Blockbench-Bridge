package dev.tazer.blockbench;

import com.google.gson.*;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.asset.FileCommonAsset;
import com.hypixel.hytale.server.core.auth.PlayerAuthentication;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

public class BlockbenchPacketHandler {
    private static final Gson GSON = new Gson();

    private final BlockbenchClient blockbenchClient;
    private final BufferedWriter writer;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public BlockbenchPacketHandler(BufferedWriter writer, PlayerAuthentication auth) {
        this.writer = writer;
        this.blockbenchClient = new BlockbenchClient(auth, this);
    }

    public BlockbenchClient getBlockbenchClient() {
        return this.blockbenchClient;
    }

    public void handle(JsonObject message) {
        String command = message.has("command") ? message.get("command").getAsString() : null;
        if (command == null) {
            LOGGER.at(Level.WARNING).log("Missing command from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        switch (command) {
            case "fileTree" -> sendFileTree();
            case "file" -> handleFileRequest(message);
            case "deleteFile" -> handleDeleteFile(message);
            case "deleteFolder" -> handleDeleteFolder(message);
            case "renameFolder" -> handleRenameFolder(message);
            case "renameFile" -> handleRenameFile(message);
            case "save" -> handleSave(message);
            case "disconnect" -> BlockbenchPlugin.getBridge().cleanupConnection(blockbenchClient.getUuid());
            default -> {}
        }
    }

    public void sendMessage(JsonObject message) {
        try {
            writer.write(GSON.toJson(message) + "\n");
            writer.flush();
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).log("Failed to send message to client (UUID: %s): %s", blockbenchClient.getUuid(), message);
        }
    }

    public void sendFileTree() {
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
        response.addProperty("type", "fileTree");
        response.add("packs", packs);

        LOGGER.at(Level.INFO).log("Sending Blockbench client (UUID: %s) file tree (%d assets)", blockbenchClient.getUuid(), size);

        sendMessage(response);
    }

    private void handleFileRequest(JsonObject message) {
        String path = message.has("path") ? message.get("path").getAsString() : null;

        if (path == null) {
            LOGGER.at(Level.WARNING).log("Missing requested file path from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        CommonAsset asset = CommonAssetRegistry.getByName(path);

        if (asset == null) {
            LOGGER.at(Level.WARNING).log("Unknown asset requested: %s (UUID: %s)", path, blockbenchClient.getUuid());
            return;
        }

        if (asset instanceof FileCommonAsset file) {
            sendFile(file);
        } else LOGGER.at(Level.WARNING).log("Invalid asset requested: %s", path);
    }

    private void sendFile(FileCommonAsset file) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "file");
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
            LOGGER.at(Level.WARNING).log("Failed to read asset block: %s, %s", file.getName(), error);
            return null;
        });
    }

    private void handleSave(JsonObject message) {
        String name = message.has("path") ? message.get("path").getAsString() : null;

        if (name == null) {
            LOGGER.at(Level.WARNING).log("Missing file path for save from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        AssetPack pack = null;

        for (AssetPack assetPack : AssetModule.get().getAssetPacks()) {
            String jsonPack = message.has("pack") ? message.get("pack").getAsString() : null;
            if (assetPack.getName().equals(jsonPack)) {
                pack = assetPack;
                break;
            }
        }

        if (pack == null) {
            LOGGER.at(Level.WARNING).log("Missing asset pack for save from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        String data = message.has("data") ? message.get("data").getAsString() : null;

        if (data == null) {
            LOGGER.at(Level.WARNING).log("Missing file data for save from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        byte[] bytes;
        if (name.endsWith(".blockymodel")) {
            bytes = data.getBytes(StandardCharsets.UTF_8);
        } else if (name.endsWith(".png")) {
            bytes = Base64.getDecoder().decode(data);
        } else {
            LOGGER.at(Level.WARNING).log("Unsupported file type sent: %s", name);
            return;
        }

        Path fileLocation = pack.getPackLocation();

        try {
            if (Files.isDirectory(fileLocation)) {
                fileLocation = fileLocation.resolve("Common", name);
                Files.createDirectories(fileLocation.getParent());

                if (Files.isDirectory(fileLocation.getParent())) {
                    Files.write(fileLocation, bytes);
                    FileCommonAsset file = new FileCommonAsset(fileLocation, name, bytes);
                    CommonAssetModule.get().addCommonAsset(pack.getName(), file);
                    LOGGER.at(Level.INFO).log("Created new file common asset: %s at %s", name, fileLocation);
                }
            }
        } catch (IOException error) {
            LOGGER.at(Level.WARNING).log("Error saving file %s, %s", name, error);
        }
    }

    private void handleRenameFolder(JsonObject message) {
        String path = message.has("path") ? message.get("path").getAsString() : null;
        String name = message.has("name") ? message.get("name").getAsString() : null;
        String pack = message.has("pack") ? message.get("pack").getAsString() : null;

        if (path == null) {
            LOGGER.at(Level.WARNING).log("Missing path for folder rename from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        if (name == null) {
            LOGGER.at(Level.WARNING).log("Missing name for folder rename from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        if (pack == null) {
            LOGGER.at(Level.WARNING).log("Missing pack for folder rename from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        Path oldFolderPath = Path.of("mods", pack.replace(":", "."), "Common", path);
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
        String pack = message.has("pack") ? message.get("pack").getAsString() : null;

        if (path == null) {
            LOGGER.at(Level.WARNING).log("Missing path for file rename from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        if (name == null) {
            LOGGER.at(Level.WARNING).log("Missing name for file rename from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        if (pack == null) {
            LOGGER.at(Level.WARNING).log("Missing pack for file rename from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        Path oldFilePath = Path.of("mods", pack.replace(":", "."), "Common", path);
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
            LOGGER.at(Level.WARNING).log("Missing path for file deletion from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        AssetPack pack = null;

        for (AssetPack assetPack : AssetModule.get().getAssetPacks()) {
            String jsonPack = message.has("pack") ? message.get("pack").getAsString() : null;
            if (assetPack.getName().equals(jsonPack)) {
                pack = assetPack;
                break;
            }
        }

        if (pack == null) {
            LOGGER.at(Level.WARNING).log("Missing asset pack for save from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        Path folderPath = pack.getPackLocation().resolve("Common", name);

        try {
            AssetPack finalPack = pack;
            Files.walk(folderPath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach((file -> {
                        if (file.delete()) {
                            CommonAssetRegistry.removeCommonAssetByName(finalPack.getName(), name + "/" + file.getName());
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
            LOGGER.at(Level.WARNING).log("Missing path for file deletion from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        AssetPack pack = null;

        for (AssetPack assetPack : AssetModule.get().getAssetPacks()) {
            String jsonPack = message.has("pack") ? message.get("pack").getAsString() : null;
            if (assetPack.getName().equals(jsonPack)) {
                pack = assetPack;
                break;
            }
        }

        if (pack == null) {
            LOGGER.at(Level.WARNING).log("Missing asset pack for save from client (UUID: %s)", blockbenchClient.getUuid());
            return;
        }

        Path file = pack.getPackLocation().resolve("Common", name);

        try {
            if (Files.deleteIfExists(file)) {
                CommonAssetRegistry.removeCommonAssetByName(pack.getName(), name);
                LOGGER.at(Level.INFO).log("Deleted file %s", file);
            }
        } catch (IOException error) {
            LOGGER.at(Level.WARNING).log("Error deleting file %s, %s", file, error);
        }
    }
}

