package com.main.smartcreeperai;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import java.util.EnumSet;

public class CreeperOpenDoorGoal extends Goal {
    private final CreeperEntity creeper;
    private BlockPos targetDoorPos;
    private int cooldown = 0;
    private int tickCounter = 0;

    public CreeperOpenDoorGoal(CreeperEntity creeper) {
        this.creeper = creeper;
        // Only require MOVE control, allowing LOOK control to remain shared with targeting/fleeing goals
        this.setControls(EnumSet.of(Goal.Control.MOVE));
    }

    @Override
    public boolean canStart() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }

        // FIX: Removed the restrictive isIdle() check that breaks during active targeting/fleeing states.
        BlockPos creeperPos = this.creeper.getBlockPos();
        BlockPos foundPos = null;

        // Scan immediate area for a closed wooden door
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos pos = creeperPos.add(dx, dy, dz);
                    BlockState state = this.creeper.getWorld().getBlockState(pos);
                    if (state.getBlock() instanceof DoorBlock) {
                        if (state.isIn(BlockTags.WOODEN_DOORS) && !state.get(DoorBlock.OPEN)) {
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
            this.targetDoorPos = foundPos;
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldContinue() {
        if (this.targetDoorPos == null) return false;
        BlockState state = this.creeper.getWorld().getBlockState(this.targetDoorPos);
        if (!(state.getBlock() instanceof DoorBlock)) return false;
        if (state.get(DoorBlock.OPEN)) return false;
        return this.creeper.squaredDistanceTo(targetDoorPos.getX() + 0.5D, targetDoorPos.getY() + 0.5D, targetDoorPos.getZ() + 0.5D) <= 9.0D && tickCounter < 20;
    }

    @Override
    public void start() {
        this.tickCounter = 0;
    }

    @Override
    public void stop() {
        this.targetDoorPos = null;
        this.cooldown = 10;
    }

    @Override
    public void tick() {
        if (this.targetDoorPos == null) return;
        this.tickCounter++;

        this.creeper.getLookControl().lookAt(
            this.targetDoorPos.getX() + 0.5D,
            this.targetDoorPos.getY() + 0.5D,
            this.targetDoorPos.getZ() + 0.5D,
            30.0F, 30.0F
        );

        if (this.tickCounter >= 5) {
            World world = this.creeper.getWorld();
            BlockState state = world.getBlockState(this.targetDoorPos);
            if (state.getBlock() instanceof DoorBlock && state.isIn(BlockTags.WOODEN_DOORS) && !state.get(DoorBlock.OPEN)) {
                BlockState newState = state.with(DoorBlock.OPEN, true);
                world.setBlockState(this.targetDoorPos, newState, 2);

                world.playSound(null, this.targetDoorPos,
                    SoundEvents.BLOCK_WOODEN_DOOR_OPEN,
                    SoundCategory.BLOCKS, 1.0F,
                    world.getRandom().nextFloat() * 0.15F + 0.9F
                );
                world.emitGameEvent(null, GameEvent.BLOCK_OPEN, this.targetDoorPos);

                SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper at " + this.creeper.getBlockPos() + " successfully opened wooden door at " + this.targetDoorPos);
            }
            this.targetDoorPos = null;
        }
    }
}
