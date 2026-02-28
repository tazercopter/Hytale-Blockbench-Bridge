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
        boolean consumed = false;
        HytaleLogger logger = BlockbenchPlugin.get().getLogger();
        if (message instanceof DatagramPacket datagram) {
            SocketAddress address = datagram.getSocketAddress();
            byte[] bytes = datagram.getData();
            try {
                String jsonString = new String(bytes, StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();
                BlockbenchBridge bridge = BlockbenchPlugin.getBridge();
                BridgeSession session = bridge.getSession(address);
                if (session == null) {
                    session = new UDPBridgeSession(address, context.channel());
                }
                bridge.handleMessage(session, json);
                consumed = true;
            } catch (JsonSyntaxException e) {
                logger.at(Level.WARNING).log("Incorrect JSON syntax from address %s", address);
            }
        }

        if (!consumed) super.channelRead(context, message);
    }
}
