package dev.tazer.blockbench;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public class BlockbenchConfig {
    private int port = 8651;
    
    public static final BuilderCodec<BlockbenchConfig> CODEC = BuilderCodec.builder(BlockbenchConfig.class, BlockbenchConfig::new)
            .append(new KeyedCodec<>("Port", Codec.INTEGER),
                    (c, value) -> c.port = value,
                    (c) -> c.port)
            .add()
            .build();

    public int port() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
