# RankedSMP for Minecraft 26.3 (Paper / Purpur / Folia)

## Build the .jar
Needs JDK 25 and Maven.

    mvn clean package          # -> target/RankedSMP.jar

No Maven? Push this folder to a GitHub repo: the included workflow (`.github/workflows/build.yml`) builds it on
every push; download `RankedSMP-jar` from the Actions run's Artifacts.

Drop `RankedSMP.jar` in `plugins/` and restart.

## Target
Minecraft **26.3** only (Paper API `26.3.build.243-stable`, Java 25). It also runs on Folia.

## Underworld world
The plugin generates its own muddy Underworld (world `world_underworld`): five biomes (soul lowlands, verdant wastes,
ashen wastes with deep ravines, crimson depths with lava, snowbound grove with spruces), ruined vaults and obelisks with diamonds.
1. Copy `RankedSMP-Biomes-Datapack` into `<main world>/datapacks/` and restart (gives the real teal sky/fog/ash colours).
2. DELETE any old `world_underworld` folder so the new terrain generates, then restart.
3. Paper/Purpur create the world automatically. Folia cannot create worlds at runtime.
It also still recognises a real datapack dimension in the `unstable_underworld` namespace if you use one.

## Ritual
Mace + right click on the middle of 5 Heavy Cores laid on the ground in a **+** (or X) shape, during a Blood Moon, as Netherite/Nemesis/ArchNemesis.
Silent (no chat messages). `/ritual status` shows why it would or wouldn't start, `/ritual start` force-tests it. Red beam, cores spiral up, ring facing down, spin inward, Underworld Orb flies to the caster (locked to them).

## Commands
`/rank [player]`, `/ranktop`, `/rankcooldown remove <player>`, `/rankreset <player|all>`, `/rankset <player> <elo>`,
`/orb give <player> <orb> [amount]`, `/orb list`, `/underworld leave|enter [player]`.
Admin commands need `underworld.admin` (OP by default).

## Resource pack
Zip the *contents* of `RankedSMP-ResourcePack` (so `pack.mcmeta` is at the zip root), host it, and set
`resource-pack.url` / `sha1` in `config.yml`, or use `server.properties`.
Rank icons use their own font `rankedsmp:rank` (characters U+E001–U+E008). A pack must not overwrite
`minecraft:font/default.json`, because that would delete every vanilla character.
