package dev.rankedsmp;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.UUID;

public final class PlayerListener implements Listener {
    private final RankedSMP plugin;

    public PlayerListener(RankedSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.ranks().touch(p);
        plugin.ranks().refreshDisplay(p);
        String url = plugin.getConfig().getString("resource-pack.url", "");
        if (url != null && !url.isEmpty()) {
            String sha1 = plugin.getConfig().getString("resource-pack.sha1", "");
            try {
                if (sha1 != null && !sha1.isEmpty()) p.setResourcePack(url, sha1);
                else p.setResourcePack(url);
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not send resource pack: " + t.getMessage());
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        // the tab name is reset by vanilla on respawn: put the rank icon back
        p.getScheduler().runDelayed(plugin, t -> plugin.ranks().applyListName(p), null, 5L);
        p.getScheduler().runDelayed(plugin, t -> plugin.ranks().applyListName(p), null, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.ticker().forget(e.getPlayer().getUniqueId());
        plugin.nametags().remove(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            plugin.ranks().handleKill(killer, victim);
        } else if (plugin.getConfig().getBoolean("elo.count-all-deaths", false)) {
            PlayerStats s = plugin.ranks().stats(victim.getUniqueId(), victim.getName());
            s.deaths++;
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("chat.show-rank-in-chat", true)) return;
        e.renderer(ChatRenderer.viewerUnaware((source, name, message) -> {
            Rank r = plugin.ranks().rankOf(source.getUniqueId());
            return Component.text().append(r.iconComponent()).append(Component.space())
                    .append(name).append(Component.text(": ")).append(message).build();
        }));
    }

    /** New nether chunks lose their floor bedrock so players can fall into the Underworld. */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!e.isNewChunk() || !plugin.getConfig().getBoolean("underworld.remove-nether-floor-bedrock", true)) return;
        World w = e.getWorld();
        if (!plugin.underworld().isNether(w)) return;
        Chunk chunk = e.getChunk();
        final int minY = w.getMinHeight();
        plugin.getServer().getRegionScheduler().run(plugin, w, chunk.getX(), chunk.getZ(), t -> {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y <= minY + 4; y++) {
                        if (chunk.getBlock(x, y, z).getType() == Material.BEDROCK) {
                            chunk.getBlock(x, y, z).setType(Material.AIR, false);
                        }
                    }
                }
            }
        });
    }
}
