package com.main.smartcreeperai;

import net.minecraft.block.BlockState;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.EnumSet;

public class CreeperInteractTrapdoorGoal extends Goal {
    private final CreeperEntity creeper;
    private BlockPos targetTrapdoorPos;
    private int cooldown = 0;
    private int tickCounter = 0;

    public CreeperInteractTrapdoorGoal(CreeperEntity creeper) {
        this.creeper = creeper;
        // Fix: Removed syntax error and only require MOVE control.
        // This allows LOOK control to remain shared with targeting/fleeing goals.
        this.setControls(EnumSet.of(Goal.Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        
        // Fix: Removed the restrictive isIdle() check that breaks during active targeting/fleeing states.
        BlockPos creeperPos = this.creeper.getBlockPos();
        BlockPos foundPos = null;
        
        // Scan a small area around the creeper (including head and feet)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos pos = creeperPos.add(dx, dy, dz);
                    BlockState state = this.creeper.getWorld().getBlockState(pos);
                    if (state.getBlock() instanceof TrapdoorBlock) {
                        // Check if it's wooden (skip iron!) and closed
                        if (state.isIn(BlockTags.WOODEN_TRAPDOORS) && !state.get(TrapdoorBlock.OPEN)) {
                            foundPos = pos;
                            break;
                        }
                    }
                }
                if (foundPos != null) break;
            }
            if (foundPos != null) break;
        }

        if (foundPos != null) {
            this.targetTrapdoorPos = foundPos;
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldContinue() {
        if (this.targetTrapdoorPos == null) return false;
        BlockState state = this.creeper.getWorld().getBlockState(this.targetTrapdoorPos);
        if (!(state.getBlock() instanceof TrapdoorBlock)) return false;
        if (state.get(TrapdoorBlock.OPEN)) return false; // Already opened
        return this.creeper.squaredDistanceTo(targetTrapdoorPos.getX() + 0.5D, targetTrapdoorPos.getY() + 0.5D, targetTrapdoorPos.getZ() + 0.5D) <= 9.0D && tickCounter < 20;
    }

    @Override
    public void start() {
        this.tickCounter = 0;
    }

    @Override
    public void stop() {
        this.targetTrapdoorPos = null;
        this.cooldown = 10; // short cooldown before looking for another
    }

    @Override
    public void tick() {
        if (this.targetTrapdoorPos == null) return;
        this.tickCounter++;

        // Face the trapdoor
        this.creeper.getLookControl().lookAt(
            this.targetTrapdoorPos.getX() + 0.5D,
            this.targetTrapdoorPos.getY() + 0.5D,
            this.targetTrapdoorPos.getZ() + 0.5D,
            30.0F, 30.0F
        );

        // Interact after looking at it for a few ticks (e.g. 5 ticks) to make it look active & realistic
        if (this.tickCounter >= 5) {
            World world = this.creeper.getWorld();
            BlockState state = world.getBlockState(this.targetTrapdoorPos);
            if (state.getBlock() instanceof TrapdoorBlock && state.isIn(BlockTags.WOODEN_TRAPDOORS) && !state.get(TrapdoorBlock.OPEN)) {
                // Open it!
                BlockState newState = state.with(TrapdoorBlock.OPEN, true);
                world.setBlockState(this.targetTrapdoorPos, newState, 2);
                
                // Play sound and emit game event, identical to player interaction
                world.playSound(null, this.targetTrapdoorPos, 
                    net.minecraft.sound.SoundEvents.BLOCK_WOODEN_TRAPDOOR_OPEN, 
                    net.minecraft.sound.SoundCategory.BLOCKS, 1.0F, 
                    world.getRandom().nextFloat() * 0.15F + 0.9F
                );
                world.emitGameEvent(null, net.minecraft.world.event.GameEvent.BLOCK_OPEN, this.targetTrapdoorPos);
                
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper at " + this.creeper.getBlockPos() + " successfully opened wooden trapdoor at " + this.targetTrapdoorPos);
            }
            this.targetTrapdoorPos = null; // complete the task
        }
    }
}
