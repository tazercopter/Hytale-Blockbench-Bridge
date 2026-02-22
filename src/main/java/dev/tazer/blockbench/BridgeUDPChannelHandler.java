package dev.tazer.blockbench;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.hypixel.hytale.logger.HytaleLogger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.net.DatagramPacket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public class BridgeUDPChannelHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        HytaleLogger logger = BlockbenchPlugin.get().getLogger();
        if (message instanceof DatagramPacket datagram) {
            SocketAddress address = datagram.getSocketAddress();
            byte[] bytes = datagram.getData();
            JsonObject json;
            try {
                String jsonString = new String(bytes, StandardCharsets.UTF_8);
                json = JsonParser.parseString(jsonString).getAsJsonObject();
                BlockbenchPlugin.getBridge().handleMessage(new UDPBridgeSession(address, context.channel()), json);
            } catch (JsonSyntaxException e) {
                logger.at(Level.WARNING).log("Incorrect JSON syntax from address %s", address);
            }
        } else logger.at(Level.WARNING).log("Unknown message format sent to %s", context.channel().localAddress());

        super.channelRead(context, message);
    }
}
