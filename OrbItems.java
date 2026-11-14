package dev.rankedsmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class OrbItems {
    private final RankedSMP plugin;
    private final NamespacedKey orbKey;

    public OrbItems(RankedSMP plugin) {
        this.plugin = plugin;
        this.orbKey = new NamespacedKey(plugin, "orb");
    }

    private Material material() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("orbs.material", "HEART_OF_THE_SEA"));
        return m == null ? Material.HEART_OF_THE_SEA : m;
    }

    public ItemStack create(OrbType type, int amount) {
        ItemStack item = new ItemStack(material(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(type.display, type.color).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        if (type.effectKey != null) {
            lore.add(Component.text("Carry it in your inventory to gain", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(type.effectName + " I", type.color).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Nemesis: II  -  ArchNemesis: III", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Carry it in the Underworld to", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("break the Adventure Mode curse.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(orbKey, PersistentDataType.STRING, type.name());

        meta.setItemModel(new NamespacedKey("rankedsmp", type.model));
        try { meta.setMaxStackSize(1); } catch (Throwable ignored) { }
        try { meta.setEnchantmentGlintOverride(true); } catch (Throwable ignored) { }

        item.setItemMeta(meta);
        return item;
    }

    public OrbType identify(ItemStack stack) {
        if (stack == null || stack.getType() != material() || !stack.hasItemMeta()) return null;
        String v = stack.getItemMeta().getPersistentDataContainer().get(orbKey, PersistentDataType.STRING);
        if (v == null) return null;
        try {
            return OrbType.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Every orb type found anywhere in the player's inventory (storage, hotbar, armor, offhand). */
    public Set<OrbType> held(Player p) {
        Set<OrbType> out = EnumSet.noneOf(OrbType.class);
        Material m = material();
        for (ItemStack s : p.getInventory().getContents()) {
            if (s == null || s.getType() != m) continue;
            OrbType t = identify(s);
            if (t != null) out.add(t);
        }
        return out;
    }
}
