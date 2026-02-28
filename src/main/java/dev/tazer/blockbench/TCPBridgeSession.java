package dev.tazer.blockbench;

import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public class TCPBridgeSession extends BridgeSession {
    private final Socket socket;
    private final BufferedWriter writer;

    protected TCPBridgeSession(Socket socket) throws IOException {
        super(socket.getRemoteSocketAddress());
        this.socket = socket;
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    @Override
    synchronized void write(JsonObject message) {
        try {
            writer.write(GSON.toJson(message) + "\n");
            writer.flush();
        } catch (IOException e) {
            BlockbenchPlugin.get().getLogger().at(Level.WARNING).log("Failed to send message to connection %s: %s", address, message);
        }
    }

    @Override
    void close() {
        try {
            writer.close();
        } catch (IOException ignored) {}
        try {
            socket.close();
        } catch (IOException ignored) {}
        BlockbenchPlugin.getBridge().disconnect(address);
    }
}
