package dev.rankedsmp;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/** Ranks in ascending order. ARCHNEMESIS is dynamic (single holder). */
public enum Rank {
    COPPER("Copper", "\uE001", "#C8693C"),
    IRON("Iron", "\uE002", "#C9D1D6"),
    GOLD("Gold", "\uE003", "#FFC93C"),
    PLATINUM("Platinum", "\uE004", "#F2EBD0"),
    DIAMOND("Diamond", "\uE005", "#3CC8E6"),
    NETHERITE("Netherite", "\uE006", "#B5503C"),
    NEMESIS("Nemesis", "\uE007", "#A855F7"),
    ARCHNEMESIS("ArchNemesis", "\uE008", "#3B82F6");

    /** Custom font defined by the resource pack (assets/rankedsmp/font/rank.json). */
    public static final Key FONT = Key.key("rankedsmp", "rank");

    public final String display;
    public final String icon;
    public final TextColor color;

    Rank(String display, String icon, String hex) {
        this.display = display;
        this.icon = icon;
        this.color = TextColor.fromHexString(hex);
    }

    public Component iconComponent() {
        return Component.text(icon).font(FONT).color(NamedTextColor.WHITE);
    }

    public Component nameComponent() {
        return Component.text(display, color);
    }

    /** Icon + space + coloured name. */
    public Component full() {
        return Component.text().append(iconComponent()).append(Component.space()).append(nameComponent()).build();
    }
}
