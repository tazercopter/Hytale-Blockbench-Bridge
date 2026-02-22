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
    void write(JsonObject message, SocketAddress address) {
        byte[] responseBytes = (GSON.toJson(message) + "\n").getBytes(StandardCharsets.UTF_8);
        channel.writeAndFlush(new DatagramPacket(responseBytes, responseBytes.length, address));
    }

    @Override
    void disconnect() {
        BlockbenchPlugin.getBridge().disconnect(address);
    }
}
