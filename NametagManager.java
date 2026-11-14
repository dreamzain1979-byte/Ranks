package dev.rankedsmp;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rank icon next to the name above a player's head. Folia has no usable scoreboard teams, so the icon is a
 * TextDisplay entity riding the player and billboarded towards every viewer. Everything runs on the player's own thread.
 */
public final class NametagManager {
    private final RankedSMP plugin;
    private final Map<UUID, TextDisplay> tags = new ConcurrentHashMap<>();
    private final Map<UUID, Rank> shown = new ConcurrentHashMap<>();
    /** set when the scoreboard API fails on this server: we then fall back to the display entity */
    private volatile boolean teamFailed;

    public NametagManager(RankedSMP plugin) {
        this.plugin = plugin;
    }

    /** Called every 2 ticks by the ticker (player's own thread). */
    public void tick(Player p) {
        update(p);
    }

    public void remove(Player p) {
        remove(p.getUniqueId());
    }

    /** Call on the player's thread (every few ticks). Creates, restyles or removes the icon as needed. */
    public void update(Player p) {
        UUID id = p.getUniqueId();
        if (!plugin.getConfig().getBoolean("nametag.enabled", true) || !p.isOnline()) {
            remove(id);
            return;
        }
        Rank rank = plugin.ranks().rankOf(id);

        if (useTeams()) {
            // real name tag: the icon is the team prefix, so it is part of the name itself
            kill(tags.remove(id));
            if (shown.get(id) != rank) {
                shown.put(id, rank);
                applyTeam(p.getName(), rank);
            }
            return;
        }

        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.isInvisible()) {
            remove(id);
            return;
        }
        TextDisplay d = tags.get(id);
        if (d == null || !d.isValid() || !p.getPassengers().contains(d)) {
            kill(d);
            d = spawn(p);
            if (d == null) return;
            tags.put(id, d);
            shown.remove(id);
        }
        if (shown.get(id) != rank) {
            style(d, p, rank);
            shown.put(id, rank);
        }
    }

    private boolean useTeams() {
        return !teamFailed && "team".equalsIgnoreCase(plugin.getConfig().getString("nametag.style", "team"));
    }

    private static String teamName(Rank r) {
        // highest rank sorts first in the tab list
        return "rk" + (9 - r.ordinal()) + "_" + r.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Scoreboard work runs on the global region thread. */
    private void applyTeam(String playerName, Rank rank) {
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
            try {
                Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
                Team target = null;
                for (Rank x : Rank.values()) {
                    Team tm = sb.getTeam(teamName(x));
                    if (tm == null) tm = sb.registerNewTeam(teamName(x));
                    tm.prefix(Component.text().append(x.iconComponent()).append(Component.space()).build());
                    if (x == rank) target = tm;
                    else if (tm.hasEntry(playerName)) tm.removeEntry(playerName);
                }
                if (target != null && !target.hasEntry(playerName)) target.addEntry(playerName);
            } catch (Throwable ex) {
                teamFailed = true;
                shown.clear();
                plugin.getLogger().warning("Scoreboard nametags failed on this server (" + ex
                        + "); falling back to the display-entity icon.");
            }
        });
    }

    private TextDisplay spawn(Player p) {
        TextDisplay d = p.getWorld().spawn(p.getLocation(), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(false);
            t.setSeeThrough(false);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setViewRange(1.0f);
            t.text(Component.text(" "));
        });
        if (!p.addPassenger(d)) {
            d.remove();
            return null;
        }
        return d;
    }

    private void style(TextDisplay d, Player p, Rank rank) {
        d.text(rank.iconComponent());
        float s = (float) plugin.getConfig().getDouble("nametag.scale", 0.6);
        double y = plugin.getConfig().getDouble("nametag.y-offset", 0.55);
        String mode = plugin.getConfig().getString("nametag.mode", "left");
        double x = 0;
        if (!"above".equalsIgnoreCase(mode)) {
            double namePx = width(p.getName());
            double gap = plugin.getConfig().getDouble("nametag.gap", 0.02);
            x = -((namePx * 0.025) / 2.0 + gap + 4.5 * 0.025 * s);
        } else {
            y += 0.3; // "above": stacked over the name
        }
        d.setTransformation(new Transformation(new Vector3f((float) x, (float) y, 0f), new Quaternionf(),
                new Vector3f(s, s, s), new Quaternionf()));
    }

    /** Approximate pixel width of a name in the default font. */
    private static double width(String name) {
        double w = 0;
        for (char c : name.toCharArray()) {
            if ("i!|:.,;'".indexOf(c) >= 0) w += 2;
            else if ("l`".indexOf(c) >= 0) w += 3;
            else if ("tI[] ".indexOf(c) >= 0) w += 4;
            else if ("fk<>()".indexOf(c) >= 0) w += 5;
            else w += 6;
        }
        return Math.max(0, w - 1);
    }

    private void kill(TextDisplay d) {
        if (d == null) return;
        try {
            d.remove();
        } catch (Throwable ignored) {
            // entity may belong to another region; it is non-persistent and will vanish on its own
        }
    }

    public void remove(UUID id) {
        kill(tags.remove(id));
        shown.remove(id);
    }

    public void removeAll() {
        for (UUID id : tags.keySet()) remove(id);
    }

    /** Force a restyle on the next update (e.g. after config reload). */
    public void invalidate(UUID id) {
        shown.remove(id);
    }
}
