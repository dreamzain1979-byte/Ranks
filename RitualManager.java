package dev.rankedsmp;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** The Blood Moon mace ritual. All animation is done with Block/Item Display transformations. */
public final class RitualManager {
    /** Accepted shapes of the 4 outer cores around the centre core: X, +, and a wide +. */
    private static final int[][][] PATTERNS = {
            {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}},
            {{1, 0}, {-1, 0}, {0, 1}, {0, -1}},
            {{2, 0}, {-2, 0}, {0, 2}, {0, -2}},
            {{2, 2}, {2, -2}, {-2, 2}, {-2, -2}}
    };

    private final RankedSMP plugin;
    private final Set<Ritual> active = ConcurrentHashMap.newKeySet();

    public RitualManager(RankedSMP plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ validation

    /** Returns the matching outer-core offsets, or null if the cores are not arranged correctly. */
    public int[][] findPattern(Block c) {
        if (c.getType() != Material.HEAVY_CORE) return null;
        for (int[][] pattern : PATTERNS) {
            boolean ok = true;
            for (int[] o : pattern) {
                if (c.getRelative(o[0], 0, o[1]).getType() != Material.HEAVY_CORE) {
                    ok = false;
                    break;
                }
            }
            if (ok) return pattern;
        }
        return null;
    }

    public boolean isPattern(Block c) {
        return findPattern(c) != null;
    }

    public boolean tryStart(Player p, Block center, boolean verbose) {
        return tryStart(p, center, verbose, false);
    }

    /** @param verbose send failure reasons (right click) or stay silent (left click); @param force admin test: skip Blood Moon and rank */
    public boolean tryStart(Player p, Block center, boolean verbose, boolean force) {
        int[][] pattern = findPattern(center);
        if (plugin.getConfig().getBoolean("ritual.debug", true)) {
            plugin.getLogger().info("[ritual] " + p.getName() + " used a Mace on a Heavy Core: pattern=" + (pattern != null)
                    + ", " + plugin.bloodMoon().describe() + ", rank=" + plugin.ranks().rankOf(p.getUniqueId()) + ", force=" + force);
        }
        if (pattern == null) {
            if (verbose) plugin.msg().send(p, "ritual-bad-pattern");
            return false;
        }
        World w = center.getWorld();
        if (!force && (!plugin.bloodMoon().isBloodMoonNow() || !w.equals(plugin.bloodMoon().overworld()))) {
            if (verbose) plugin.msg().send(p, "ritual-no-bloodmoon");
            return false;
        }
        Rank r = plugin.ranks().rankOf(p.getUniqueId());
        if (!force && r.ordinal() < Rank.NETHERITE.ordinal()) {
            if (verbose) plugin.msg().send(p, "ritual-bad-rank");
            return false;
        }
        for (Ritual other : active) {
            if (other.world.equals(w) && Math.abs(other.cx - center.getX()) <= 8 && Math.abs(other.cz - center.getZ()) <= 8
                    && Math.abs(other.cy - center.getY()) <= 20) {
                if (verbose) plugin.msg().send(p, "ritual-active");
                return false;
            }
        }
        Ritual ritual = new Ritual(p.getUniqueId(), center, pattern);
        active.add(ritual);
        try {
            ritual.setup();
        } catch (Throwable t) {
            plugin.getLogger().warning("Ritual setup failed: " + t);
            ritual.abort();
            plugin.msg().send(p, "ritual-failed");
            return false;
        }
        ritual.task = Bukkit.getRegionScheduler().runAtFixedRate(plugin, center.getLocation(), ritual, 1L, 1L);
        return true;
    }

    /** Called from onDisable: removes entities and puts the world back. */
    public void abortAll() {
        for (Ritual r : new ArrayList<>(active)) {
            try {
                r.abort();
            } catch (Throwable ignored) {
                // best effort during shutdown
            }
        }
    }

    // ------------------------------------------------------------------ the ritual itself

    private final class Ritual implements Consumer<ScheduledTask> {
        final UUID caster;
        final int[][] offsets;
        BlockDisplay beamGlass;
        BlockDisplay beamCore;
        final World world;
        final int cx, cy, cz;
        ScheduledTask task;
        int tick;
        boolean finished;
        boolean completed;

        final BlockDisplay[] cores = new BlockDisplay[5];
        final double[][] start = new double[5][2]; // initial x,z offsets from the centre
        final double[] startAngle = new double[5];
        final double[] ringAngle = new double[5];
        final double[] extraTurns = new double[5];
        final Map<Block, BlockData> saved = new HashMap<>();

        final double radius = plugin.getConfig().getDouble("ritual.ring-radius", 3.5);
        final double height = plugin.getConfig().getDouble("ritual.height", 8);
        final int ascentTicks = plugin.getConfig().getInt("ritual.ascent-ticks-per-core", 50);
        final int stagger = plugin.getConfig().getInt("ritual.stagger-ticks", 25);
        final int holdTicks = plugin.getConfig().getInt("ritual.ring-hold-ticks", 40);
        final int implodeTicks = plugin.getConfig().getInt("ritual.implode-ticks", 70);
        final int ascentEnd;
        final int holdEnd;
        final int implodeEnd;
        double spin;

        Ritual(UUID caster, Block center, int[][] offsets) {
            this.caster = caster;
            this.offsets = offsets;
            this.world = center.getWorld();
            this.cx = center.getX();
            this.cy = center.getY();
            this.cz = center.getZ();
            this.ascentEnd = 4 * stagger + ascentTicks;
            this.holdEnd = ascentEnd + holdTicks;
            this.implodeEnd = holdEnd + implodeTicks;
        }

        Location centerPoint() {
            return new Location(world, cx + 0.5, cy + 0.5, cz + 0.5);
        }

        void setup() {
            // slots: 0 = centre core, 1..4 = diagonal corners
            start[0] = new double[]{0, 0};
            for (int i = 0; i < 4; i++) start[i + 1] = new double[]{offsets[i][0], offsets[i][1]};
            for (int i = 0; i < 5; i++) {
                double phi = i * (2 * Math.PI / 5);
                ringAngle[i] = phi;
                double a0 = i == 0 ? 0 : Math.atan2(start[i][1], start[i][0]);
                startAngle[i] = a0;
                double delta = phi - a0;
                delta = ((delta % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
                extraTurns[i] = delta + 4 * Math.PI; // two extra full turns of spiral
            }

            // the only blocks the ritual touches are the 5 ground cores
            remember(world.getBlockAt(cx, cy, cz));
            for (int[] o : offsets) remember(world.getBlockAt(cx + o[0], cy, cz + o[1]));

            // 1) the cores become smooth display entities, the ground cores vanish
            BlockData core = Material.HEAVY_CORE.createBlockData();
            for (int i = 0; i < 5; i++) {
                final int idx = i;
                Location at = new Location(world, cx, cy, cz);
                cores[i] = world.spawn(at, BlockDisplay.class, d -> {
                    d.setBlock(core);
                    d.setPersistent(false);
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setViewRange(4f);
                    d.setDisplayWidth(80f);
                    d.setDisplayHeight(80f);
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(2);
                    d.setTransformation(transformFor(idx, new Vector3f((float) start[idx][0], 0f, (float) start[idx][1]), new Quaternionf()));
                });
            }
            world.getBlockAt(cx, cy, cz).setType(Material.AIR, false);
            for (int[] o : offsets) world.getBlockAt(cx + o[0], cy, cz + o[1]).setType(Material.AIR, false);

            // 2) red beam, made only of display entities (no blocks are placed)
            beamGlass = spawnBeam(Material.RED_STAINED_GLASS, 0.9f);
            beamCore = spawnBeam(Material.RED_CONCRETE, 0.35f);

            world.playSound(centerPoint(), "minecraft:block.beacon.activate", 2.0f, 0.6f);
            world.playSound(centerPoint(), "minecraft:entity.warden.sonic_charge", 1.5f, 0.6f);
        }

        BlockDisplay spawnBeam(Material m, float w) {
            Location at = new Location(world, cx, cy, cz);
            return world.spawn(at, BlockDisplay.class, d -> {
                d.setBlock(m.createBlockData());
                d.setPersistent(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(8f);
                d.setDisplayWidth(400f);
                d.setDisplayHeight(400f);
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(2);
                d.setTransformation(beamTransform(w, 0f));
            });
        }

        /** A very tall thin column centred on the ritual block, rotating around its own axis. */
        Transformation beamTransform(float w, float angle) {
            Quaternionf q = new Quaternionf().rotateY(angle);
            Vector3f t = new Vector3f(0.5f, 0f, 0.5f).sub(new Vector3f(w / 2f, 0f, w / 2f).rotate(q));
            return new Transformation(t, q, new Vector3f(w, 320f, w), new Quaternionf());
        }

        void updateBeam() {
            if (beamCore != null && beamCore.isValid()) {
                beamCore.setTransformation(beamTransform(0.35f, tick * 0.18f));
                beamCore.setInterpolationDelay(0);
                beamCore.setInterpolationDuration(2);
            }
            if (beamGlass != null && beamGlass.isValid()) {
                beamGlass.setTransformation(beamTransform(0.9f, -tick * 0.06f));
                beamGlass.setInterpolationDelay(0);
                beamGlass.setInterpolationDuration(2);
            }
            if (tick % 4 == 0) {
                Particle dust = Compat.particle("DUST", "REDSTONE");
                if (dust != null) {
                    world.spawnParticle(dust, cx + 0.5, cy + 0.3, cz + 0.5, 6, 0.5, 0.2, 0.5, 0.0, new Particle.DustOptions(Color.RED, 1.6f));
                    world.spawnParticle(dust, cx + 0.5, cy + 3 + (tick % 40) / 4.0, cz + 0.5, 3, 0.2, 0.5, 0.2, 0.0, new Particle.DustOptions(Color.fromRGB(255, 60, 60), 1.2f));
                }
            }
        }

        void removeBeam() {
            if (beamGlass != null) beamGlass.remove();
            if (beamCore != null) beamCore.remove();
        }


        void remember(Block b) {
            saved.putIfAbsent(b, b.getBlockData());
        }

        /** Block display origin is its corner; rotate around the block centre. */
        Transformation transformFor(int idx, Vector3f offsetFromCentre, Quaternionf q) {
            Vector3f half = new Vector3f(0.5f, 0.5f, 0.5f);
            Vector3f rotatedHalf = new Vector3f(half).rotate(q);
            Vector3f t = new Vector3f(half).add(offsetFromCentre).sub(rotatedHalf);
            return new Transformation(t, new Quaternionf(q), new Vector3f(1f, 1f, 1f), new Quaternionf());
        }

        void place(int idx, double x, double y, double z, Quaternionf q) {
            BlockDisplay d = cores[idx];
            if (d == null || !d.isValid()) return;
            d.setTransformation(transformFor(idx, new Vector3f((float) x, (float) y, (float) z), q));
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(2);
        }

        @Override
        public void accept(ScheduledTask t) {
            if (finished) {
                t.cancel();
                return;
            }
            try {
                step();
            } catch (Throwable ex) {
                plugin.getLogger().warning("Ritual error, aborting: " + ex);
                t.cancel();
                abort();
                Player p = Bukkit.getPlayer(caster);
                if (p != null) p.getScheduler().run(plugin, x -> plugin.msg().send(p, "ritual-failed"), null);
            }
        }

        void step() {
            tick++;
            boolean animate = tick % 2 == 0;
            if (animate) updateBeam();

            if (tick <= ascentEnd) {
                for (int i = 0; i < 5; i++) {
                    double s = Math.max(0, Math.min(1, (tick - i * stagger) / (double) ascentTicks));
                    double e = s * s * (3 - 2 * s);
                    double r0 = i == 0 ? 0 : Math.hypot(start[i][0], start[i][1]);
                    double r = r0 + (radius - r0) * e;
                    double ang = startAngle[i] + extraTurns[i] * e;
                    double y = height * e;
                    Quaternionf q = new Quaternionf().rotateAxis((float) (4 * Math.PI * (1 - e)), new Vector3f(1f, 0.4f, 0.7f).normalize());
                    if (animate) place(i, r * Math.cos(ang), y, r * Math.sin(ang), q);
                    if (s > 0 && s < 1 && tick % 3 == 0) {
                        trail(r * Math.cos(ang), y, r * Math.sin(ang));
                    }
                }
                if (tick % 20 == 0) world.playSound(centerPoint(), "minecraft:block.beacon.ambient", 1.5f, 1.2f);
            } else if (tick <= holdEnd) {
                spin += 0.03 + 0.05 * ((tick - ascentEnd) / (double) holdTicks);
                if (animate) ring(radius);
                if (tick % 10 == 0) world.playSound(centerPoint().add(0, height, 0), "minecraft:block.respawn_anchor.charge", 1.2f, 0.6f);
            } else if (tick <= implodeEnd) {
                double u = (tick - holdEnd) / (double) implodeTicks;
                spin += 0.10 + 0.70 * u;
                double r = radius * (1 - u * u);
                if (animate) ring(r);
                if (tick % 4 == 0) {
                    Particle ps = Compat.particle("SOUL_FIRE_FLAME");
                    if (ps != null) world.spawnParticle(ps, cx + 0.5, cy + 0.5 + height, cz + 0.5, 6, r * 0.5, 0.2, r * 0.5, 0.02);
                }
                if (tick % 6 == 0) world.playSound(centerPoint().add(0, height, 0), "minecraft:entity.evoker.prepare_summon", 1.5f, 0.5f + (float) u);
            } else {
                finish();
            }
        }

        void ring(double r) {
            Quaternionf yaw = new Quaternionf().rotateY((float) -spin);
            for (int i = 0; i < 5; i++) {
                double a = ringAngle[i] + spin;
                place(i, r * Math.cos(a), height, r * Math.sin(a), yaw);
            }
        }

        void trail(double x, double y, double z) {
            Particle p = Compat.particle("END_ROD");
            if (p != null) world.spawnParticle(p, cx + 0.5 + x, cy + 0.5 + y, cz + 0.5 + z, 2, 0.1, 0.1, 0.1, 0.01);
        }

        void finish() {
            finished = true;
            completed = true;
            if (task != null) task.cancel();
            active.remove(this);

            Location burst = centerPoint().add(0, height, 0);
            for (BlockDisplay d : cores) if (d != null) d.remove();
            removeBeam();
            restoreBlocks(false);

            Particle boom = Compat.particle("EXPLOSION_EMITTER", "EXPLOSION_HUGE");
            Particle flash = Compat.particle("FLASH");
            Particle rod = Compat.particle("END_ROD");
            Particle soul = Compat.particle("SCULK_SOUL");
            if (boom != null) world.spawnParticle(boom, burst, 2, 0.3, 0.3, 0.3, 0);
            if (flash != null) world.spawnParticle(flash, burst, 3, 0.2, 0.2, 0.2, 0);
            if (rod != null) world.spawnParticle(rod, burst, 250, 1.2, 1.2, 1.2, 0.35);
            if (soul != null) world.spawnParticle(soul, burst, 80, 1.0, 1.0, 1.0, 0.1);
            world.strikeLightningEffect(burst);               // visual only: no damage, no fire
            world.strikeLightningEffect(centerPoint());
            world.playSound(burst, "minecraft:entity.generic.explode", 3.0f, 0.6f);
            world.playSound(burst, "minecraft:entity.lightning_bolt.thunder", 3.0f, 0.8f);
            world.playSound(burst, "minecraft:block.end_portal.spawn", 1.5f, 1.4f);

            deliverOrb(burst);
        }

        /** Put the world back. If aborting, the original 5 heavy cores come back too. */
        void restoreBlocks(boolean aborting) {
            for (Map.Entry<Block, BlockData> e : saved.entrySet()) {
                if (aborting) e.getKey().setBlockData(e.getValue(), false); // put the heavy cores back
                else e.getKey().setType(Material.AIR, false);               // cores were consumed
            }
        }

        void abort() {
            if (completed) return;
            finished = true;
            if (task != null) task.cancel();
            active.remove(this);
            for (BlockDisplay d : cores) {
                if (d != null) d.remove();
            }
            removeBeam();
            restoreBlocks(true);
        }

        // -------------------------------------------------------------- un-interceptable delivery

        void deliverOrb(Location from) {
            ItemStack orb = plugin.orbs().create(OrbType.UNDERWORLD, 1);
            ItemDisplay visual = world.spawn(from, ItemDisplay.class, d -> {
                d.setItemStack(orb);
                d.setPersistent(false);
                d.setGlowing(true);
                d.setGlowColorOverride(Color.fromRGB(0x2BFF9A));
                d.setBillboard(Display.Billboard.CENTER);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(4f);
                d.setTeleportDuration(2);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.1f, 1.1f, 1.1f), new Quaternionf()));
            });

            Player caster0 = Bukkit.getPlayer(caster);
            if (caster0 == null) {
                dropLocked(from, orb);
                visual.remove();
                return;
            }
            final Vector cur = new Vector(from.getX(), from.getY(), from.getZ());
            final double[] speed = {0.12};
            final int[] iterations = {0};
            caster0.getScheduler().runAtFixedRate(plugin, t -> {
                Player p = caster0;
                iterations[0]++;
                boolean sameWorld = p.isOnline() && p.getWorld().equals(world);
                if (!sameWorld || iterations[0] > 250) {
                    t.cancel();
                    finishDelivery(p, visual, orb, p.isOnline() ? p.getLocation() : from);
                    return;
                }
                Location chest = p.getLocation().add(0, 1.1, 0);
                Vector target = new Vector(chest.getX(), chest.getY(), chest.getZ());
                Vector dir = target.clone().subtract(cur);
                double dist = dir.length();
                if (dist < 0.9) {
                    t.cancel();
                    finishDelivery(p, visual, orb, chest);
                    return;
                }
                speed[0] = Math.min(1.1, speed[0] + 0.05);
                double step = Math.min(speed[0], dist);
                cur.add(dir.normalize().multiply(step));
                Location next = new Location(world, cur.getX(), cur.getY(), cur.getZ());
                visual.teleportAsync(next);
                Particle rod = Compat.particle("END_ROD");
                if (rod != null) world.spawnParticle(rod, next, 2, 0.05, 0.05, 0.05, 0.0);
            }, () -> {
                visual.getScheduler().run(plugin, x -> visual.remove(), null);
                dropLocked(from, orb);
            }, 1L, 2L);
        }

        void finishDelivery(Player p, ItemDisplay visual, ItemStack orb, Location where) {
            visual.getScheduler().run(plugin, x -> visual.remove(), null);
            if (!p.isOnline()) {
                dropLocked(where, orb);
                return;
            }
            Map<Integer, ItemStack> left = p.getInventory().addItem(orb);
            for (ItemStack rest : left.values()) {
                p.getWorld().dropItem(p.getLocation(), rest, it -> lock(it, p.getUniqueId()));
            }
            p.playSound(p.getLocation(), "minecraft:entity.item.pickup", 1.0f, 0.8f);
            p.playSound(p.getLocation(), "minecraft:entity.player.levelup", 0.8f, 0.6f);
        }

        void dropLocked(Location at, ItemStack orb) {
            Bukkit.getRegionScheduler().run(plugin, at, t -> world.dropItem(at, orb, it -> lock(it, caster)));
        }

        void lock(Item it, UUID owner) {
            it.setOwner(owner);           // only this UUID can ever pick it up
            it.setUnlimitedLifetime(true);
            it.setGlowing(true);
        }
    }
}
