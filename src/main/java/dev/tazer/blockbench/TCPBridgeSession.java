package dev.tazer.blockbench;

import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public class TCPBridgeSession extends BridgeSession {
    private final Socket socket;

    protected TCPBridgeSession(Socket socket) {
        super(socket.getRemoteSocketAddress());
        this.socket = socket;
    }

    @Override
    void write(JsonObject message, SocketAddress address) {
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(GSON.toJson(message) + "\n");
            writer.flush();
        } catch (IOException e) {
            BlockbenchPlugin.get().getLogger().at(Level.WARNING).log("Failed to send message to connection %s: %s", address, message);
        }
    }

    @Override
    void disconnect() {
        BlockbenchPlugin.getBridge().disconnect(address);
    }
}
