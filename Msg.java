package dev.rankedsmp;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Config-driven MiniMessage messages. Every message may use &lt;prefix&gt;. */
public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final RankedSMP plugin;

    public Msg(RankedSMP plugin) {
        this.plugin = plugin;
    }

    public Component get(String key, TagResolver... resolvers) {
        String raw = plugin.getConfig().getString("messages." + key, "<red>Missing message: " + key);
        String prefix = plugin.getConfig().getString("prefix", "");
        return MM.deserialize(raw,
                TagResolver.resolver(resolvers),
                Placeholder.parsed("prefix", prefix));
    }

    public void send(Audience to, String key, TagResolver... resolvers) {
        if (plugin.getConfig().getString("messages." + key, "x").isEmpty()) return; // empty message = silent
        to.sendMessage(get(key, resolvers));
    }

    public static TagResolver str(String name, String value) {
        return Placeholder.unparsed(name, value);
    }

    public static TagResolver comp(String name, Component value) {
        return Placeholder.component(name, value);
    }

    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        return Math.max(1, m) + "m";
    }
}
