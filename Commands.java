package dev.rankedsmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class Commands implements CommandExecutor, TabCompleter {
    private final RankedSMP plugin;

    public Commands(RankedSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "rank" -> rank(sender, args);
            case "ranktop" -> top(sender);
            case "rankcooldown" -> cooldown(sender, args);
            case "rankreset" -> reset(sender, args);
            case "rankset" -> set(sender, args);
            case "orb" -> orb(sender, args);
            case "underworld" -> underworld(sender, args);
            case "ritual" -> ritual(sender, args);
            default -> { return false; }
        }
        return true;
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("underworld.admin")) return true;
        plugin.msg().send(s, "no-permission");
        return false;
    }

    private PlayerStats find(CommandSender s, String name) {
        PlayerStats st = plugin.ranks().findByName(name);
        if (st == null) plugin.msg().send(s, "unknown-player", Msg.str("player", name));
        return st;
    }

    // ------------------------------------------------------------------ /rank, /ranktop

    private void rank(CommandSender sender, String[] args) {
        PlayerStats st;
        if (args.length >= 1) {
            st = find(sender, args[0]);
            if (st == null) return;
        } else if (sender instanceof Player p) {
            plugin.ranks().touch(p);
            st = plugin.ranks().stats(p.getUniqueId(), p.getName());
        } else {
            sender.sendMessage("Usage: /rank <player>");
            return;
        }
        Rank r = plugin.ranks().rankOf(st.id);
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text().append(Component.text("  ")).append(r.full())
                .append(Component.text("  " + st.name, NamedTextColor.WHITE)).build());
        sender.sendMessage(Component.text("  Elo: ", NamedTextColor.GRAY).append(Component.text(st.elo, NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("  Kills: ", NamedTextColor.GRAY).append(Component.text(st.kills, NamedTextColor.GREEN))
                .append(Component.text("   Deaths: ", NamedTextColor.GRAY)).append(Component.text(st.deaths, NamedTextColor.RED)));

        List<Object[]> cds = plugin.ranks().activeCooldowns(st.id);
        if (cds.isEmpty()) {
            sender.sendMessage(Component.text("  Kill cooldowns: none", NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text("  Kill cooldowns:", NamedTextColor.GRAY));
            for (Object[] c : cds) {
                UUID killer = (UUID) c[0], victim = (UUID) c[1];
                long left = (Long) c[2];
                boolean asKiller = killer.equals(st.id);
                UUID other = asKiller ? victim : killer;
                PlayerStats os = plugin.ranks().statsOrNull(other);
                String on = os == null ? "?" : os.name;
                String text = asKiller ? "    no Elo from killing " + on : "    " + on + " gets no Elo from killing " + st.name;
                sender.sendMessage(Component.text(text + " - " + Msg.duration(left), NamedTextColor.DARK_GRAY));
            }
        }
        sender.sendMessage(Component.empty());
    }

    private void top(CommandSender sender) {
        sender.sendMessage(Component.text("Top 10 - Elo Leaderboard", NamedTextColor.GOLD));
        int i = 1;
        for (PlayerStats s : plugin.ranks().top(10)) {
            Rank r = plugin.ranks().rankOf(s.id);
            sender.sendMessage(Component.text()
                    .append(Component.text("#" + i + " ", NamedTextColor.DARK_GRAY))
                    .append(r.iconComponent()).append(Component.space())
                    .append(Component.text(s.name, NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(r.nameComponent())
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(s.elo + " Elo", NamedTextColor.YELLOW)).build());
            i++;
        }
        if (i == 1) sender.sendMessage(Component.text("No ranked players yet.", NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------------ admin rank commands

    private void cooldown(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length != 2 || !args[0].equalsIgnoreCase("remove")) {
            sender.sendMessage(Component.text("Usage: /rankcooldown remove <player>", NamedTextColor.RED));
            return;
        }
        PlayerStats st = find(sender, args[1]);
        if (st == null) return;
        int n = plugin.ranks().removeCooldowns(st.id);
        sender.sendMessage(Component.text("Removed " + n + " cooldown(s) involving " + st.name + ".", NamedTextColor.GREEN));
    }

    private void reset(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length != 1) {
            sender.sendMessage(Component.text("Usage: /rankreset <player|all>", NamedTextColor.RED));
            return;
        }
        if (args[0].equalsIgnoreCase("all")) {
            plugin.ranks().resetAll();
            sender.sendMessage(Component.text("Reset Elo and stats for everyone.", NamedTextColor.GREEN));
            return;
        }
        PlayerStats st = find(sender, args[0]);
        if (st == null) return;
        plugin.ranks().reset(st);
        sender.sendMessage(Component.text("Reset " + st.name + " to 0 Elo.", NamedTextColor.GREEN));
    }

    private void set(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length != 2) {
            sender.sendMessage(Component.text("Usage: /rankset <player> <elo>", NamedTextColor.RED));
            return;
        }
        PlayerStats st = find(sender, args[0]);
        if (st == null) return;
        int elo;
        try {
            elo = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Elo must be a whole number.", NamedTextColor.RED));
            return;
        }
        plugin.ranks().setElo(st, elo);
        Rank r = plugin.ranks().rankOf(st.id);
        sender.sendMessage(Component.text().append(Component.text(st.name + " is now at " + st.elo + " Elo: ", NamedTextColor.GREEN))
                .append(r.full()).build());
    }

    // ------------------------------------------------------------------ /orb, /underworld

    private void orb(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            StringBuilder sb = new StringBuilder();
            for (OrbType t : OrbType.values()) sb.append(t.name().toLowerCase(Locale.ROOT)).append(' ');
            sender.sendMessage(Component.text("Orbs: " + sb.toString().trim(), NamedTextColor.GRAY));
            return;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(Component.text("Usage: /orb give <player> <orb> [amount]  |  /orb list", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.msg().send(sender, "unknown-player", Msg.str("player", args[1]));
            return;
        }
        OrbType type = args.length >= 3 ? OrbType.parse(args[2]) : null;
        if (type == null) {
            sender.sendMessage(Component.text("Unknown orb. Use /orb list.", NamedTextColor.RED));
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[3]))); } catch (NumberFormatException ignored) { }
        }
        final int amt = amount;
        final OrbType ot = type;
        target.getScheduler().run(plugin, t -> {
            for (int i = 0; i < amt; i++) {
                ItemStack item = plugin.orbs().create(ot, 1);
                for (ItemStack rest : target.getInventory().addItem(item).values()) {
                    target.getWorld().dropItem(target.getLocation(), rest, it -> it.setOwner(target.getUniqueId()));
                }
            }
        }, null);
        sender.sendMessage(Component.text("Gave " + amt + "x " + type.display + " to " + target.getName() + ".", NamedTextColor.GREEN));
    }

    private void ritual(CommandSender sender, String[] args) {
        if (!admin(sender)) return;
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        org.bukkit.block.Block target = p.getTargetBlockExact(6);
        if (args.length >= 1 && args[0].equalsIgnoreCase("start")) {
            if (target == null || !plugin.ritual().isPattern(target)) {
                p.sendMessage(Component.text("Look at the MIDDLE Heavy Core of a valid + or X pattern.", NamedTextColor.RED));
                return;
            }
            plugin.ritual().tryStart(p, target, true, true);
            return;
        }
        p.sendMessage(Component.text("Ritual status: " + plugin.bloodMoon().describe(), NamedTextColor.YELLOW));
        p.sendMessage(Component.text("Your rank: " + plugin.ranks().rankOf(p.getUniqueId()).display + " (needs Netherite or higher)", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("Block you look at: " + (target == null ? "none" : target.getType().name())
                + ", valid pattern: " + (target != null && plugin.ritual().isPattern(target)), NamedTextColor.YELLOW));
        p.sendMessage(Component.text("Use /ritual start to force-test the animation (ignores Blood Moon and rank).", NamedTextColor.GRAY));
    }

    private void underworld(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("enter")) {
            if (!admin(sender)) return;
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
            if (target == null) {
                sender.sendMessage(Component.text("Usage: /underworld enter [player]", NamedTextColor.RED));
                return;
            }
            plugin.underworld().sendDown(target);
            return;
        }
        // default: leave
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        if (!plugin.getConfig().getBoolean("underworld.allow-leave-command", true) && !p.hasPermission("underworld.admin")) {
            plugin.msg().send(sender, "no-permission");
            return;
        }
        World w = p.getWorld();
        if (!plugin.underworld().isUnderworld(w)) {
            sender.sendMessage(Component.text("You are not in the Underworld.", NamedTextColor.GRAY));
            return;
        }
        plugin.underworld().sendUp(p);
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String cur = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        switch (name) {
            case "rank" -> { if (args.length == 1) players(out); }
            case "rankcooldown" -> {
                if (args.length == 1) out.add("remove");
                else if (args.length == 2) players(out);
            }
            case "rankreset" -> { if (args.length == 1) { out.add("all"); players(out); } }
            case "rankset" -> { if (args.length == 1) players(out); }
            case "orb" -> {
                if (args.length == 1) { out.add("give"); out.add("list"); }
                else if (args.length == 2) players(out);
                else if (args.length == 3) for (OrbType t : OrbType.values()) out.add(t.name().toLowerCase(Locale.ROOT));
            }
            case "ritual" -> { if (args.length == 1) { out.add("status"); out.add("start"); } }
            case "underworld" -> {
                if (args.length == 1) { out.add("leave"); if (sender.hasPermission("underworld.admin")) out.add("enter"); }
                else if (args.length == 2 && args[0].equalsIgnoreCase("enter")) players(out);
            }
            default -> { }
        }
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(cur));
        return out;
    }

    private void players(List<String> out) {
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
    }
}
