package dev.tazer.blockbench;

import java.util.UUID;

public record BlockbenchKey(String username, String code, UUID uuid) {
    String getFullKey() {
        return username.equals("Console") ? code : username + '-' + code;
    }
}
