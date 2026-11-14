package dev.rankedsmp;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class RitualListener implements Listener {
    private final RankedSMP plugin;

    public RitualListener(RankedSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.HEAVY_CORE) return;
        Player p = e.getPlayer();
        if (p.getInventory().getItemInMainHand().getType() != Material.MACE) return;

        boolean right = a == Action.RIGHT_CLICK_BLOCK;
        if (!right) {
            // left click ("strike") only counts when everything is valid, so cores can still be mined normally
            if (plugin.ritual().isPattern(b) && plugin.ritual().tryStart(p, b, false)) {
                e.setCancelled(true);
            }
            return;
        }
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setCancelled(true);
        plugin.ritual().tryStart(p, b, true);
    }
}
