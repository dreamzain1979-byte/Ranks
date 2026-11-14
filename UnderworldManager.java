package dev.rankedsmp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class UnderworldManager {
    private final RankedSMP plugin;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public UnderworldManager(RankedSMP plugin) {
        this.plugin = plugin;
    }

    public String underworldName() {
        return plugin.getConfig().getString("underworld.world-name", "world_underworld");
    }

    public String netherName() {
        return plugin.getConfig().getString("underworld.nether-world-name", "world_nether");
    }

    /** Finds the Underworld: by configured name, or any world from the datapack namespace (unstable_underworld). */
    public World world() {
        World w = Bukkit.getWorld(underworldName());
        if (w != null) return w;
        for (World x : Bukkit.getWorlds()) {
            if (isUnderworld(x)) return x;
        }
        return null;
    }

    public boolean isUnderworld(World w) {
        if (w == null) return false;
        if (w.getName().equals(underworldName())) return true;
        String ns = plugin.getConfig().getString("underworld.datapack-namespace", "unstable_underworld");
        if (ns == null || ns.isEmpty()) return false;
        try {
            return w.getKey().getNamespace().equals(ns);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isNether(World w) {
        return w != null && w.getName().equals(netherName());
    }

    /** Call from the global region thread / main thread. */
    public void ensureWorld() {
        if (world() != null) {
            plugin.getLogger().info("Underworld world '" + underworldName() + "' is loaded.");
            return;
        }
        if (!plugin.getConfig().getBoolean("underworld.create-world", true)) {
            plugin.getLogger().warning("Underworld world '" + underworldName() + "' is not loaded and create-world is false.");
            return;
        }
        try {
            if (Boolean.TRUE.equals(isFolia())) throw new UnsupportedOperationException("Folia");
            World w = new WorldCreator(underworldName())
                    .environment(World.Environment.NORMAL)
                    .generator(new UnderworldGenerator())
                    .createWorld();
            if (w != null) {
                plugin.getLogger().info("Created Underworld world '" + underworldName() + "'.");
                lockSky(w);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Not creating a world here (" + t.getClass().getSimpleName()
                    + "). Install the Unstable Underworld datapack (namespace '" + plugin.getConfig().getString("underworld.datapack-namespace", "unstable_underworld")
                    + "') or a world named '" + underworldName() + "'.");
        }
        if (world() == null) {
            plugin.getLogger().severe("Underworld world is missing: falling below the nether will not work until it exists.");
        }
    }

    /** Fixed bright teal sky: no day/night cycle, no weather. Rule names differ between versions, so try both. */
    private void lockSky(World w) {
        for (String name : new String[]{"advance_time", "doDaylightCycle", "advance_weather", "doWeatherCycle"}) {
            try {
                @SuppressWarnings("unchecked")
                org.bukkit.GameRule<Boolean> rule = (org.bukkit.GameRule<Boolean>) org.bukkit.GameRule.getByName(name);
                if (rule != null) w.setGameRule(rule, false);
            } catch (Throwable ignored) {
                // rule not present in this version
            }
        }
        try {
            w.setFullTime(6000L);
            w.setStorm(false);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private static Boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return Boolean.TRUE;
        } catch (ClassNotFoundException e) {
            return Boolean.FALSE;
        }
    }

    // ------------------------------------------------------------------ nether -> underworld

    public void sendDown(Player p) {
        World uw = world();
        if (uw == null || !busy.add(p.getUniqueId())) return;
        UUID id = p.getUniqueId();
        Location from = p.getLocation();
        double scale = plugin.getConfig().getDouble("underworld.coordinate-scale", 1.0);
        int tx = (int) Math.floor(from.getX() * scale);
        int tz = (int) Math.floor(from.getZ() * scale);
        float yaw = from.getYaw(), pitch = from.getPitch();
        uw.getChunkAtAsync(tx >> 4, tz >> 4).thenAccept(chunk ->
                Bukkit.getRegionScheduler().run(plugin, uw, tx >> 4, tz >> 4, task -> {
                    Location dest;
                    try {
                        dest = findSafeDown(uw, tx, tz);
                    } catch (Throwable t) {
                        busy.remove(id);
                        return;
                    }
                    dest.setYaw(yaw);
                    dest.setPitch(pitch);
                    p.teleportAsync(dest).whenComplete((ok, err) ->
                            p.getScheduler().run(plugin, t2 -> {
                                p.setFallDistance(0f);
                                p.setVelocity(new Vector(0, 0, 0));
                                busy.remove(id);
                            }, () -> busy.remove(id)));
                })).exceptionally(err -> {
            busy.remove(id);
            return null;
        });
    }

    private Location findSafeDown(World uw, int x, int z) {
        if (plugin.getConfig().getBoolean("underworld.use-highest-block", true)) {
            int hy = uw.getHighestBlockYAt(x, z);
            if (hy > uw.getMinHeight() && isStandable(uw.getBlockAt(x, hy, z))) {
                return new Location(uw, x + 0.5, hy + 1, z + 0.5);
            }
        }
        int top = Math.min(uw.getMaxHeight() - 3, plugin.getConfig().getInt("underworld.scan-start-y", 185));
        for (int y = top; y > uw.getMinHeight() + 1; y--) {
            if (isStandable(uw.getBlockAt(x, y, z))) {
                return new Location(uw, x + 0.5, y + 1, z + 0.5);
            }
        }
        // no floor found: build a small obsidian platform
        int py = 64;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                uw.getBlockAt(x + dx, py, z + dz).setType(Material.OBSIDIAN, false);
                uw.getBlockAt(x + dx, py + 1, z + dz).setType(Material.AIR, false);
                uw.getBlockAt(x + dx, py + 2, z + dz).setType(Material.AIR, false);
            }
        }
        return new Location(uw, x + 0.5, py + 1, z + 0.5);
    }

    private static boolean isStandable(Block b) {
        Material m = b.getType();
        if (!m.isSolid() || m == Material.MAGMA_BLOCK || m == Material.BEDROCK || m.name().endsWith("_LEAVES") || m == Material.ICE) return false;
        Block a1 = b.getRelative(0, 1, 0);
        Block a2 = b.getRelative(0, 2, 0);
        return a1.isPassable() && a2.isPassable() && !a1.isLiquid() && !a2.isLiquid();
    }

    // ------------------------------------------------------------------ underworld -> nether

    public void sendUp(Player p) {
        World nether = Bukkit.getWorld(netherName());
        if (nether == null) {
            World fallback = Bukkit.getWorlds().get(0);
            p.teleportAsync(fallback.getSpawnLocation());
            return;
        }
        Location from = p.getLocation();
        int tx = from.getBlockX(), tz = from.getBlockZ();
        nether.getChunkAtAsync(tx >> 4, tz >> 4).thenAccept(chunk ->
                Bukkit.getRegionScheduler().run(plugin, nether, tx >> 4, tz >> 4, task -> {
                    Location dest = null;
                    for (int y = 120; y > 5; y--) {
                        if (isStandable(nether.getBlockAt(tx, y, tz))) {
                            dest = new Location(nether, tx + 0.5, y + 1, tz + 0.5);
                            break;
                        }
                    }
                    if (dest == null) dest = nether.getSpawnLocation();
                    p.teleportAsync(dest);
                }));
    }
}
