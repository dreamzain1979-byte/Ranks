package dev.rankedsmp;

import org.bukkit.Bukkit;
import org.bukkit.World;

/** A Blood Moon is any night (night-start..night-end) whose moon phase is 0 (full moon). */
public final class BloodMoonManager {
    private final RankedSMP plugin;
    private volatile boolean active;
    private boolean initialised;

    public BloodMoonManager(RankedSMP plugin) {
        this.plugin = plugin;
    }

    public World overworld() {
        String name = plugin.getConfig().getString("bloodmoon.overworld-name", "");
        if (name != null && !name.isEmpty()) {
            World w = Bukkit.getWorld(name);
            if (w != null) return w;
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) return w;
        }
        return null;
    }

    /** Computed live, so it is always correct even right after a time change. */
    public boolean isBloodMoonNow() {
        World w = overworld();
        if (w == null) return false;
        long full = w.getFullTime();
        long time = Math.floorMod(full, 24000L);
        int phase = (int) (Math.floorDiv(full, 24000L) % 8L);
        int start = plugin.getConfig().getInt("bloodmoon.night-start", 13000);
        int end = plugin.getConfig().getInt("bloodmoon.night-end", 23000);
        return phase == 0 && time >= start && time < end;
    }

    public String describe() {
        World w = overworld();
        if (w == null) return "no overworld found";
        long full = w.getFullTime();
        return "world=" + w.getName() + " fullTime=" + full + " dayPhase=" + (Math.floorDiv(full, 24000L) % 8L)
                + " timeOfDay=" + Math.floorMod(full, 24000L) + " bloodMoon=" + isBloodMoonNow();
    }

    public boolean isActive() {
        return active;
    }

    public void start() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
            boolean now = isBloodMoonNow();
            if (!initialised) {
                initialised = true;
                active = now;
                return;
            }
            if (now != active) {
                active = now;
                Bukkit.getServer().sendMessage(plugin.msg().get(now ? "bloodmoon-rise" : "bloodmoon-end"));
                World w = overworld();
                if (w != null && now) {
                    for (org.bukkit.entity.Player p : w.getPlayers()) {
                        p.getScheduler().run(plugin, x -> p.playSound(p.getLocation(), "minecraft:entity.wither.spawn", 0.6f, 0.5f), null);
                    }
                }
            }
        }, 20L, 20L);
    }
}
