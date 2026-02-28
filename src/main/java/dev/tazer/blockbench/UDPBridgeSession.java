package dev.tazer.blockbench;

import com.google.gson.JsonObject;
import io.netty.channel.Channel;

import java.net.DatagramPacket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

public class UDPBridgeSession extends BridgeSession {
    private final Channel channel;

    protected UDPBridgeSession(SocketAddress address, Channel channel) {
        super(address);
        this.channel = channel;
    }

    @Override
    void write(JsonObject message) {
        byte[] responseBytes = (GSON.toJson(message) + "\n").getBytes(StandardCharsets.UTF_8);
        // Might need to use netty datagram packet instead?
        channel.writeAndFlush(new DatagramPacket(responseBytes, responseBytes.length, address));
    }

    @Override
    void close() {
        BlockbenchPlugin.getBridge().disconnect(address);
    }
}
