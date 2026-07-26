# SmartCreeper

[![Modrinth](https://img.shields.io/badge/Modrinth-SmartCreeper-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/mod/SmartCreeper)
[![License](https://img.shields.io/badge/license-All%20Rights%20Reserved-red)](https://github.com/sudoMR-OK/SmartCreeper/main/LICENSE)

A Fabric mod that gives Creepers an actual brain. They sneak, flee, tower up with TNT, and sometimes just stand behind you and vibe until you turn around.

This is my first ever mod project. I'm not a professional, not even an amateur, just a beginner with very little experience :). Built half by AI, half by me (barely).

## Features

### Sneaky Creeper AI

- Checks whether the player can see it. Spotted? It flees at 2x speed to find cover. Unspotted? It sneaks up at 1.2x speed.
- If the target is high up and there's no path up, it switches to towering mode. (They can tower up now •_•)

### Personality

Sometimes a Creeper won't explode at all. It'll get behind you and stand completely still until you look at it, then instantly flee. Sometimes it hita you, just to make you turn around.

While it's behind you, it might hiss like it's about to blow up. You panic, turn around, and it flees and hides all over again.

### Door & Trapdoor Interaction

- Can open wooden doors and trapdoors and path through them. 

### Towering

- If the target is 2 or more blocks above and there's no path up, it towers using TNT to climb.
- If a ceiling blocks the way, it bridges across, moves to open ground, and keeps climbing.

### Core Changes

- Detection range extended to 64 blocks.
- Can now punch players in melee.
- Spawns with 64 TNT already loaded in inventory. (Free TNT, assuming you can actually kill it first.)

## Requirements

- Minecraft: `1.21.1`
- Fabric Loader: `>=0.16.4`
- Fabric API: `>=0.116.9+1.21.1`

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for your Minecraft version.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api).
3. Download SmartCreeper from [Modrinth](https://modrinth.com/mod/SmartCreeper).
4. Drop the jar into your `mods` folder.

## Project Structure

```
src/main/java/com/main/smartcreeperai/
├── SmartCreeperMod.java                Initializer
├── SneakyCreeperGoal.java              Stealth AI
├── CreeperOpenDoorGoal.java            Door Interaction
├── CreeperInteractTrapdoorGoal.java    Trapdoor Interaction
├── CreeperTowerGoal.java               Towering AI
├── CreeperInventoryProvider.java       Inventory
└── mixin/
    ├── CreeperEntityMixin.java         Goal Injections
    └── MobEntityMixin.java             Spawn with TNT
```

## License

All Rights Reserved. The source is shown here for transparency, not for reuse. Copying, modifying, or redistributing any part of this code without permission isn't allowed. To actually use SmartCreeper in your game, grab the compiled build from Modrinth.
