package dev.rankedsmp;

import net.kyori.adventure.text.format.TextColor;

import java.util.Locale;

public enum OrbType {
    STRENGTH("Strength Orb", "strength_orb", "#FF9F1C", 1001, "strength", "Strength"),
    SPEED("Speed Orb", "speed_orb", "#22D3EE", 1002, "speed", "Speed"),
    WATER_BREATHING("Water Breathing Orb", "water_breathing_orb", "#2F7FB5", 1003, "water_breathing", "Water Breathing"),
    DOLPHINS_GRACE("Dolphin's Grace Orb", "dolphins_grace_orb", "#8EE8D8", 1004, "dolphins_grace", "Dolphin's Grace"),
    RESISTANCE("Resistance Orb", "resistance_orb", "#6C7FB0", 1005, "resistance", "Resistance"),
    FIRE_RESISTANCE("Fire Resistance Orb", "fire_resistance_orb", "#FF5A1F", 1006, "fire_resistance", "Fire Resistance"),
    REGENERATION("Regeneration Orb", "regeneration_orb", "#F472B6", 1007, "regeneration", "Regeneration"),
    HASTE("Haste Orb", "haste_orb", "#B07A4A", 1008, "haste", "Haste"),
    UNDERWORLD("Underworld Orb", "underworld_orb", "#1FA36B", 1009, null, null);

    public final String display;
    /** item model / texture name in the resource pack (namespace rankedsmp) */
    public final String model;
    public final TextColor color;
    /** legacy custom_model_data value */
    public final int customModelData;
    /** vanilla effect key, null for the Underworld Orb */
    public final String effectKey;
    public final String effectName;

    OrbType(String display, String model, String hex, int cmd, String effectKey, String effectName) {
        this.display = display;
        this.model = model;
        this.color = TextColor.fromHexString(hex);
        this.customModelData = cmd;
        this.effectKey = effectKey;
        this.effectName = effectName;
    }

    public static OrbType parse(String s) {
        if (s == null) return null;
        String n = s.toUpperCase(Locale.ROOT).replace(' ', '_').replace("'", "");
        for (OrbType t : values()) {
            if (t.name().equals(n) || t.name().replace("_ORB", "").equals(n)) return t;
        }
        return null;
    }
}
