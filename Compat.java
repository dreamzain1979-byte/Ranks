package dev.rankedsmp;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version bridge. Bukkit renamed several constants between 1.21.x and 26.x (and turned some enums into
 * registry-backed interfaces), so everything that has ever been renamed is looked up reflectively.
 */
public final class Compat {
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();

    private Compat() {}

    /** Potion effect by vanilla key, e.g. "strength", "water_breathing". */
    public static PotionEffectType effect(String key) {
        Object o = CACHE.computeIfAbsent("effect:" + key, k -> {
            try {
                PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(key));
                return t == null ? MISSING : t;
            } catch (Throwable t) {
                return MISSING;
            }
        });
        return o == MISSING ? null : (PotionEffectType) o;
    }

    /** Particle by trying several constant names (old and new). Returns null if none exists. */
    public static Particle particle(String... names) {
        String cacheKey = "particle:" + String.join(",", names);
        Object o = CACHE.computeIfAbsent(cacheKey, k -> {
            for (String n : names) {
                try {
                    Field f = Particle.class.getField(n);
                    Object v = f.get(null);
                    if (v != null) return v;
                } catch (Throwable ignored) {
                    // try next name
                }
            }
            return MISSING;
        });
        return o == MISSING ? null : (Particle) o;
    }
}
