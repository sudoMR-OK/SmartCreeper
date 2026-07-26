# Smart Creeper
### Available on [Modrinth](https://modrinth.com/mod/SmartCreeper)
This is my first ever Minecraft mod project, a fabric mod.  
I'm not a professional, nor an amateur, just a beginner with a very little bit of experience :).  
This is my first ever mod project which is half worked by AI, half by me (barely).

## How it works  

## Features

### Sneaky Creeper AI  
- It checks if the player can see it. If spotted, it flees at 2x speed to find cover. If unspotted, it sneaks up behind the player at 1.2x speed.
- If it needs to go up to reach the target, it switches to towering mode  (They can tower up now •_•)

### Personality  
Sometimes they will not explode. Instead, they will get behind you and stand still untill you look at them. As soon as you look at them they will instantly flee away. Sometimes hit you before fleeing.  

When standing behind you they might scare you with hissing sound until you look behind, as if they will blast right now. You'll get scared and run away and they will hide from there too.  
Sometimes they will hit you from behind to look at them and again, they will run away as soon as you look at them

### Door and Trapdoor Interaction
- They can interact with wooden doors and trapdoors and path through them

### Towering
- If the target is 2+ or more blocks above and there's no path to the player, it towers up using TNTs to get up there. If a ceiling blocks the way, it bridges across with TNT, moves to a clear spot, then keeps climbing.

### Core Changes
- A few internal tweaks: their detection range is extended to 64 blocks, and can fist damage to player. They spawn with 64 TNTs loaded in their inventory. (so easy TNTs :) only if you can kill them)

## Project Structure

```
src/main/java/com/main/smartcreeperai/
├── SmartCreeperMod.java                Initializer
├── SneakyCreeperGoal.java              Stealth AI
├── CreeperOpenDoorGoal.java            Door interaction
├── CreeperInteractTrapdoorGoal.java    Trapdoor interaction
├── CreeperTowerGoal.java               Towering up AI
├── CreeperInventoryProvider.java       Inventory
└── mixin/
    ├── CreeperEntityMixin.java         Goal Injections
    └── MobEntityMixin.java             Spawn with TNT
```
