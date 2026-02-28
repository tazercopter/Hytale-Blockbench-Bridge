package dev.tazer.blockbench;

import javax.annotation.Nullable;

public enum CommandType {
    FILE_TREE("fileTree"),
    FILE("file"),
    DELETE_FILE("deleteFile"),
    DELETE_FOLDER("deleteFolder"),
    RENAME_FOLDER("renameFolder"),
    RENAME_FILE("renameFile"),
    SAVE("save"),
    DISCONNECT("disconnect");

    private final String value;

    CommandType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static @Nullable CommandType fromString(String value) {
        for (CommandType type : values()) {
            if (type.value.equals(value)) return type;
        }
        return null;
    }
}
