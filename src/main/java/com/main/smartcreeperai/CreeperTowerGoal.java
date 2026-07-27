package com.main.smartcreeperai;

import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.EnumSet;

public class CreeperTowerGoal extends Goal {
    private final CreeperEntity creeper;
    private BlockPos ceilingEscapeTarget;
    private boolean ceilingEscapeMode;

    public CreeperTowerGoal(CreeperEntity creeper) {
        this.creeper = creeper;
        this.ceilingEscapeTarget = null;
        this.ceilingEscapeMode = false;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    private boolean isPlayerLookingAtMe(PlayerEntity player) {
        Vec3d lookVec = player.getRotationVec(1.0F).normalize();
        Vec3d toCreeperVec = new Vec3d(
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

        if (isPlayerLookingAtMe(player)) {
            return false;
        }

        double dx = target.getX() - this.creeper.getX();
        double dz = target.getZ() - this.creeper.getZ();
        if (dx * dx + dz * dz > 64.0D * 64.0D) {
            return false;
        }

        if (target.getY() - this.creeper.getY() < 2.0D) {
            return false;
        }

        if (!isMidClimbOrScaffolding()) {
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

    @Override
    public boolean shouldContinue() {
        LivingEntity target = this.creeper.getTarget();
        if (target == null || !target.isAlive() || !(target instanceof PlayerEntity player)) {
            return false;
        }

        if (isPlayerLookingAtMe(player)) {
            return false;
        }

        if (target.getY() - this.creeper.getY() < 1.5D) {
            return false;
        }

        double dx = target.getX() - this.creeper.getX();
        double dz = target.getZ() - this.creeper.getZ();
        if (dx * dx + dz * dz > 64.0D * 64.0D) {
            return false;
        }

        if (!isMidClimbOrScaffolding()) {
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
        World world = this.creeper.getWorld();
        return !world.getBlockState(pos.up(2)).getCollisionShape(world, pos.up(2)).isEmpty()
            || !world.getBlockState(pos.up(3)).getCollisionShape(world, pos.up(3)).isEmpty();
    }

    private boolean isPathHeadBlocked(BlockPos start, LivingEntity target) {
        double dirX = target.getX() - (start.getX() + 0.5D);
        double dirZ = target.getZ() - (start.getZ() + 0.5D);
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1.0D) return false;

        dirX /= len;
        dirZ /= len;

        for (int step = 1; step <= Math.min(8, (int) len); step++) {
            BlockPos stepPos = BlockPos.ofFloored(start.getX() + 0.5D + dirX * step, start.getY(), start.getZ() + 0.5D + dirZ * step);
            if (isHeadroomBlocked(stepPos)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWalkableAt(BlockPos pos) {
        World world = this.creeper.getWorld();
        return world.getBlockState(pos).isAir()
            && world.getBlockState(pos.up()).isAir()
            && !world.getBlockState(pos.down()).getCollisionShape(world, pos.down()).isEmpty();
    }

    private boolean isValidEscapeSpot(BlockPos pos, LivingEntity target, boolean requirePathClear) {
        if (pos == null) {
            return false;
        }
        if (!isWalkableAt(pos)) {
            return false;
        }
        if (isHeadroomBlocked(pos)) {
            return false;
        }
        return !requirePathClear || !isPathHeadBlocked(pos, target);
    }

    private BlockPos findCeilingEscapeRoute(BlockPos start, LivingEntity target) {
        BlockPos bestPos = null;
        double bestScore = Double.MAX_VALUE;

        for (int radius = 2; radius <= 12; radius += 2) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }

                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos candidate = start.add(dx, dy, dz);
                        if (!isValidEscapeSpot(candidate, target, true)) {
                            continue;
                        }

                        double distToTarget = candidate.getSquaredDistance(target.getPos());
                        double distFromStart = candidate.getSquaredDistance(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D);
                        double score = distToTarget + (distFromStart * 0.25D);
                        if (score < bestScore) {
                            bestScore = score;
                            bestPos = candidate;
                        }
                    }
                }
            }

            if (bestPos != null) {
                break;
            }
        }

        if (bestPos == null) {
            for (int radius = 2; radius <= 14; radius += 2) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }

                        for (int dy = -2; dy <= 2; dy++) {
                            BlockPos candidate = start.add(dx, dy, dz);
                            if (!isValidEscapeSpot(candidate, target, false)) {
                                continue;
                            }

                            double distToTarget = candidate.getSquaredDistance(target.getPos());
                            double distFromStart = candidate.getSquaredDistance(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D);
                            double score = distToTarget + (distFromStart * 0.35D);
                            if (score < bestScore) {
                                bestScore = score;
                                bestPos = candidate;
                            }
                        }
                    }
                }

                if (bestPos != null) {
                    break;
                }
            }
        }

        return bestPos;
    }

    private void moveTo(double x, double y, double z, double speed) {
        this.creeper.getNavigation().startMovingTo(x, y, z, speed);
        this.creeper.getMoveControl().moveTo(x, y, z, speed);
    }

    private void moveTo(BlockPos pos, double speed) {
        moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, speed);
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

    @Override
    public void start() {
        this.ceilingEscapeMode = false;
        this.ceilingEscapeTarget = null;
        this.creeper.getNavigation().stop();
    }

    @Override
    public void stop() {
        this.ceilingEscapeMode = false;
        this.ceilingEscapeTarget = null;
    }

    @Override
    public void tick() {
        LivingEntity target = this.creeper.getTarget();
        if (target == null) {
            this.ceilingEscapeMode = false;
            this.ceilingEscapeTarget = null;
            return;
        }

        this.creeper.getLookControl().lookAt(target, 30.0F, 30.0F);

        BlockPos currentPos = this.creeper.getBlockPos();
        boolean headBlocked = isHeadroomBlocked(currentPos);
        boolean pathBlocked = isPathHeadBlocked(currentPos, target);

        if (headBlocked || pathBlocked || this.ceilingEscapeMode) {
            if (!this.ceilingEscapeMode) {
                this.ceilingEscapeMode = true;
                this.ceilingEscapeTarget = null;
                this.creeper.getNavigation().stop();
            }

            if (this.ceilingEscapeTarget == null || !isValidEscapeSpot(this.ceilingEscapeTarget, target, true)) {
                this.ceilingEscapeTarget = findCeilingEscapeRoute(currentPos, target);
            }

            if (this.ceilingEscapeTarget != null) {
                moveTo(this.ceilingEscapeTarget, 1.35D);
                double distToEscape = this.creeper.squaredDistanceTo(
                    this.ceilingEscapeTarget.getX() + 0.5D,
                    this.ceilingEscapeTarget.getY(),
                    this.ceilingEscapeTarget.getZ() + 0.5D
                );
                if (distToEscape <= 1.5D && !isHeadroomBlocked(this.ceilingEscapeTarget)) {
                    this.ceilingEscapeMode = false;
                    this.ceilingEscapeTarget = null;
                }
                return;
            }

            Vec3d awayFromTarget = this.creeper.getPos().subtract(target.getPos()).normalize();
            Vec3d fallback = this.creeper.getPos().add(awayFromTarget.multiply(4.0D));
            moveTo(fallback.x, this.creeper.getY(), fallback.z, 1.35D);
            return;
        }

        double moveDestX = target.getX();
        double moveDestZ = target.getZ();

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

        if (this.creeper.isOnGround()) {
            if (!headBlocked) {
                this.creeper.getJumpControl().setActive();
            }
        } else {
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
