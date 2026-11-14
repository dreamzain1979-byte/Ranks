package dev.rankedsmp;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Elo, ranks, ArchNemesis, kill cooldowns and persistence (data.yml). Thread-safe for Folia. */
public final class RankManager {
    private final RankedSMP plugin;
    private final Object lock = new Object();
    private final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<>();
    /** key = killerUUID|victimUUID, value = epoch millis when the cooldown ends */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private volatile UUID archNemesis;
    private final int[] mins = new int[Rank.values().length];
    private volatile boolean dirty;
    private final File file;

    public RankManager(RankedSMP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    // ------------------------------------------------------------------ config / ranks

    public void loadThresholds() {
        for (Rank r : Rank.values()) {
            String key = (r == Rank.ARCHNEMESIS ? Rank.NEMESIS : r).name().toLowerCase(Locale.ROOT);
            mins[r.ordinal()] = plugin.getConfig().getInt("ranks." + key, defaultMin(r));
        }
    }

    private static int defaultMin(Rank r) {
        return switch (r) {
            case COPPER -> 0;
            case IRON -> 50;
            case GOLD -> 100;
            case PLATINUM -> 150;
            case DIAMOND -> 200;
            case NETHERITE -> 250;
            default -> 300;
        };
    }

    public int minElo(Rank r) {
        return mins[r.ordinal()];
    }

    /** Rank purely from Elo (never ARCHNEMESIS). */
    public Rank baseRank(int elo) {
        for (int i = Rank.NEMESIS.ordinal(); i >= 0; i--) {
            if (elo >= mins[i]) return Rank.values()[i];
        }
        return Rank.COPPER;
    }

    public Rank rankOf(UUID id) {
        PlayerStats s = stats.get(id);
        if (s == null) return Rank.COPPER;
        Rank base = baseRank(s.elo);
        if (base == Rank.NEMESIS && id.equals(archNemesis)) return Rank.ARCHNEMESIS;
        return base;
    }

    public UUID archNemesis() {
        return archNemesis;
    }

    /** Effect amplifier for orbs: 0 below Nemesis, 1 Nemesis, 2 ArchNemesis. */
    public int amplifierFor(UUID id) {
        Rank r = rankOf(id);
        if (r == Rank.ARCHNEMESIS) return 2;
        if (r == Rank.NEMESIS) return 1;
        return 0;
    }

    // ------------------------------------------------------------------ stats

    public PlayerStats stats(UUID id, String nameIfNew) {
        return stats.computeIfAbsent(id, k -> {
            dirty = true;
            return new PlayerStats(k, nameIfNew == null ? "Unknown" : nameIfNew);
        });
    }

    public PlayerStats statsOrNull(UUID id) {
        return stats.get(id);
    }

    public PlayerStats findByName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return stats(online.getUniqueId(), online.getName());
        for (PlayerStats s : stats.values()) {
            if (s.name.equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    public Collection<PlayerStats> all() {
        return stats.values();
    }

    public List<PlayerStats> top(int n) {
        List<PlayerStats> list = new ArrayList<>(stats.values());
        list.sort(Comparator.comparingInt((PlayerStats s) -> s.elo).reversed().thenComparing(s -> s.name.toLowerCase(Locale.ROOT)));
        return list.size() > n ? list.subList(0, n) : list;
    }

    public void touch(Player p) {
        PlayerStats s = stats(p.getUniqueId(), p.getName());
        if (!s.name.equals(p.getName())) {
            s.name = p.getName();
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ kills

    public void handleKill(Player killer, Player victim) {
        UUID kid = killer.getUniqueId(), vid = victim.getUniqueId();
        if (kid.equals(vid)) return;
        int gain = 0, loss = 0;
        Rank killerBefore, killerAfter, victimBefore, victimAfter;
        int killerElo, victimElo;
        long cooldownLeft = 0;

        synchronized (lock) {
            PlayerStats k = stats(kid, killer.getName());
            PlayerStats v = stats(vid, victim.getName());
            k.kills++;
            v.deaths++;
            dirty = true;
            long now = System.currentTimeMillis();
            String key = kid + "|" + vid;
            Long until = cooldowns.get(key);
            killerBefore = rankOf(kid);
            victimBefore = rankOf(vid);
            if (until != null && until > now) {
                cooldownLeft = until - now;
            } else {
                int vo = victimBefore.ordinal(), ko = killerBefore.ordinal();
                // killer's gain depends on the victim's rank, victim's loss depends on the killer's rank
                int g, l;
                if (vo > ko) g = 3; else if (vo == ko) g = 2; else g = 1;          // victim higher / same / lower
                if (ko > vo) l = 1; else if (ko == vo) l = 2; else l = 3;          // killer higher / same / lower
                int oldVictim = v.elo;
                k.elo += g;
                v.elo = Math.max(0, v.elo - l);
                gain = g;
                loss = oldVictim - v.elo;
                cooldowns.put(key, now + plugin.getConfig().getLong("elo.kill-cooldown-seconds", 259200L) * 1000L);
                recalcArchNemesis();
            }
            killerAfter = rankOf(kid);
            victimAfter = rankOf(vid);
            killerElo = k.elo;
            victimElo = v.elo;
        }

        if (cooldownLeft > 0) {
            final long left = cooldownLeft;
            killer.getScheduler().run(plugin, t -> plugin.msg().send(killer, "kill-cooldown",
                    Msg.str("player", victim.getName()), Msg.str("time", Msg.duration(left))), null);
            return;
        }
        final int g = gain, l = loss;
        notifyChange(killer, true, g, killerElo, killerBefore, killerAfter);
        notifyChange(victim, false, l, victimElo, victimBefore, victimAfter);
        refreshDisplay(killer);
        refreshDisplay(victim);
    }

    private void notifyChange(Player p, boolean gain, int amount, int elo, Rank before, Rank after) {
        p.getScheduler().run(plugin, t -> {
            if (amount > 0 || gain) {
                plugin.msg().send(p, gain ? "elo-gain" : "elo-loss",
                        Msg.str("amount", String.valueOf(amount)), Msg.str("elo", String.valueOf(elo)),
                        Msg.comp("rank", after.full()));
            }
            if (after.ordinal() > before.ordinal()) {
                plugin.msg().send(p, "rank-up", Msg.comp("rank", after.full()));
            } else if (after.ordinal() < before.ordinal()) {
                plugin.msg().send(p, "rank-down", Msg.comp("rank", after.full()));
            }
        }, null);
    }

    // ------------------------------------------------------------------ admin operations

    public void setElo(PlayerStats s, int elo) {
        synchronized (lock) {
            s.elo = Math.max(0, elo);
            dirty = true;
            recalcArchNemesis();
        }
        refreshOnline(s.id);
    }

    public void reset(PlayerStats s) {
        synchronized (lock) {
            s.elo = 0;
            s.kills = 0;
            s.deaths = 0;
            dirty = true;
            recalcArchNemesis();
        }
        refreshOnline(s.id);
    }

    public void resetAll() {
        synchronized (lock) {
            for (PlayerStats s : stats.values()) {
                s.elo = 0;
                s.kills = 0;
                s.deaths = 0;
            }
            cooldowns.clear();
            dirty = true;
            recalcArchNemesis();
        }
        refreshAllDisplays();
    }

    /** Removes every active cooldown where the player is killer or victim. Returns how many were removed. */
    public int removeCooldowns(UUID id) {
        int removed = 0;
        String s = id.toString();
        synchronized (lock) {
            Iterator<String> it = cooldowns.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                String[] parts = key.split("\\|");
                if (parts.length == 2 && (parts[0].equals(s) || parts[1].equals(s))) {
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0) dirty = true;
        }
        return removed;
    }

    /** Active cooldowns involving id: entries of {killerId, victimId, remainingMillis}. */
    public List<Object[]> activeCooldowns(UUID id) {
        List<Object[]> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        String s = id.toString();
        for (Map.Entry<String, Long> e : cooldowns.entrySet()) {
            if (e.getValue() <= now) continue;
            String[] parts = e.getKey().split("\\|");
            if (parts.length != 2) continue;
            if (parts[0].equals(s) || parts[1].equals(s)) {
                out.add(new Object[]{UUID.fromString(parts[0]), UUID.fromString(parts[1]), e.getValue() - now});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ ArchNemesis

    /** Must be called while holding {@code lock}. */
    private void recalcArchNemesis() {
        int need = mins[Rank.NEMESIS.ordinal()];
        UUID best = null;
        int bestElo = -1;
        for (PlayerStats s : stats.values()) {
            if (s.elo < need) continue;
            boolean take;
            if (best == null || s.elo > bestElo) take = true;
            else if (s.elo == bestElo) {
                if (s.id.equals(archNemesis)) take = true;                       // current holder keeps ties
                else if (best.equals(archNemesis)) take = false;
                else take = s.id.toString().compareTo(best.toString()) < 0;      // deterministic otherwise
            } else take = false;
            if (take) {
                best = s.id;
                bestElo = s.elo;
            }
        }
        if (!Objects.equals(best, archNemesis)) {
            archNemesis = best;
            dirty = true;
            if (best != null) {
                PlayerStats s = stats.get(best);
                Component c = plugin.msg().get("archnemesis-new", Msg.str("player", s.name), Msg.comp("rank", Rank.ARCHNEMESIS.full()));
                Bukkit.getServer().sendMessage(c);
            } else {
                Bukkit.getServer().sendMessage(plugin.msg().get("archnemesis-none"));
            }
            refreshAllDisplays();
        }
    }

    // ------------------------------------------------------------------ displays (tab list)

    public void refreshOnline(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) refreshDisplay(p);
    }

    public void refreshAllDisplays() {
        for (Player p : Bukkit.getOnlinePlayers()) refreshDisplay(p);
    }

    public void refreshDisplay(Player p) {
        p.getScheduler().run(plugin, t -> applyListName(p), null);
    }

    /** Must run on the player's own thread. Called again after respawn and every few seconds. */
    public void applyListName(Player p) {
        if (!plugin.getConfig().getBoolean("chat.show-rank-in-tab", true)) return;
        Rank r = rankOf(p.getUniqueId());
        p.playerListName(Component.text().append(r.iconComponent()).append(Component.space())
                .append(Component.text(p.getName())).build());
    }

    // ------------------------------------------------------------------ persistence

    public void load() {
        loadThresholds();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var sec = y.getConfigurationSection("players");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    PlayerStats s = new PlayerStats(id, sec.getString(key + ".name", "Unknown"));
                    s.elo = sec.getInt(key + ".elo");
                    s.kills = sec.getInt(key + ".kills");
                    s.deaths = sec.getInt(key + ".deaths");
                    stats.put(id, s);
                } catch (IllegalArgumentException ignored) {
                    // bad uuid key, skip
                }
            }
        }
        long now = System.currentTimeMillis();
        for (String line : y.getStringList("cooldowns")) {
            String[] p = line.split(";");
            if (p.length == 3) {
                try {
                    long until = Long.parseLong(p[2]);
                    if (until > now) cooldowns.put(p[0] + "|" + p[1], until);
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        String arch = y.getString("archnemesis");
        if (arch != null && !arch.isEmpty()) {
            try { archNemesis = UUID.fromString(arch); } catch (IllegalArgumentException ignored) { }
        }
        synchronized (lock) {
            UUID before = archNemesis;
            recalcArchNemesisSilently();
            if (!Objects.equals(before, archNemesis)) dirty = true;
        }
    }

    private void recalcArchNemesisSilently() {
        int need = mins[Rank.NEMESIS.ordinal()];
        UUID best = null;
        int bestElo = -1;
        for (PlayerStats s : stats.values()) {
            if (s.elo < need) continue;
            if (best == null || s.elo > bestElo || (s.elo == bestElo && s.id.equals(archNemesis))) {
                best = s.id;
                bestElo = s.elo;
            }
        }
        archNemesis = best;
    }

    /** Serialises under the lock, writes outside it. */
    public void save() {
        String text;
        synchronized (lock) {
            YamlConfiguration y = new YamlConfiguration();
            for (PlayerStats s : stats.values()) {
                String base = "players." + s.id;
                y.set(base + ".name", s.name);
                y.set(base + ".elo", s.elo);
                y.set(base + ".kills", s.kills);
                y.set(base + ".deaths", s.deaths);
            }
            long now = System.currentTimeMillis();
            List<String> cds = new ArrayList<>();
            for (Map.Entry<String, Long> e : cooldowns.entrySet()) {
                if (e.getValue() > now) cds.add(e.getKey().replace('|', ';') + ";" + e.getValue());
            }
            y.set("cooldowns", cds);
            y.set("archnemesis", archNemesis == null ? "" : archNemesis.toString());
            text = y.saveToString();
            dirty = false;
        }
        try {
            plugin.getDataFolder().mkdirs();
            File tmp = new File(plugin.getDataFolder(), "data.yml.tmp");
            Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    public void saveIfDirty() {
        if (dirty) save();
    }
}
