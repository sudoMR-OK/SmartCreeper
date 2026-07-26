package com.main.smartcreeperai;

import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import java.util.EnumSet;

public class CreeperTowerGoal extends Goal {
    private final CreeperEntity creeper;

    public CreeperTowerGoal(CreeperEntity creeper) {
        this.creeper = creeper;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    private boolean isPlayerLookingAtMe(PlayerEntity player) {
        net.minecraft.util.math.Vec3d lookVec = player.getRotationVec(1.0F).normalize();
        net.minecraft.util.math.Vec3d toCreeperVec = new net.minecraft.util.math.Vec3d(
            this.creeper.getX() - player.getX(),
            this.creeper.getEyeY() - player.getEyeY(),
            this.creeper.getZ() - player.getZ()
        ).normalize();
        return lookVec.dotProduct(toCreeperVec) > 0.0D && player.canSee(this.creeper);
    }

    private boolean isMidClimbOrScaffolding() {
        BlockPos pos = this.creeper.getBlockPos();
        return this.creeper.getWorld().getBlockState(pos.down()).isOf(Blocks.TNT)
            || this.creeper.getWorld().getBlockState(pos.down(2)).isOf(Blocks.TNT)
            || isHeadroomBlocked(pos);
    }

    @Override
    public boolean canStart() {
        LivingEntity target = this.creeper.getTarget();
        if (target == null || !target.isAlive() || !(target instanceof PlayerEntity player)) {
            return false;
        }

        // Stealthiness No. 1 priority: if spotted by player, yield so SneakyCreeperGoal flees to cover
        if (isPlayerLookingAtMe(player)) {
            return false;
        }

        // Target player must be within horizontal follow range (64 blocks)
        double dx = target.getX() - this.creeper.getX();
        double dz = target.getZ() - this.creeper.getZ();
        if (dx * dx + dz * dz > 64.0D * 64.0D) {
            return false;
        }

        // Target player's Y is >= 2 blocks above the Creeper
        if (target.getY() - this.creeper.getY() < 2.0D) {
            return false;
        }

        if (!isMidClimbOrScaffolding()) {
            // Check navigation result: if a standard path exists and reaches target, don't tower
            net.minecraft.entity.ai.pathing.Path path = this.creeper.getNavigation().findPathTo(target, 0);
            if (path != null && path.reachesTarget()) {
                return false;
            }

            // Only bridge/tower once creeper has reached closure at closest path end
            if (path != null) {
                net.minecraft.entity.ai.pathing.PathNode endNode = path.getEnd();
                if (endNode != null) {
                    double distSqToEnd = this.creeper.squaredDistanceTo(endNode.x + 0.5D, endNode.y, endNode.z + 0.5D);
                    if (distSqToEnd > 2.25D) {
                        return false;
                    }
                }
            }
        }

        return hasTntInInventory();
    }

    @Override
    public boolean shouldContinue() {
        LivingEntity target = this.creeper.getTarget();
        if (target == null || !target.isAlive() || !(target instanceof PlayerEntity player)) {
            return false;
        }

        // Stealthiness No. 1 priority: yield immediately if player looks at creeper
        if (isPlayerLookingAtMe(player)) {
            return false;
        }

        // Stop once height gap is closed (< 1.5 blocks vertical difference)
        if (target.getY() - this.creeper.getY() < 1.5D) {
            return false;
        }

        double dx = target.getX() - this.creeper.getX();
        double dz = target.getZ() - this.creeper.getZ();
        if (dx * dx + dz * dz > 64.0D * 64.0D) {
            return false;
        }

        if (!isMidClimbOrScaffolding()) {
            // Check if a direct path or new closest path segment is available
            net.minecraft.entity.ai.pathing.Path path = this.creeper.getNavigation().findPathTo(target, 0);
            if (path != null && path.reachesTarget()) {
                return false;
            }

            if (path != null) {
                net.minecraft.entity.ai.pathing.PathNode endNode = path.getEnd();
                if (endNode != null) {
                    double distSqToEnd = this.creeper.squaredDistanceTo(endNode.x + 0.5D, endNode.y, endNode.z + 0.5D);
                    if (distSqToEnd > 2.25D) {
                        return false;
                    }
                }
            }
        }

        return hasTntInInventory();
    }

    private boolean isHeadroomBlocked(BlockPos pos) {
        // Check 2 and 3 blocks above feet position for any ceiling/collision obstruction
        return !this.creeper.getWorld().getBlockState(pos.up(2)).getCollisionShape(this.creeper.getWorld(), pos.up(2)).isEmpty()
            || !this.creeper.getWorld().getBlockState(pos.up(3)).getCollisionShape(this.creeper.getWorld(), pos.up(3)).isEmpty();
    }

    private boolean isPathHeadBlocked(BlockPos start, LivingEntity target) {
        double dirX = target.getX() - (start.getX() + 0.5D);
        double dirZ = target.getZ() - (start.getZ() + 0.5D);
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1.0D) return false;

        dirX /= len;
        dirZ /= len;

        for (int step = 1; step <= Math.min(4, (int)len); step++) {
            BlockPos stepPos = BlockPos.ofFloored(start.getX() + 0.5D + dirX * step, start.getY(), start.getZ() + 0.5D + dirZ * step);
            if (isHeadroomBlocked(stepPos)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findHeadroomFreeRoute(BlockPos start, LivingEntity target) {
        BlockPos bestPos = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos cand = start.add(dx, 0, dz);
                if (!isHeadroomBlocked(cand)) {
                    double distToTarget = cand.getSquaredDistance(target.getPos());
                    if (isPathHeadBlocked(cand, target)) {
                        distToTarget += 500.0D;
                    }
                    if (distToTarget < bestScore) {
                        bestScore = distToTarget;
                        bestPos = cand;
                    }
                }
            }
        }
        return bestPos;
    }

    @Override
    public void start() {
        this.creeper.getNavigation().stop();
    }

    @Override
    public void stop() {
        // Fall back to normal pathing/explosion behavior
    }

    @Override
    public void tick() {
        LivingEntity target = this.creeper.getTarget();
        if (target == null) return;

        this.creeper.getLookControl().lookAt(target, 30.0F, 30.0F);

        BlockPos currentPos = this.creeper.getBlockPos();
        boolean headBlocked = isHeadroomBlocked(currentPos);

        double moveDestX = target.getX();
        double moveDestZ = target.getZ();

        // Choose head blocking free path before bridging or avoid overhead obstacle before towering
        if (headBlocked || isPathHeadBlocked(currentPos, target)) {
            BlockPos freeRoute = findHeadroomFreeRoute(currentPos, target);
            if (freeRoute != null) {
                moveDestX = freeRoute.getX() + 0.5D;
                moveDestZ = freeRoute.getZ() + 0.5D;
            }
        }

        // Move horizontally toward destination if not directly underneath
        double dx = moveDestX - this.creeper.getX();
        double dz = moveDestZ - this.creeper.getZ();
        boolean movingHorizontally = (dx * dx + dz * dz > 1.0D);
        if (movingHorizontally) {
            tryPlaceHorizontalBridge(currentPos, moveDestX, moveDestZ);
            if (this.creeper.getNavigation().isIdle() || this.creeper.age % 5 == 0) {
                this.creeper.getNavigation().startMovingTo(moveDestX, this.creeper.getY(), moveDestZ, 1.2D);
            }
            this.creeper.getMoveControl().moveTo(moveDestX, this.creeper.getY(), moveDestZ, 1.2D);
        } else {
            this.creeper.getNavigation().stop();
        }

        // Trigger upward jump when on ground, ONLY if headroom is not obstructed above head!
        if (this.creeper.isOnGround()) {
            if (!headBlocked) {
                this.creeper.getJumpControl().setActive();
            }
        } else {
            // Once airborne with no block underfoot, place regular unlit Blocks.TNT block state
            BlockPos scaffoldPos = this.creeper.getBlockPos().down();
            BlockState stateBeneath = this.creeper.getWorld().getBlockState(scaffoldPos);
            if (stateBeneath.isAir() || stateBeneath.isReplaceable()) {
                if (placeTntScaffolding(scaffoldPos)) {
                    SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper scaffolded upward/bridged with unlit TNT at " + scaffoldPos);
                }
            }
        }
    }

    private void tryPlaceHorizontalBridge(BlockPos currentPos, double destX, double destZ) {
        BlockPos targetPos = BlockPos.ofFloored(destX, currentPos.getY(), destZ);
        if (currentPos.equals(targetPos)) return;

        int stepX = Integer.compare(targetPos.getX(), currentPos.getX());
        int stepZ = Integer.compare(targetPos.getZ(), currentPos.getZ());

        if (stepX != 0) {
            tryPlaceBridgeBeneath(currentPos.add(stepX, 0, 0));
        }
        if (stepZ != 0) {
            tryPlaceBridgeBeneath(currentPos.add(0, 0, stepZ));
        }
        if (stepX != 0 && stepZ != 0) {
            tryPlaceBridgeBeneath(currentPos.add(stepX, 0, stepZ));
        }
    }

    private void tryPlaceBridgeBeneath(BlockPos pos) {
        BlockPos beneath = pos.down();
        BlockState state = this.creeper.getWorld().getBlockState(beneath);
        if (state.isAir() || state.isReplaceable()) {
            if (placeTntScaffolding(beneath)) {
                SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper placed horizontal TNT bridge at " + beneath);
            }
        }
    }

    private boolean hasTntInInventory() {
        if (!(this.creeper instanceof CreeperInventoryProvider provider)) return false;
        SimpleInventory inv = provider.getTntInventory();
        if (inv == null || inv.isEmpty()) return false;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.TNT)) {
                return true;
            }
        }
        return false;
    }

    private boolean placeTntScaffolding(BlockPos pos) {
        if (!(this.creeper instanceof CreeperInventoryProvider provider)) return false;
        SimpleInventory inv = provider.getTntInventory();
        if (inv == null) return false;

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.TNT)) {
                stack.decrement(1);
                this.creeper.getWorld().setBlockState(pos, Blocks.TNT.getDefaultState());
                this.creeper.getWorld().playSound(null, pos, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1.0F, 1.0F);
                return true;
            }
        }
        return false;
    }
}
