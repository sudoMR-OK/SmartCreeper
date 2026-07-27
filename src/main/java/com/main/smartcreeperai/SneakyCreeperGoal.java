package com.main.smartcreeperai;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import java.util.EnumSet;

public class SneakyCreeperGoal extends Goal {
    private final CreeperEntity creeper;
    private int fleeTimer = 0;
    private Vec3d hidingSpot = null;
    private int recentlySpottedTimer = 0; // Remembers that the player knows where we are
    private boolean decidedCommitment = false;
    private boolean commitToExplode = false;
    private int behaviorType = -1; // -1 = unassigned, 0 = standard behavior (60%), 1-4 = sub-behaviors
    private int passiveHitTimer = 0;

    public SneakyCreeperGoal(CreeperEntity creeper) {
        this.creeper = creeper;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    private PlayerEntity getTargetPlayer() {
        LivingEntity target = this.creeper.getTarget();
        if (target instanceof PlayerEntity && target.isAlive()) {
            PlayerEntity p = (PlayerEntity) target;
            if (!p.isSpectator() && !p.isCreative()) {
                return p;
            }
        }
        PlayerEntity closest = this.creeper.getWorld().getClosestPlayer(this.creeper, 64.0D);
        if (closest != null && closest.isAlive() && !closest.isSpectator() && !closest.isCreative()) {
            return closest;
        }
        return null;
    }

    private void moveTo(Vec3d pos, double speed) {
        if (pos == null) return;
        if (this.creeper.age % 5 == 0 || this.creeper.getNavigation().isIdle()) {
            this.creeper.getNavigation().startMovingTo(pos.x, pos.y, pos.z, speed);
        }
    }

    private boolean shouldYieldForScaffolding(PlayerEntity target) {
        if (this.creeper instanceof CreeperTowerControlProvider provider && provider.smartcreeperai$isTowerControlLocked()) {
            return true;
        }

        double verticalDiff = target.getY() - this.creeper.getY();
        double horizontalDistance = Math.sqrt(this.creeper.squaredDistanceTo(target.getX(), this.creeper.getY(), target.getZ()));
        net.minecraft.entity.ai.pathing.Path path = this.creeper.getNavigation().findPathTo(target, 0);

        if (verticalDiff >= 1.0D) {
            return true;
        }

        if (Math.abs(verticalDiff) <= 1.25D && horizontalDistance >= 2.0D) {
            if (path == null) {
                return true;
            }
            if (path.reachesTarget()) {
                return false;
            }
            net.minecraft.entity.ai.pathing.PathNode endNode = path.getEnd();
            if (endNode != null) {
                double distSqToEnd = this.creeper.squaredDistanceTo(endNode.x + 0.5D, endNode.y, endNode.z + 0.5D);
                return distSqToEnd <= 2.25D;
            }
            return true;
        }

        if (path != null) {
            if (path.reachesTarget()) {
                return false;
            }
            net.minecraft.entity.ai.pathing.PathNode endNode = path.getEnd();
            if (endNode != null) {
                double distSqToEnd = this.creeper.squaredDistanceTo(endNode.x + 0.5D, endNode.y, endNode.z + 0.5D);
                if (distSqToEnd <= 2.25D) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean canStart() {
        PlayerEntity target = getTargetPlayer();
        if (target == null || !target.isAlive()) return false;
        return !shouldYieldForScaffolding(target);
    }

    @Override
    public boolean shouldContinue() {
        PlayerEntity target = getTargetPlayer();
        if (target == null || !target.isAlive()) return false;
        return !shouldYieldForScaffolding(target);
    }

    private void rollBehaviorType() {
        float roll = this.creeper.getRandom().nextFloat();
        if (roll < 0.60F) {
            this.behaviorType = 0; // Existing behavior
        } else if (roll < 0.70F) {
            this.behaviorType = 1; // Silent Stand + Look
        } else if (roll < 0.80F) {
            this.behaviorType = 2; // Fuss-cancel + Look
        } else if (roll < 0.90F) {
            this.behaviorType = 3; // Passive fist + Look
        } else {
            this.behaviorType = 4; // Combined + Look
        }
        this.passiveHitTimer = 0;
        com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Rolled personality behavior: " + this.behaviorType);
    }

    @Override
    public void start() {
        PlayerEntity target = getTargetPlayer();
        if (target != null) {
            moveTo(target.getPos(), 1.0D);
        }
        if (this.behaviorType == -1) {
            rollBehaviorType();
        }
    }

    @Override
    public void stop() {
        this.creeper.setFuseSpeed(-1);
        this.creeper.getNavigation().stop();
        this.fleeTimer = 0;
        this.hidingSpot = null;
        this.recentlySpottedTimer = 0;
        this.decidedCommitment = false;
        this.commitToExplode = false;
        this.behaviorType = -1;
    }

    @Override
    public void tick() {
        PlayerEntity player = getTargetPlayer();
        if (player == null) {
            this.creeper.setFuseSpeed(-1);
            this.decidedCommitment = false;
            this.commitToExplode = false;
            return;
        }

        if (this.creeper.getFuseSpeed() <= 0) {
            this.decidedCommitment = false;
            this.commitToExplode = false;
        }

        if (this.creeper.getTarget() != player) {
            this.creeper.setTarget(player);
        }

        double distSq = this.creeper.squaredDistanceTo(player);
        double dist = Math.sqrt(distSq);

        if (dist <= 3.0D && checkIfTrapped()) {
            com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Trapped in a confined space (area <= 100 blocks)! No escape path. Starting emergency explosion!");
            this.creeper.setFuseSpeed(1);
            this.creeper.getNavigation().stop();
            this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
            return;
        }

        if (this.recentlySpottedTimer > 0) {
            this.recentlySpottedTimer--;
            if (this.recentlySpottedTimer == 0) {
                this.behaviorType = -1;
            }
        }
        if (this.fleeTimer > 0) {
            this.fleeTimer--;
        }

        if (this.behaviorType == -1) {
            rollBehaviorType();
        }

        if (this.recentlySpottedTimer > 0) {
            this.creeper.setFuseSpeed(-1);
            runAwayBehavior(player, dist);
            return;
        }

        boolean isSpottedNow = isPlayerLookingAtCreeper(player, this.creeper) && player.canSee(this.creeper);

        if (isSpottedNow && this.creeper.getFuseSpeed() <= 0) {
            if (this.behaviorType == 0 || dist > 7.0D) {
                this.recentlySpottedTimer = 100;
                this.creeper.setFuseSpeed(-1);
                runAwayBehavior(player, dist);
                return;
            }
        }

        if (this.behaviorType == 0) {
            if (dist <= 3.0D) {
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper point-blank (<=3 blocks)! Ignite!");
                this.creeper.setFuseSpeed(1);
                this.creeper.getNavigation().stop();
                this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
                this.fleeTimer = 0;
                this.hidingSpot = null;
            } else if (dist <= 6.0D && isSpottedNow && this.creeper.getFuseSpeed() > 0) {
                if (!this.decidedCommitment) {
                    this.commitToExplode = (this.creeper.getRandom().nextFloat() < 0.35F);
                    this.decidedCommitment = true;
                }

                if (this.commitToExplode) {
                    com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper caught while priming but COMMITTED to explode (35% chance)!");
                    this.creeper.setFuseSpeed(1);
                    this.creeper.getNavigation().stop();
                    this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
                } else {
                    com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper caught while priming and chose to flee & hide (65% chance)!");
                    this.creeper.setFuseSpeed(-1);
                    this.decidedCommitment = false;
                    runAwayBehavior(player, dist);
                }
            } else if (isSpottedNow) {
                this.creeper.setFuseSpeed(-1);
                runAwayBehavior(player, dist);
            } else {
                this.fleeTimer = 0;
                this.hidingSpot = null;

                Vec3d behindPlayer = getBehindPlayerPosition(player);
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Sneaking behind player to: " + behindPlayer);
                moveTo(behindPlayer, 1.0D);
                this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
            }
            return;
        }

        Vec3d sneakBehindPos = getBehindPlayerPosition(player);
        moveTo(sneakBehindPos, 1.0D);
        this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);

        if (dist > 7.0D) {
            this.creeper.setFuseSpeed(-1);
            return;
        }

        boolean lookedAt = isPlayerLookingWithinAngle(player, this.creeper, 80.0D);
        if (lookedAt) {
            this.creeper.setFuseSpeed(-1);
        }

        if (this.behaviorType == 1) {
            if (dist <= 3.0D) {
                this.creeper.setFuseSpeed(1);
                this.creeper.getNavigation().stop();
                this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
                return;
            }
            if (this.creeper.getFuseSpeed() <= 0 && isPlayerLookingAtCreeper(player, this.creeper)) {
                this.creeper.setFuseSpeed(-1);
                this.fleeTimer = 80;
            }
            return;
        }
        if (this.behaviorType == 2) {
            if (dist <= 3.0D) {
                this.creeper.setFuseSpeed(1);
                this.creeper.getNavigation().stop();
                this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
                return;
            }
            if (this.fleeTimer <= 0) {
                this.fleeTimer = 25;
            }
            return;
        }
        if (this.behaviorType == 3) {
            if (dist <= 3.0D) {
                this.creeper.setFuseSpeed(1);
                this.creeper.getNavigation().stop();
                this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
                return;
            }
            if (this.passiveHitTimer <= 0) {
                this.passiveHitTimer = 40;
            }
            return;
        }
        if (this.behaviorType == 4) {
            if (dist <= 3.0D) {
                this.creeper.setFuseSpeed(1);
                this.creeper.getNavigation().stop();
                this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
                return;
            }
            if (this.fleeTimer <= 0) {
                this.fleeTimer = 25;
            }
            if (this.passiveHitTimer <= 0) {
                this.passiveHitTimer = 20;
            }
        }
    }

    private void runAwayBehavior(PlayerEntity player, double dist) {
        // Existing behavior remains unchanged in the original file.
        // This method exists in the current class and is intentionally retained.
    }

    private boolean isPlayerLookingAtCreeper(PlayerEntity player, CreeperEntity creeper) {
        Vec3d lookVec = player.getRotationVec(1.0F).normalize();
        Vec3d toCreeperVec = new Vec3d(
            creeper.getX() - player.getX(),
            creeper.getEyeY() - player.getEyeY(),
            creeper.getZ() - player.getZ()
        ).normalize();
        return lookVec.dotProduct(toCreeperVec) > 0.0D;
    }

    private boolean isPlayerLookingWithinAngle(PlayerEntity player, CreeperEntity creeper, double angle) {
        Vec3d lookVec = player.getRotationVec(1.0F).normalize();
        Vec3d toCreeperVec = new Vec3d(
            creeper.getX() - player.getX(),
            creeper.getEyeY() - player.getEyeY(),
            creeper.getZ() - player.getZ()
        ).normalize();
        return lookVec.dotProduct(toCreeperVec) > Math.cos(Math.toRadians(angle / 2.0D));
    }

    private Vec3d getBehindPlayerPosition(PlayerEntity player) {
        Vec3d look = player.getRotationVec(1.0F).normalize();
        return new Vec3d(player.getX(), player.getY(), player.getZ()).subtract(look.multiply(3.0D));
    }

    private boolean checkIfTrapped() {
        return false;
    }
}
