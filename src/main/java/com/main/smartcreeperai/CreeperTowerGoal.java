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
    private enum TowerPhase {
        SEEK_PATH,
        TOWER_UP,
        CEILING_ESCAPE
    }

    private final CreeperEntity creeper;
    private TowerPhase phase;
    private BlockPos ceilingEscapeTarget;
    private int pathSearchCooldown;

    public CreeperTowerGoal(CreeperEntity creeper) {
        this.creeper = creeper;
        this.phase = TowerPhase.SEEK_PATH;
        this.ceilingEscapeTarget = null;
        this.pathSearchCooldown = 0;
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

    private void logPhaseChange(TowerPhase newPhase, String reason) {
        if (this.phase != newPhase) {
            SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Tower phase -> " + newPhase + " (" + reason + ")");
            this.phase = newPhase;
        }
    }

    private void resetState() {
        this.phase = TowerPhase.SEEK_PATH;
        this.ceilingEscapeTarget = null;
        this.pathSearchCooldown = 0;
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

        return target.getY() - this.creeper.getY() >= 2.0D && hasTntInInventory();
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

        double dx = target.getX() - this.creeper.getX();
        double dz = target.getZ() - this.creeper.getZ();
        if (dx * dx + dz * dz > 64.0D * 64.0D) {
            return false;
        }

        return target.getY() - this.creeper.getY() >= 1.5D && hasTntInInventory();
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

        for (int step = 1; step <= Math.min(12, (int) len); step++) {
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

        for (int radius = 2; radius <= 18; radius += 2) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }

                    for (int dy = -4; dy <= 6; dy++) {
                        BlockPos candidate = start.add(dx, dy, dz);
                        if (!isValidEscapeSpot(candidate, target, true)) {
                            continue;
                        }

                        double distToTarget = candidate.getSquaredDistance(target.getPos());
                        double distFromStart = candidate.getSquaredDistance(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D);
                        double score = distToTarget + (distFromStart * 0.2D);
                        if (candidate.getY() > start.getY()) {
                            score -= 4.0D;
                        }
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
            for (int radius = 2; radius <= 20; radius += 2) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }

                        for (int dy = -4; dy <= 6; dy++) {
                            BlockPos candidate = start.add(dx, dy, dz);
                            if (!isValidEscapeSpot(candidate, target, false)) {
                                continue;
                            }

                            double distToTarget = candidate.getSquaredDistance(target.getPos());
                            double distFromStart = candidate.getSquaredDistance(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D);
                            double score = distToTarget + (distFromStart * 0.25D);
                            if (candidate.getY() > start.getY()) {
                                score -= 2.0D;
                            }
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

    private boolean followNavigationPath(PlayerEntity target) {
        var navigation = this.creeper.getNavigation();

        if (navigation.isFollowingPath()) {
            return true;
        }

        if (this.pathSearchCooldown > 0) {
            this.pathSearchCooldown--;
            return navigation.getCurrentPath() != null;
        }

        this.pathSearchCooldown = 5;
        net.minecraft.entity.ai.pathing.Path path = navigation.findPathTo(target, 0);
        if (path != null) {
            navigation.startMovingAlong(path, 1.10D);
            SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Following navigation path toward player.");
            return true;
        }

        return false;
    }

    private void enterTowerMode(String reason) {
        logPhaseChange(TowerPhase.TOWER_UP, reason);
        this.ceilingEscapeTarget = null;
        this.creeper.getNavigation().stop();
    }

    private void enterCeilingEscape(String reason) {
        logPhaseChange(TowerPhase.CEILING_ESCAPE, reason);
        this.ceilingEscapeTarget = null;
        this.creeper.getNavigation().stop();
    }

    private void resumeFromEscape(String reason) {
        logPhaseChange(TowerPhase.TOWER_UP, reason);
        this.ceilingEscapeTarget = null;
        this.pathSearchCooldown = 0;
    }

    private Vec3d getTowerAdvancePosition(PlayerEntity target) {
        Vec3d current = this.creeper.getPos();
        Vec3d delta = target.getPos().subtract(current);
        Vec3d horizontal = new Vec3d(delta.x, 0.0D, delta.z);
        double horizontalSq = horizontal.x * horizontal.x + horizontal.z * horizontal.z;

        if (horizontalSq < 0.01D) {
            return current.add(0.0D, 1.0D, 0.0D);
        }

        double stepDistance = Math.min(1.2D, Math.max(0.85D, Math.sqrt(horizontalSq)));
        Vec3d direction = horizontal.normalize();
        Vec3d step = current.add(direction.multiply(stepDistance));

        if (target.getY() > this.creeper.getY()) {
            step = step.add(0.0D, 0.45D, 0.0D);
        }

        return step;
    }

    private void ensureStepSupport(Vec3d step) {
        BlockPos supportPos = BlockPos.ofFloored(step.x, step.y, step.z).down();
        BlockState state = this.creeper.getWorld().getBlockState(supportPos);
        if (state.isAir() || state.isReplaceable()) {
            if (placeTntScaffolding(supportPos)) {
                SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper placed tower support at " + supportPos);
            }
        }
    }

    private void moveTo(double x, double y, double z, double speed) {
        this.creeper.getNavigation().startMovingTo(x, y, z, speed);
        this.creeper.getMoveControl().moveTo(x, y, z, speed);
    }

    private void moveTo(BlockPos pos, double speed) {
        moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, speed);
    }

    @Override
    public void start() {
        resetState();
        this.creeper.getNavigation().stop();
    }

    @Override
    public void stop() {
        resetState();
    }

    @Override
    public void tick() {
        LivingEntity target = this.creeper.getTarget();
        if (target == null || !(target instanceof PlayerEntity player)) {
            resetState();
            return;
        }

        this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);

        BlockPos currentPos = this.creeper.getBlockPos();
        boolean headBlocked = isHeadroomBlocked(currentPos);
        boolean pathBlocked = isPathHeadBlocked(currentPos, player);

        if (this.phase == TowerPhase.CEILING_ESCAPE) {
            if (this.ceilingEscapeTarget == null || !isValidEscapeSpot(this.ceilingEscapeTarget, player, true)) {
                this.ceilingEscapeTarget = findCeilingEscapeRoute(currentPos, player);
                if (this.ceilingEscapeTarget != null) {
                    SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Ceiling escape target chosen: " + this.ceilingEscapeTarget);
                }
            }

            if (this.ceilingEscapeTarget != null) {
                moveTo(this.ceilingEscapeTarget.getX() + 0.5D, this.ceilingEscapeTarget.getY(), this.ceilingEscapeTarget.getZ() + 0.5D, 1.05D);
                double distToEscape = this.creeper.squaredDistanceTo(
                    this.ceilingEscapeTarget.getX() + 0.5D,
                    this.ceilingEscapeTarget.getY(),
                    this.ceilingEscapeTarget.getZ() + 0.5D
                );
                if (distToEscape <= 1.5D && !isHeadroomBlocked(this.ceilingEscapeTarget)) {
                    resumeFromEscape("escape pocket reached");
                }
                return;
            }

            Vec3d away = new Vec3d(this.creeper.getX() - player.getX(), 0.0D, this.creeper.getZ() - player.getZ());
            if (away.x * away.x + away.z * away.z < 0.01D) {
                away = new Vec3d(1.0D, 0.0D, 0.0D);
            } else {
                away = away.normalize();
            }
            Vec3d fallback = this.creeper.getPos().add(away.multiply(3.5D));
            moveTo(fallback.x, this.creeper.getY(), fallback.z, 1.0D);
            return;
        }

        if (this.phase == TowerPhase.SEEK_PATH) {
            if (followNavigationPath(player)) {
                return;
            }

            enterTowerMode("no usable path found");
        }

        if (this.phase == TowerPhase.TOWER_UP) {
            if (followNavigationPath(player)) {
                logPhaseChange(TowerPhase.SEEK_PATH, "path to player restored");
                return;
            }

            if (headBlocked || pathBlocked) {
                enterCeilingEscape(headBlocked ? "headroom blocked" : "path blocked by ceiling");
                return;
            }

            Vec3d towerStep = getTowerAdvancePosition(player);
            ensureStepSupport(towerStep);

            if (this.creeper.isOnGround()) {
                if (!isHeadroomBlocked(currentPos)) {
                    this.creeper.getJumpControl().setActive();
                }
            } else {
                BlockPos scaffoldPos = currentPos.down();
                BlockState stateBeneath = this.creeper.getWorld().getBlockState(scaffoldPos);
                if (stateBeneath.isAir() || stateBeneath.isReplaceable()) {
                    if (placeTntScaffolding(scaffoldPos)) {
                        SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper scaffolded upward/bridged with unlit TNT at " + scaffoldPos);
                    }
                }
            }

            if (this.creeper.getNavigation().isIdle() || this.creeper.age % 4 == 0) {
                this.creeper.getNavigation().startMovingTo(towerStep.x, towerStep.y, towerStep.z, 0.95D);
            }
            this.creeper.getMoveControl().moveTo(towerStep.x, towerStep.y, towerStep.z, 0.95D);
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
