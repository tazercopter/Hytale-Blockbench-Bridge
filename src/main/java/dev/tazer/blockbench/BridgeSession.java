package dev.tazer.blockbench;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.SocketAddress;

public abstract class BridgeSession {
    protected final SocketAddress address;
    protected static final Gson GSON = new Gson();

    protected BridgeSession(SocketAddress address) {
        this.address = address;
    }

    public SocketAddress getAddress() {
        return address;
    }

    abstract void write(JsonObject message);

    abstract void close();
}
