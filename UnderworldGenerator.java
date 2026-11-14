package dev.rankedsmp;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Unstable-Underworld style world: muddy rolling terrain under a teal sky, five biomes (soul lowlands, verdant
 * wastes, ashen wastes with deep ravines, crimson depths with lava, snowbound grove with spruce trees)
 * and ruined diamond vaults scattered around.
 */
public final class UnderworldGenerator extends ChunkGenerator {
    public enum Zone {
        SOUL_LOWLANDS("soul_lowlands"),
        VERDANT_WASTES("verdant_wastes"),
        ASHEN_WASTES("ashen_wastes"),
        CRIMSON_DEPTHS("crimson_depths"),
        SNOWBOUND_GROVE("snowbound_grove");

        public final String key;

        Zone(String key) {
            this.key = key;
        }
    }

    public static final int SEA = 60;
    private static final int RAVINE_FLOOR = 14;

    private volatile SimplexOctaveGenerator zoneA, zoneB, hill, ravine, patch;
    private final Map<Zone, Biome> biomes = new EnumMap<>(Zone.class);

    private synchronized void init(long seed) {
        if (hill != null) return;
        SimplexOctaveGenerator a = new SimplexOctaveGenerator(seed + 11, 3);
        a.setScale(1.0 / 260.0);
        SimplexOctaveGenerator b = new SimplexOctaveGenerator(seed + 12, 3);
        b.setScale(1.0 / 300.0);
        SimplexOctaveGenerator r = new SimplexOctaveGenerator(seed + 13, 2);
        r.setScale(1.0 / 90.0);
        SimplexOctaveGenerator p = new SimplexOctaveGenerator(seed + 14, 2);
        p.setScale(1.0 / 20.0);
        SimplexOctaveGenerator h = new SimplexOctaveGenerator(seed, 4);
        h.setScale(1.0 / 85.0);
        zoneA = a;
        zoneB = b;
        ravine = r;
        patch = p;
        hill = h;
    }

    // ------------------------------------------------------------------ terrain model (shared by chunks, biomes, populators)

    Zone zoneAt(int x, int z) {
        double a = zoneA.noise(x, z, 0.5, 0.5, true);
        double b = zoneB.noise(x, z, 0.5, 0.5, true);
        if (b > 0.33) return Zone.SNOWBOUND_GROVE;
        if (a < -0.15) return Zone.SOUL_LOWLANDS;
        if (a < 0.2) return Zone.VERDANT_WASTES;
        return b < -0.05 ? Zone.CRIMSON_DEPTHS : Zone.ASHEN_WASTES;
    }

    int heightAt(int x, int z, Zone zone) {
        double n = hill.noise(x, z, 0.5, 0.5, true);
        int off = switch (zone) {
            case SOUL_LOWLANDS -> -5;
            case VERDANT_WASTES -> 0;
            case ASHEN_WASTES -> 2;
            case CRIMSON_DEPTHS -> 1;
            case SNOWBOUND_GROVE -> 8;
        };
        return 66 + off + (int) Math.round(n * 12);
    }

    boolean isRavine(int x, int z, Zone zone) {
        if (zone != Zone.ASHEN_WASTES && zone != Zone.SOUL_LOWLANDS) return false;
        return Math.abs(ravine.noise(x, z, 0.5, 0.5, true)) < 0.03;
    }

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        if (hill == null) init(info.getSeed());
        int minY = data.getMinHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = chunkX * 16 + x;
                int wz = chunkZ * 16 + z;
                Zone zone = zoneAt(wx, wz);
                int h = heightAt(wx, wz, zone);
                boolean cut = isRavine(wx, wz, zone) && h > RAVINE_FLOOR + 6;
                double p = patch.noise(wx, wz, 0.5, 0.5, true);

                data.setBlock(x, minY, z, Material.BEDROCK);
                data.setRegion(x, minY + 1, z, x + 1, 0, z + 1, Material.DEEPSLATE);
                data.setRegion(x, 0, z, x + 1, Math.max(1, h - 8), z + 1, Material.TUFF);
                data.setRegion(x, Math.max(0, h - 8), z, x + 1, Math.max(1, h - 3), z + 1, Material.PACKED_MUD);
                data.setRegion(x, Math.max(0, h - 3), z, x + 1, h, z + 1, Material.MUD);
                data.setBlock(x, h, z, surface(zone, p));

                if (cut) {
                    // deep ravine: carve straight down, leave a muddy floor
                    data.setRegion(x, RAVINE_FLOOR + 1, z, x + 1, h + 1, z + 1, Material.AIR);
                    data.setBlock(x, RAVINE_FLOOR, z, Material.MUD);
                } else if (h < SEA) {
                    Material fluid = zone == Zone.CRIMSON_DEPTHS ? Material.LAVA : Material.WATER;
                    data.setRegion(x, h + 1, z, x + 1, SEA + 1, z + 1, fluid);
                    if (zone == Zone.SNOWBOUND_GROVE) data.setBlock(x, SEA, z, Material.ICE);
                }
            }
        }
    }

    private static Material surface(Zone zone, double p) {
        return switch (zone) {
            case SOUL_LOWLANDS -> p > 0.1 ? Material.SOUL_SOIL : Material.MUD;
            case VERDANT_WASTES -> p > 0.0 ? Material.MOSS_BLOCK : Material.MUD;
            case ASHEN_WASTES -> p > 0.25 ? Material.TUFF : (p < -0.3 ? Material.SMOOTH_BASALT : Material.MUD);
            case CRIMSON_DEPTHS -> p > 0.15 ? Material.CRIMSON_NYLIUM : (p < -0.55 ? Material.MAGMA_BLOCK : Material.NETHERRACK);
            case SNOWBOUND_GROVE -> Material.SNOW_BLOCK;
        };
    }

    // ------------------------------------------------------------------ biomes

    private Biome biome(Zone zone) {
        synchronized (biomes) {
            Biome b = biomes.get(zone);
            if (b == null) {
                b = lookup(zone);
                biomes.put(zone, b);
            }
            return b;
        }
    }

    /** Uses the datapack biome rankedsmp:<zone> when installed, otherwise a close vanilla biome. */
    private static Biome lookup(Zone zone) {
        try {
            Biome b = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(new NamespacedKey("rankedsmp", zone.key));
            if (b != null) return b;
        } catch (Throwable ignored) {
            // fall back below
        }
        return switch (zone) {
            case SOUL_LOWLANDS -> Biome.SOUL_SAND_VALLEY;
            case VERDANT_WASTES -> Biome.WARPED_FOREST;
            case ASHEN_WASTES -> Biome.BASALT_DELTAS;
            case CRIMSON_DEPTHS -> Biome.CRIMSON_FOREST;
            case SNOWBOUND_GROVE -> Biome.SNOWY_TAIGA;
        };
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        if (hill == null) init(worldInfo.getSeed());
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo info, int x, int y, int z) {
                return biome(zoneAt(x, z));
            }

            @Override
            public List<Biome> getBiomes(WorldInfo info) {
                List<Biome> out = new java.util.ArrayList<>();
                for (Zone z : Zone.values()) out.add(biome(z));
                return out;
            }
        };
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        if (hill == null) init(world.getSeed());
        Zone z = zoneAt(0, 0);
        return new Location(world, 0.5, Math.max(heightAt(0, 0, z), SEA) + 1, 0.5);
    }

    // ------------------------------------------------------------------ structures and trees

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new Populator());
    }

    private final class Populator extends BlockPopulator {
        @Override
        public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
            if (hill == null) init(info.getSeed());
            int x0 = chunkX * 16, z0 = chunkZ * 16;
            try {
                spruces(random, region, x0, z0);
                if (random.nextInt(34) == 0) vault(random, region, x0 + 8, z0 + 8);
                else if (random.nextInt(16) == 0) obelisk(random, region, x0 + 2 + random.nextInt(12), z0 + 2 + random.nextInt(12));
            } catch (Throwable t) {
                // a structure failing must never stop chunk generation
            }
        }

        private void spruces(Random random, LimitedRegion region, int x0, int z0) {
            for (int i = 0; i < 5; i++) {
                int x = x0 + 2 + random.nextInt(12), z = z0 + 2 + random.nextInt(12);
                if (zoneAt(x, z) != Zone.SNOWBOUND_GROVE) continue;
                int h = heightAt(x, z, Zone.SNOWBOUND_GROVE);
                if (h < SEA + 1) continue;
                region.setType(x, h, z, Material.PODZOL);
                region.generateTree(new Location(null, x, h + 1, z), random, TreeType.REDWOOD);
            }
        }

        private void obelisk(Random random, LimitedRegion region, int x, int z) {
            Zone zone = zoneAt(x, z);
            int h = heightAt(x, z, zone);
            if (h < SEA + 1 || isRavine(x, z, zone)) return;
            for (int y = 1; y <= 6; y++) {
                region.setType(x, h + y, z, y % 2 == 0 ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_BRICKS);
            }
            region.setType(x, h + 7, z, Material.DIAMOND_BLOCK);
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (random.nextInt(3) == 0) region.setType(x + o[0], h, z + o[1], Material.DEEPSLATE_DIAMOND_ORE);
            }
        }

        /** 9x9 ruined vault: broken brick walls, tall corner pillars capped with diamond blocks, loot chest on a diamond block. */
        private void vault(Random random, LimitedRegion region, int cx, int cz) {
            Zone zone = zoneAt(cx, cz);
            int h = heightAt(cx, cz, zone);
            if (h < SEA + 1 || isRavine(cx, cz, zone)) return;
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    int x = cx + dx, z = cz + dz;
                    // flatten the footprint
                    for (int y = h + 1; y <= h + 8; y++) region.setType(x, y, z, Material.AIR);
                    for (int y = h - 3; y < h; y++) region.setType(x, y, z, Material.DEEPSLATE_BRICKS);
                    region.setType(x, h, z, random.nextInt(4) == 0 ? Material.CRACKED_DEEPSLATE_TILES : Material.DEEPSLATE_TILES);

                    boolean edge = Math.abs(dx) == 4 || Math.abs(dz) == 4;
                    boolean corner = Math.abs(dx) == 4 && Math.abs(dz) == 4;
                    boolean door = dx == 0 || dz == 0;
                    if (corner) {
                        for (int y = 1; y <= 5; y++) region.setType(x, h + y, z, Material.POLISHED_DEEPSLATE);
                        region.setType(x, h + 6, z, Material.DIAMOND_BLOCK);
                    } else if (edge && !door) {
                        int wall = 1 + random.nextInt(3);
                        for (int y = 1; y <= wall; y++) {
                            int roll = random.nextInt(100);
                            Material m = roll < 8 ? Material.DEEPSLATE_DIAMOND_ORE
                                    : roll < 35 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS;
                            region.setType(x, h + y, z, m);
                        }
                    }
                }
            }
            // centre: diamond block podium + loot chest
            region.setType(cx, h, cz, Material.DIAMOND_BLOCK);
            region.setType(cx, h + 1, cz, Material.CHEST);
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                region.setType(cx + o[0] * 2, h, cz + o[1] * 2, Material.DEEPSLATE_DIAMOND_ORE);
            }
            BlockState st = region.getBlockState(cx, h + 1, cz);
            if (st instanceof Chest chest) {
                chest.getBlockInventory().addItem(new ItemStack(Material.DIAMOND, 4 + random.nextInt(6)));
                chest.getBlockInventory().addItem(new ItemStack(Material.IRON_INGOT, 3 + random.nextInt(8)));
                if (random.nextInt(3) == 0) chest.getBlockInventory().addItem(new ItemStack(Material.DIAMOND_PICKAXE));
                if (random.nextInt(4) == 0) chest.getBlockInventory().addItem(new ItemStack(Material.DIAMOND_CHESTPLATE));
                if (random.nextInt(6) == 0) chest.getBlockInventory().addItem(new ItemStack(Material.DIAMOND_BLOCK, 1 + random.nextInt(2)));
                chest.update(true);
            }
        }
    }
}
