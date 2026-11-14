package dev.rankedsmp;

import java.util.UUID;

public final class PlayerStats {
    public final UUID id;
    public volatile String name;
    public volatile int elo;
    public volatile int kills;
    public volatile int deaths;

    public PlayerStats(UUID id, String name) {
        this.id = id;
        this.name = name;
    }
}
