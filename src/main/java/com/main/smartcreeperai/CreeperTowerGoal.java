package com.main.smartcreeperai;

import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
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
        BRIDGE_GAP,
        TOWER_UP,
        CEILING_ESCAPE
    }

    private final CreeperEntity creeper;
    private TowerPhase phase;
    private BlockPos ceilingEscapeTarget;
    private int pathSearchCooldown;
    private BlockPos lastObservedBlockPos;
    private int pathStallTicks;
    private BlockPos lastBridgeSupportPos;
    private int bridgeProgressTicks;

    public CreeperTowerGoal(CreeperEntity creeper) {
        this.creeper = creeper;
        this.phase = TowerPhase.SEEK_PATH;
        this.ceilingEscapeTarget = null;
        this.pathSearchCooldown = 0;
        this.lastObservedBlockPos = null;
        this.pathStallTicks = 0;
        this.lastBridgeSupportPos = null;
        this.bridgeProgressTicks = 0;
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
        this.lastObservedBlockPos = null;
        this.pathStallTicks = 0;
        this.lastBridgeSupportPos = null;
        this.bridgeProgressTicks = 0;
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

        return target.getY() - this.creeper.getY() >= -1.0D && hasTntInInventory();
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

        return target.getY() - this.creeper.getY() >= -1.0D && hasTntInInventory();
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

    private boolean isSameLevelBridgeScenario(PlayerEntity player) {
        double verticalDiff = Math.abs(player.getY() - this.creeper.getY());
        double dx = player.getX() - this.creeper.getX();
        double dz = player.getZ() - this.creeper.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        return verticalDiff <= 1.25D && horizontalDistance >= 2.0D;
    }

    private boolean isAtPathEnd(Path path) {
        if (path == null) {
            return false;
        }
        net.minecraft.entity.ai.pathing.PathNode endNode = path.getEnd();
        if (endNode == null) {
            return false;
        }
        double distSqToEnd = this.creeper.squaredDistanceTo(endNode.x + 0.5D, endNode.y, endNode.z + 0.5D);
        return distSqToEnd <= 2.25D;
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

                    for (int dy = 1; dy <= 8; dy++) {
                        BlockPos candidate = start.add(dx, dy, dz);
                        if (!isValidEscapeSpot(candidate, target, true)) {
                            continue;
                        }

                        double distToTarget = candidate.getSquaredDistance(target.getPos());
                        double distFromStart = candidate.getSquaredDistance(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D);
                        double score = distToTarget + (distFromStart * 0.2D);
                        score -= dy * 3.0D;
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

                        for (int dy = 1; dy <= 8; dy++) {
                            BlockPos candidate = start.add(dx, dy, dz);
                            if (!isValidEscapeSpot(candidate, target, false)) {
                                continue;
                            }

                            double distToTarget = candidate.getSquaredDistance(target.getPos());
                            double distFromStart = candidate.getSquaredDistance(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D);
                            double score = distToTarget + (distFromStart * 0.25D);
                            score -= dy * 2.0D;
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

    private boolean acquirePath(PlayerEntity target) {
        EntityNavigation navigation = this.creeper.getNavigation();
        if (this.pathSearchCooldown > 0) {
            this.pathSearchCooldown--;
            Path current = navigation.getCurrentPath();
            return current != null && !current.isFinished();
        }

        this.pathSearchCooldown = 4;
        Path path = navigation.findPathTo(target, 0);
        if (path != null && !path.isFinished()) {
            navigation.startMovingAlong(path, 1.10D);
            SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Following navigation path toward player.");
            return true;
        }

        return false;
    }

    private boolean trackPathStall(boolean pathActive) {
        BlockPos currentPos = this.creeper.getBlockPos();
        if (!pathActive) {
            this.lastObservedBlockPos = null;
            this.pathStallTicks = 0;
            return false;
        }

        if (this.lastObservedBlockPos != null && this.lastObservedBlockPos.equals(currentPos)) {
            this.pathStallTicks++;
        } else {
            this.lastObservedBlockPos = currentPos;
            this.pathStallTicks = 0;
        }

        return this.pathStallTicks >= 4;
    }

    private void enterBridgeGap(String reason) {
        logPhaseChange(TowerPhase.BRIDGE_GAP, reason);
        this.ceilingEscapeTarget = null;
        this.pathStallTicks = 0;
        this.lastObservedBlockPos = null;
        this.bridgeProgressTicks = 0;
        this.lastBridgeSupportPos = null;
        this.creeper.getNavigation().stop();
    }

    private void enterTowerMode(String reason) {
        logPhaseChange(TowerPhase.TOWER_UP, reason);
        this.ceilingEscapeTarget = null;
        this.pathStallTicks = 0;
        this.lastObservedBlockPos = null;
        this.bridgeProgressTicks = 0;
        this.lastBridgeSupportPos = null;
        this.creeper.getNavigation().stop();
    }

    private void enterCeilingEscape(String reason) {
        logPhaseChange(TowerPhase.CEILING_ESCAPE, reason);
        this.ceilingEscapeTarget = null;
        this.pathStallTicks = 0;
        this.lastObservedBlockPos = null;
        this.bridgeProgressTicks = 0;
        this.lastBridgeSupportPos = null;
        this.creeper.getNavigation().stop();
    }

    private void resumeFromEscape(String reason) {
        logPhaseChange(TowerPhase.TOWER_UP, reason);
        this.ceilingEscapeTarget = null;
        this.pathSearchCooldown = 0;
        this.pathStallTicks = 0;
        this.lastObservedBlockPos = null;
        this.bridgeProgressTicks = 0;
        this.lastBridgeSupportPos = null;
    }

    private Vec3d getTowerAdvancePosition(PlayerEntity target) {
        Vec3d current = this.creeper.getPos();
        Vec3d delta = target.getPos().subtract(current);
        Vec3d horizontal = new Vec3d(delta.x, 0.0D, delta.z);
        double horizontalSq = horizontal.x * horizontal.x + horizontal.z * horizontal.z;

        if (horizontalSq < 0.01D) {
            return current.add(0.0D, 1.0D, 0.0D);
        }

        double horizontalDistance = Math.sqrt(horizontalSq);
        double stepDistance = Math.min(1.15D, Math.max(0.80D, horizontalDistance));
        Vec3d step = current.add(horizontal.normalize().multiply(stepDistance));

        if (target.getY() > this.creeper.getY()) {
            step = step.add(0.0D, 0.35D, 0.0D);
        }

        return step;
    }

    private Vec3d getBridgeAdvancePosition(PlayerEntity target) {
        Vec3d current = this.creeper.getPos();
        Vec3d delta = target.getPos().subtract(current);
        Vec3d horizontal = new Vec3d(delta.x, 0.0D, delta.z);
        double horizontalSq = horizontal.x * horizontal.x + horizontal.z * horizontal.z;

        if (horizontalSq < 0.01D) {
            return current;
        }

        double horizontalDistance = Math.sqrt(horizontalSq);
        double stepDistance = Math.min(2.35D, Math.max(1.7D, horizontalDistance * 0.7D));
        return current.add(horizontal.normalize().multiply(stepDistance));
    }

    private void placeSupportIfNeeded(BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockState state = this.creeper.getWorld().getBlockState(pos);
        if (state.isAir() || state.isReplaceable()) {
            if (placeTntScaffolding(pos)) {
                SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper placed tower support at " + pos);
            }
        }
    }

    private void placeBridgeSupportsToward(PlayerEntity player, BlockPos currentPos) {
        Vec3d start = new Vec3d(currentPos.getX() + 0.5D, currentPos.getY(), currentPos.getZ() + 0.5D);
        Vec3d direction = new Vec3d(player.getX() - start.x, 0.0D, player.getZ() - start.z);
        if (direction.x * direction.x + direction.z * direction.z < 0.01D) {
            return;
        }
        direction = direction.normalize();

        for (int i = 1; i <= 6; i++) {
            Vec3d sample = new Vec3d(start.x + direction.x * i, start.y, start.z + direction.z * i);
            BlockPos support = BlockPos.ofFloored(sample.x, sample.y, sample.z).down();
            placeSupportIfNeeded(support);
        }
    }

    private void ensureStepSupport(BlockPos currentPos, Vec3d step) {
        BlockPos stepSupport = BlockPos.ofFloored(step.x, step.y, step.z).down();
        placeSupportIfNeeded(stepSupport);

        double midX = (currentPos.getX() + 0.5D + step.x) / 2.0D;
        double midZ = (currentPos.getZ() + 0.5D + step.z) / 2.0D;
        BlockPos midSupport = BlockPos.ofFloored(midX, currentPos.getY(), midZ).down();
        if (!midSupport.equals(stepSupport)) {
            placeSupportIfNeeded(midSupport);
        }
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
        if (!(target instanceof PlayerEntity player) || !target.isAlive()) {
            resetState();
            return;
        }

        this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);

        BlockPos currentPos = this.creeper.getBlockPos();
        boolean headBlocked = isHeadroomBlocked(currentPos);
        boolean pathBlocked = isPathHeadBlocked(currentPos, player);
        boolean sameLevelBridgeScenario = isSameLevelBridgeScenario(player);
        double horizontalDistanceToPlayer = Math.sqrt(this.creeper.squaredDistanceTo(player.getX(), this.creeper.getY(), player.getZ()));

        if (this.phase == TowerPhase.CEILING_ESCAPE) {
            if (this.ceilingEscapeTarget == null || !isValidEscapeSpot(this.ceilingEscapeTarget, player, true)) {
                this.ceilingEscapeTarget = findCeilingEscapeRoute(currentPos, player);
                if (this.ceilingEscapeTarget != null) {
                    SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Ceiling escape target chosen: " + this.ceilingEscapeTarget);
                }
            }

            if (this.ceilingEscapeTarget != null) {
                this.creeper.getNavigation().startMovingTo(
                    this.ceilingEscapeTarget.getX() + 0.5D,
                    this.ceilingEscapeTarget.getY(),
                    this.ceilingEscapeTarget.getZ() + 0.5D,
                    1.05D
                );
                this.creeper.getMoveControl().moveTo(
                    this.ceilingEscapeTarget.getX() + 0.5D,
                    this.ceilingEscapeTarget.getY(),
                    this.ceilingEscapeTarget.getZ() + 0.5D,
                    1.05D
                );

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

            BlockPos upwardFallback = currentPos.up(2);
            if (!isHeadroomBlocked(currentPos) && isWalkableAt(upwardFallback)) {
                this.creeper.getNavigation().startMovingTo(upwardFallback.getX() + 0.5D, upwardFallback.getY(), upwardFallback.getZ() + 0.5D, 1.0D);
                this.creeper.getMoveControl().moveTo(upwardFallback.getX() + 0.5D, upwardFallback.getY(), upwardFallback.getZ() + 0.5D, 1.0D);
                return;
            }

            Vec3d away = new Vec3d(this.creeper.getX() - player.getX(), 0.0D, this.creeper.getZ() - player.getZ());
            if (away.x * away.x + away.z * away.z < 0.01D) {
                away = new Vec3d(1.0D, 0.0D, 0.0D);
            } else {
                away = away.normalize();
            }
            Vec3d fallback = this.creeper.getPos().add(away.multiply(3.5D));
            this.creeper.getNavigation().startMovingTo(fallback.x, this.creeper.getY(), fallback.z, 1.0D);
            this.creeper.getMoveControl().moveTo(fallback.x, this.creeper.getY(), fallback.z, 1.0D);
            return;
        }

        if (this.phase == TowerPhase.SEEK_PATH) {
            Path currentPath = this.creeper.getNavigation().getCurrentPath();
            boolean hasActivePath = currentPath != null && !currentPath.isFinished();

            if (hasActivePath) {
                if (sameLevelBridgeScenario && (isAtPathEnd(currentPath) || trackPathStall(true))) {
                    enterBridgeGap("same-level gap at edge");
                    return;
                }

                if (trackPathStall(true)) {
                    enterTowerMode("path stalled at edge");
                    return;
                }

                return;
            }

            if (acquirePath(player)) {
                return;
            }

            if (sameLevelBridgeScenario) {
                enterBridgeGap("same-level gap without usable path");
                return;
            }

            enterTowerMode("path ended or no usable path found");
        }

        if (this.phase == TowerPhase.BRIDGE_GAP) {
            if (headBlocked) {
                enterCeilingEscape("headroom blocked during bridge");
                return;
            }

            if (horizontalDistanceToPlayer <= 1.75D) {
                if (player.getY() - this.creeper.getY() > 1.25D) {
                    enterTowerMode("bridge gap crossed, switching to tower");
                } else {
                    enterTowerMode("bridge gap crossed");
                }
                return;
            }

            placeBridgeSupportsToward(player, currentPos);

            Vec3d bridgeStep = getBridgeAdvancePosition(player);
            BlockPos bridgeStepPos = BlockPos.ofFloored(bridgeStep.x, bridgeStep.y, bridgeStep.z).down();
            if (bridgeStepPos.equals(this.lastBridgeSupportPos)) {
                this.bridgeProgressTicks++;
            } else {
                this.lastBridgeSupportPos = bridgeStepPos;
                this.bridgeProgressTicks = 0;
            }

            if (this.bridgeProgressTicks >= 6) {
                this.bridgeProgressTicks = 0;
                placeSupportIfNeeded(bridgeStepPos);
            }

            placeSupportIfNeeded(bridgeStepPos);
            this.creeper.getNavigation().startMovingTo(bridgeStep.x, this.creeper.getY(), bridgeStep.z, 1.05D);
            this.creeper.getMoveControl().moveTo(bridgeStep.x, this.creeper.getY(), bridgeStep.z, 1.05D);
            return;
        }

        if (this.phase == TowerPhase.TOWER_UP) {
            if (headBlocked || (pathBlocked && !sameLevelBridgeScenario)) {
                enterCeilingEscape(headBlocked ? "headroom blocked" : "path blocked by ceiling");
                return;
            }

            if (sameLevelBridgeScenario && horizontalDistanceToPlayer >= 2.0D) {
                enterBridgeGap("same-level gap while towering");
                return;
            }

            Vec3d towerStep = getTowerAdvancePosition(player);
            ensureStepSupport(currentPos, towerStep);

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

            this.creeper.getNavigation().startMovingTo(towerStep.x, towerStep.y, towerStep.z, 0.90D);
            this.creeper.getMoveControl().moveTo(towerStep.x, towerStep.y, towerStep.z, 0.90D);
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
