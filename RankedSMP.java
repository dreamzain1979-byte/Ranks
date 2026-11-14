package dev.rankedsmp;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

public final class RankedSMP extends JavaPlugin {
    private Msg msg;
    private RankManager ranks;
    private OrbItems orbs;
    private UnderworldManager underworld;
    private BloodMoonManager bloodMoon;
    private RitualManager ritual;
    private PlayerTicker ticker;
    private NametagManager nametags;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        msg = new Msg(this);
        ranks = new RankManager(this);
        ranks.load();
        orbs = new OrbItems(this);
        underworld = new UnderworldManager(this);
        bloodMoon = new BloodMoonManager(this);
        ritual = new RitualManager(this);
        nametags = new NametagManager(this);
        ticker = new PlayerTicker(this);

        Commands commands = new Commands(this);
        for (String name : new String[]{"rank", "ranktop", "rankcooldown", "rankreset", "rankset", "orb", "underworld", "ritual"}) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(commands);
                c.setTabCompleter(commands);
            }
        }
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new RitualListener(this), this);

        // worlds can only be created once the server has finished starting
        Bukkit.getGlobalRegionScheduler().run(this, t -> {
            underworld.ensureWorld();
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) ranks.refreshDisplay(p);
        });
        bloodMoon.start();
        ticker.start();
        Bukkit.getAsyncScheduler().runAtFixedRate(this, t -> ranks.saveIfDirty(), 2, 2, TimeUnit.MINUTES);
        getLogger().info("RankedSMP enabled.");
    }

    @Override
    public void onDisable() {
        try {
            ritual.abortAll();
        } catch (Throwable ignored) {
            // shutdown best effort
        }
        try {
            nametags.removeAll();
        } catch (Throwable ignored) {
            // shutdown best effort
        }
        ranks.save();
    }

    public Msg msg() { return msg; }
    public RankManager ranks() { return ranks; }
    public OrbItems orbs() { return orbs; }
    public UnderworldManager underworld() { return underworld; }
    public BloodMoonManager bloodMoon() { return bloodMoon; }
    public RitualManager ritual() { return ritual; }
    public PlayerTicker ticker() { return ticker; }
    public NametagManager nametags() { return nametags; }
}
