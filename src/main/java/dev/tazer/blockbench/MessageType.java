package dev.tazer.blockbench;

import javax.annotation.Nullable;

public enum MessageType {
    // Incoming
    CREATE("create"),
    COMMAND("command"),
    // Outgoing
    CREATED("created"),
    FILE_TREE("fileTree"),
    FILE("file"),
    UPDATE("update");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static @Nullable MessageType fromString(String value) {
        for (MessageType type : values()) {
            if (type.value.equals(value)) return type;
        }
        return null;
    }
}
