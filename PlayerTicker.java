package dev.rankedsmp;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One global 1-tick dispatcher that hands each player's work to that player's own scheduler (Folia-safe):
 *  - nether fall detection (every tick)
 *  - Underworld game-mode enforcement + silent Orb bypass (every tick while in the Underworld)
 *  - orb potion effects (every 5 ticks)
 */
public final class PlayerTicker {
    private static final int EFFECT_DURATION = 120;
    private static final int EFFECT_REFRESH_BELOW = 70;

    private final RankedSMP plugin;
    private final NamespacedKey prevModeKey;
    private final Map<UUID, Map<OrbType, Integer>> applied = new ConcurrentHashMap<>();
    private long counter;

    public PlayerTicker(RankedSMP plugin) {
        this.plugin = plugin;
        this.prevModeKey = new NamespacedKey(plugin, "prev_gamemode");
    }

    public void start() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
            counter++;
            final boolean slow = counter % 5 == 0;
            final boolean names = counter % 100 == 0;
            final boolean tagTick = counter % 2 == 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.getScheduler().run(plugin, task -> tickPlayer(p, slow, names, tagTick), null);
            }
        }, 1L, 1L);
    }

    public void forget(UUID id) {
        applied.remove(id);
        plugin.nametags().remove(id);
    }

    private void tickPlayer(Player p, boolean slow, boolean names, boolean tagTick) {
        if (!p.isOnline()) return;
        if (p.isDead()) {
            plugin.nametags().remove(p);
            return;
        }
        if (tagTick) plugin.nametags().tick(p);
        if (names) plugin.ranks().applyListName(p);
        World w = p.getWorld();
        UnderworldManager uw = plugin.underworld();

        if (plugin.getConfig().getBoolean("underworld.teleport-on-fall", true) && uw.isNether(w) && p.getLocation().getY() < plugin.getConfig().getInt("underworld.fall-trigger-y", 0)) {
            uw.sendDown(p);
        }

        Set<OrbType> held = null;
        if (uw.isUnderworld(w)) {
            held = plugin.orbs().held(p);
            enforceMode(p, held.contains(OrbType.UNDERWORLD));
        } else {
            restoreMode(p);
        }

        if (slow) {
            if (held == null) held = plugin.orbs().held(p);
            applyEffects(p, held);
        }
    }

    // ------------------------------------------------------------------ game mode

    private void enforceMode(Player p, boolean hasUnderworldOrb) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (!pdc.has(prevModeKey, PersistentDataType.STRING)) {
            pdc.set(prevModeKey, PersistentDataType.STRING, gm.name());
        }
        GameMode target = hasUnderworldOrb ? GameMode.SURVIVAL : GameMode.ADVENTURE;
        if (gm != target) p.setGameMode(target); // silent: no message, no actionbar
    }

    private void restoreMode(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        String prev = pdc.get(prevModeKey, PersistentDataType.STRING);
        if (prev == null) return;
        pdc.remove(prevModeKey);
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        try {
            GameMode old = GameMode.valueOf(prev);
            if (old != GameMode.CREATIVE && old != GameMode.SPECTATOR && gm != old) p.setGameMode(old);
        } catch (IllegalArgumentException ignored) {
            // unknown stored value
        }
    }

    // ------------------------------------------------------------------ orb potion effects

    private void applyEffects(Player p, Set<OrbType> held) {
        int amp = plugin.ranks().amplifierFor(p.getUniqueId());
        Map<OrbType, Integer> prev = applied.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(OrbType.class));
        for (OrbType t : OrbType.values()) {
            if (t.effectKey == null) continue;
            PotionEffectType type = Compat.effect(t.effectKey);
            if (type == null) continue;
            Integer old = prev.get(t);
            if (held.contains(t)) {
                if (old != null && old != amp) {
                    p.removePotionEffect(type); // rank changed: replace cleanly
                }
                PotionEffect cur = p.getPotionEffect(type);
                boolean need = cur == null
                        || cur.getAmplifier() < amp
                        || (cur.getAmplifier() == amp && cur.getDuration() <= EFFECT_REFRESH_BELOW);
                if (need) {
                    p.addPotionEffect(new PotionEffect(type, EFFECT_DURATION, amp, true, false, true));
                }
                if (cur == null || need) prev.put(t, amp);
            } else if (old != null) {
                PotionEffect cur = p.getPotionEffect(type);
                if (cur != null && cur.getAmplifier() == old && cur.isAmbient()) {
                    p.removePotionEffect(type); // orb left the inventory: effect disappears immediately
                }
                prev.remove(t);
            }
        }
    }
}
