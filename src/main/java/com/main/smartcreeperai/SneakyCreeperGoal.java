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
        // Fallback: search for the closest non-creative, non-spectator player within 64 blocks
        PlayerEntity closest = this.creeper.getWorld().getClosestPlayer(this.creeper, 64.0D);
        if (closest != null && closest.isAlive() && !closest.isSpectator() && !closest.isCreative()) {
            return closest;
        }
        return null;
    }

    private void moveTo(Vec3d pos, double speed) {
        if (pos == null) return;
        // Periodic check to prevent pathfinder from constant recalculation jitter
        if (this.creeper.age % 5 == 0 || this.creeper.getNavigation().isIdle()) {
            this.creeper.getNavigation().startMovingTo(pos.x, pos.y, pos.z, speed);
        }
    }

    private boolean shouldYieldForScaffolding(PlayerEntity target) {
        // If player is looking at us or we are fleeing, stealthiness & hiding is priority #1! Never yield.
        if (this.recentlySpottedTimer > 0 || (isPlayerLookingAtCreeper(target, this.creeper) && target.canSee(this.creeper))) {
            return false;
        }

        // Check if player is elevated (>= 2 blocks above)
        if (target.getY() - this.creeper.getY() < 2.0D) {
            return false;
        }

        net.minecraft.entity.ai.pathing.Path path = this.creeper.getNavigation().findPathTo(target, 0);
        if (path != null && path.reachesTarget()) {
            return false; // A standard path reaches target directly
        }

        // Check if we arrived at the closest path end
        if (path != null) {
            net.minecraft.entity.ai.pathing.PathNode endNode = path.getEnd();
            if (endNode != null) {
                double distSqToEnd = this.creeper.squaredDistanceTo(endNode.x + 0.5D, endNode.y, endNode.z + 0.5D);
                // If we are still > 2.25 blocks from the end of closest path, keep sneaking along it!
                if (distSqToEnd > 2.25D) {
                    return false;
                }
            }
        }

        // We are at the closest path end (or stuck) and player is high up -> yield for towering (priority 4)!
        return true;
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
            moveTo(target.getPos(), 1.0D); // Always default vanilla speed
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
        this.behaviorType = -1; // Reset behavior to roll again next encounter
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

        // Keep current target active
        if (this.creeper.getTarget() != player) {
            this.creeper.setTarget(player);
        }

        double distSq = this.creeper.squaredDistanceTo(player);
        double dist = Math.sqrt(distSq);

        // Emergency Trapped/No-Escape Behavior in limited area (<= 100 blocks)
        if (dist <= 3.0D && checkIfTrapped()) {
            com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Trapped in a confined space (area <= 100 blocks)! No escape path. Starting emergency explosion!");
            this.creeper.setFuseSpeed(1);
            this.creeper.getNavigation().stop();
            this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
            return;
        }

        // Tick down timers first for reliable async behavior matching
        if (this.recentlySpottedTimer > 0) {
            this.recentlySpottedTimer--;
            if (this.recentlySpottedTimer == 0) {
                this.behaviorType = -1; // Reset behavior to roll again next time!
            }
        }
        if (this.fleeTimer > 0) {
            this.fleeTimer--;
        }

        if (this.behaviorType == -1) {
            rollBehaviorType();
        }

        // If previously spotted, continue to escape and hide (flight mode remains consistent for both sets)
        if (this.recentlySpottedTimer > 0) {
            this.creeper.setFuseSpeed(-1);
            runAwayBehavior(player, dist);
            return;
        }

        // 180° field-of-view check (dot product > 0.0 means in front of player's face/eyes)
        boolean isSpottedNow = isPlayerLookingAtCreeper(player, this.creeper) && player.canSee(this.creeper);

        // Stealth approach guard: if player is looking at us while we are far away (during approach) or in standard mode, we must flee!
        if (isSpottedNow && this.creeper.getFuseSpeed() <= 0) {
            if (this.behaviorType == 0 || dist > 7.0D) {
                this.recentlySpottedTimer = 100;
                this.creeper.setFuseSpeed(-1);
                runAwayBehavior(player, dist);
                return;
            }
        }

        // Standard behavioral logic (60% split)
        if (this.behaviorType == 0) {
            if (dist <= 3.0D) {
                // Point-blank: Explode immediately, no matter what!
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper point-blank (<=3 blocks)! Ignite!");
                this.creeper.setFuseSpeed(1);
                this.creeper.getNavigation().stop();
                this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
                this.fleeTimer = 0;
                this.hidingSpot = null;
            } else if (dist <= 6.0D && isSpottedNow && this.creeper.getFuseSpeed() > 0) {
                // Caught while priming close but outside point-blank: Decide between stand-and-explode (35%) or flee-and-hide (65%)
                if (!this.decidedCommitment) {
                    this.commitToExplode = (this.creeper.getRandom().nextFloat() < 0.35F);
                    this.decidedCommitment = true;
                }

                if (this.commitToExplode) {
                    // Standing ground to exploding: Option 2 (35% chance)
                    com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper caught while priming but COMMITTED to explode (35% chance)!");
                    this.creeper.setFuseSpeed(1);
                    this.creeper.getNavigation().stop();
                    this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
                } else {
                    // Fleeing and hiding: Option 1 (65% chance)
                    com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper caught while priming and chose to flee & hide (65% chance)!");
                    this.creeper.setFuseSpeed(-1);
                    this.decidedCommitment = false; // Reset so that if it starts priming again later it gets another roll
                    runAwayBehavior(player, dist);
                }
            } else if (isSpottedNow) {
                // Flee or alert/hide state (either currently seen, or recently spotted and player knows we are here)
                this.creeper.setFuseSpeed(-1);
                runAwayBehavior(player, dist);
            } else {
                // Not spotted and not alert: Sneak up behind the player at default vanilla speed (1.0D)
                this.fleeTimer = 0;
                this.hidingSpot = null;

                Vec3d behindPlayer = getBehindPlayerPosition(player);
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Sneaking behind player to: " + behindPlayer);
                moveTo(behindPlayer, 1.0D); // Always default vanilla speed
                this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
            }
            return;
        }

        // Sub-behavior 40% personalities splits: Approach player from behind but execute specialized sequences
        Vec3d sneakBehindPos = getBehindPlayerPosition(player);
        moveTo(sneakBehindPos, 1.0D);
        this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);

        // Make sure creeper first gets close to player (at least within 7.0 blocks) before starting sub-behaviors
        if (dist > 7.0D) {
            this.creeper.setFuseSpeed(-1);
            return;
        }

        boolean lookedAt = isPlayerLookingWithinAngle(player, this.creeper, 80.0D);

        if (this.behaviorType == 1) {
            // Sub-behavior 1: Silent Stand + Look Trigger
            this.creeper.setFuseSpeed(-1);

            if (lookedAt) {
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Personality 1 (Silent Stand): Player looked! Hit and flee!");
                if (dist <= 2.5D) {
                    deliverFistHit(player);
                }
                this.recentlySpottedTimer = 100;
                this.creeper.setFuseSpeed(-1);
                runAwayBehavior(player, dist);
            }
        } 
        else if (this.behaviorType == 2) {
            // Sub-behavior 2: Fuss-Cancel Loop + Look Trigger
            if (lookedAt) {
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Personality 2 (Fuss-Cancel): Player looked! Deciding trigger action...");
                boolean hit = this.creeper.getRandom().nextBoolean(); // 50% / 50%
                if (hit && dist <= 2.5D) {
                    deliverFistHit(player);
                }
                this.recentlySpottedTimer = 100;
                this.creeper.setFuseSpeed(-1);
                runAwayBehavior(player, dist);
            } else {
                // Repeatedly start fuse for a few ticks, then cancel
                int cycle = this.creeper.age % 25;
                if (cycle < 6) {
                    this.creeper.setFuseSpeed(1);
                } else {
                    this.creeper.setFuseSpeed(-1);
                }
            }
        } 
        else if (this.behaviorType == 3) {
            // Sub-behavior 3: Passive Fist Hits + Look Trigger
            if (lookedAt) {
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Personality 3 (Passive Hits): Player looked! Deciding trigger action...");
                boolean hit = this.creeper.getRandom().nextBoolean(); // 50% / 50%
                if (hit && dist <= 2.5D) {
                    deliverFistHit(player);
                }
                this.recentlySpottedTimer = 100;
                this.creeper.setFuseSpeed(-1);
                runAwayBehavior(player, dist);
            } else {
                this.creeper.setFuseSpeed(-1);
                if (dist <= 2.5D) {
                    if (this.passiveHitTimer <= 0) {
                        int hits = this.creeper.getRandom().nextBoolean() ? 1 : 2;
                        for (int i = 0; i < hits; i++) {
                            deliverFistHit(player);
                        }
                        this.passiveHitTimer = 60 + this.creeper.getRandom().nextInt(60); // Cooldown of 3-6s
                        com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Personality 3: Periodic passive hits delivered! Next in " + this.passiveHitTimer);
                    } else {
                        this.passiveHitTimer--;
                    }
                }
            }
        } 
        else if (this.behaviorType == 4) {
            // Sub-behavior 4: Combined + Look Trigger
            if (lookedAt) {
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Personality 4 (Combined): Player looked! Deciding action...");
                float roll = this.creeper.getRandom().nextFloat();
                if (roll < 0.333F) {
                    if (dist <= 2.5D) {
                        deliverFistHit(player);
                    }
                    this.recentlySpottedTimer = 100;
                    this.creeper.setFuseSpeed(-1);
                    runAwayBehavior(player, dist);
                } else if (roll < 0.666F) {
                    this.recentlySpottedTimer = 100;
                    this.creeper.setFuseSpeed(-1);
                    runAwayBehavior(player, dist);
                } else {
                    com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Personality 4: Gaze committed instantly to direct explosion!");
                    this.creeper.setFuseSpeed(1);
                    this.creeper.getNavigation().stop();
                    this.creeper.getLookControl().lookAt(player, 30.0F, 30.0F);
                }
            } else {
                // Fuss-cancel cycle
                int cycle = this.creeper.age % 25;
                if (cycle < 6) {
                    this.creeper.setFuseSpeed(1);
                } else {
                    this.creeper.setFuseSpeed(-1);
                }

                // Periodic damage hits
                if (dist <= 2.5D) {
                    if (this.passiveHitTimer <= 0) {
                        int hits = this.creeper.getRandom().nextBoolean() ? 1 : 2;
                        for (int i = 0; i < hits; i++) {
                            deliverFistHit(player);
                        }
                        this.passiveHitTimer = 60 + this.creeper.getRandom().nextInt(60);
                        com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Personality 4: Periodic combined hits delivered! Next in " + this.passiveHitTimer);
                    } else {
                        this.passiveHitTimer--;
                    }
                }
            }
        }
    }

    private void runAwayBehavior(PlayerEntity player, double dist) {
        // Look for a hiding spot if we are at least 10 blocks away
        if (dist >= 10.0D) {
            if (this.hidingSpot == null || this.fleeTimer <= 0 || (this.creeper.age % 20 == 0 && isSpotVisibleToPlayer(player, this.hidingSpot))) {
                this.hidingSpot = findHidingSpot(player);
                this.fleeTimer = 80; // 4 seconds path duration
            }
        } else {
            this.hidingSpot = null;
        }

        if (this.hidingSpot != null) {
            double distToHidingSpot = this.creeper.getPos().distanceTo(this.hidingSpot);
            if (distToHidingSpot <= 1.5D) {
                // Successfully reached the spot, stop moving and stay hidden
                this.creeper.getNavigation().stop();
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] In hiding spot, staying hidden!");
            } else {
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Moving to secure hiding spot: " + this.hidingSpot);
                moveTo(this.hidingSpot, 2.0D);
            }
        } else {
            // No valid hiding spots, or too close (<10 blocks): run sideways and away to get distance and break line of sight
            Vec3d escapeDir = this.creeper.getPos().subtract(player.getPos()).normalize();
            
            // Slower sway cycle (every 40 ticks = 2 seconds) for smoother angle movement
            int phase = (this.creeper.age / 40) % 3;
            Vec3d driftVec = Vec3d.ZERO;
            double driftAmt = 0.6D;
            if (phase == 1) {
                driftVec = new Vec3d(-escapeDir.z, 0.0, escapeDir.x).normalize().multiply(driftAmt);
            } else if (phase == 2) {
                driftVec = new Vec3d(escapeDir.z, 0.0, -escapeDir.x).normalize().multiply(driftAmt);
            }
            
            Vec3d finalEscapeDir = escapeDir.add(driftVec).normalize();
            Vec3d escapeSpot = player.getPos().add(finalEscapeDir.multiply(32.0D));

            com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Running away (evading sight) to escape destination: " + escapeSpot);
            moveTo(escapeSpot, 2.0D);
        }
    }

    private boolean isPlayerLookingAtCreeper(PlayerEntity player, CreeperEntity creeper) {
        Vec3d lookVec = player.getRotationVec(1.0F).normalize();
        Vec3d toCreeperVec = new Vec3d(
            creeper.getX() - player.getX(),
            creeper.getEyeY() - player.getEyeY(),
            creeper.getZ() - player.getZ()
        ).normalize();
        
        double dot = lookVec.dotProduct(toCreeperVec);
        return dot > 0.0D; // 180° coverage sweep envelope
    }

    private boolean isSpotVisibleToPlayer(PlayerEntity player, Vec3d pos) {
        if (pos == null) return false;
        double lookDot = getPlayerLookDotToPosition(player, pos);
        if (lookDot <= 0.0D) {
            // Outside 180° FOV, cannot be seen
            return false;
        }
        // If inside 180° FOV, check if there are blocks blocking line-of-sight
        Vec3d eyePos = new Vec3d(player.getX(), player.getEyeY(), player.getZ());
        return !isOccluded(this.creeper.getWorld(), eyePos, pos, player);
    }

    private boolean isOccluded(net.minecraft.world.World world, Vec3d start, Vec3d end, PlayerEntity player) {
        net.minecraft.world.RaycastContext context = new net.minecraft.world.RaycastContext(
            start,
            end,
            net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
            net.minecraft.world.RaycastContext.FluidHandling.NONE,
            player
        );
        net.minecraft.util.hit.BlockHitResult hitResult = world.raycast(context);
        return hitResult.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK;
    }

    private Vec3d findHidingSpot(PlayerEntity player) {
        Vec3d creeperPos = this.creeper.getPos();
        Vec3d bestSpot = null;
        double bestScore = -Double.MAX_VALUE;

        // Sample nearby blocks around the creeper (X, Z step 3, Y step 1 for efficiency)
        for (int xOffset = -15; xOffset <= 15; xOffset += 3) {
            for (int zOffset = -15; zOffset <= 15; zOffset += 3) {
                for (int yOffset = -3; yOffset <= 3; yOffset += 1) {
                    if (xOffset == 0 && zOffset == 0) continue;

                    Vec3d candidate = creeperPos.add(xOffset, yOffset, zOffset);
                    double distToCreeper = candidate.distanceTo(creeperPos);

                    // Quick distance filtering before expensive calls
                    if (distToCreeper < 2.0D || distToCreeper > 18.0D) {
                        continue;
                    }

                    // Confirm path is walkable first
                    if (!isWalkable(candidate)) {
                        continue;
                    }

                    // Only run line-of-sight raycasting if candidate is walkable
                    if (isSpotVisibleToPlayer(player, candidate)) {
                        continue;
                    }

                    double distToPlayer = candidate.distanceTo(player.getPos());

                    // Score calculation: prefer further from player, closer to creeper
                    double score = distToPlayer - (distToCreeper * 0.3D);
                    if (score > bestScore) {
                        bestScore = score;
                        bestSpot = candidate;
                    }
                }
            }
        }

        return bestSpot;
    }

    private double getPlayerLookDotToPosition(PlayerEntity player, Vec3d pos) {
        Vec3d lookVec = player.getRotationVec(1.0F).normalize();
        Vec3d toPosVec = new Vec3d(
            pos.x - player.getX(),
            pos.y - player.getEyeY(),
            pos.z - player.getZ()
        ).normalize();
        return lookVec.dotProduct(toPosVec);
    }

    private boolean isWalkable(Vec3d pos) {
        net.minecraft.util.math.BlockPos blockPos = net.minecraft.util.math.BlockPos.ofFloored(pos.x, pos.y, pos.z);
        net.minecraft.world.World world = this.creeper.getWorld();
        
        return world.getBlockState(blockPos).isAir()
            && world.getBlockState(blockPos.up()).isAir()
            && !world.getBlockState(blockPos.down()).isAir();
    }

    private Vec3d getBehindPlayerPosition(PlayerEntity player) {
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d lookHorizontal = new Vec3d(look.x, 0.0D, look.z).normalize();
        return player.getPos().subtract(lookHorizontal.multiply(1.5D));
    }

    private void deliverFistHit(PlayerEntity player) {
        this.creeper.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        this.creeper.getWorld().playSound(null, this.creeper.getX(), this.creeper.getY(), this.creeper.getZ(), 
            net.minecraft.sound.SoundEvents.ENTITY_PLAYER_ATTACK_WEAK, net.minecraft.sound.SoundCategory.HOSTILE, 1.0F, 1.0F);
        player.damage(this.creeper.getDamageSources().mobAttack(this.creeper), 4.0F); // 2 hearts of direct strike damage
    }

    private boolean isPlayerLookingWithinAngle(PlayerEntity player, CreeperEntity creeper, double angleDegrees) {
        Vec3d lookVec = player.getRotationVec(1.0F).normalize();
        Vec3d toCreeperVec = new Vec3d(
            creeper.getX() - player.getX(),
            creeper.getEyeY() - player.getEyeY(),
            creeper.getZ() - player.getZ()
        ).normalize();
        double dot = lookVec.dotProduct(toCreeperVec);
        double threshold = Math.cos(Math.toRadians(angleDegrees));
        return dot >= threshold && player.canSee(creeper);
    }

    private boolean checkIfTrapped() {
        net.minecraft.world.World world = this.creeper.getWorld();
        net.minecraft.util.math.BlockPos start = this.creeper.getBlockPos();
        
        java.util.Set<net.minecraft.util.math.BlockPos> visited = new java.util.HashSet<>();
        java.util.Queue<net.minecraft.util.math.BlockPos> queue = new java.util.LinkedList<>();
        
        queue.add(start);
        visited.add(start);
        
        int minX = start.getX();
        int maxX = start.getX();
        int minZ = start.getZ();
        int maxZ = start.getZ();
        
        int count = 0;
        
        while (!queue.isEmpty()) {
            net.minecraft.util.math.BlockPos curr = queue.poll();
            count++;
            
            // If we have visited more than 100 blocks, we are definitely NOT trapped!
            if (count > 100) {
                return false;
            }
            
            // Check horizontal adjacencies (North, South, East, West)
            net.minecraft.util.math.BlockPos[] neighbors = {
                curr.east(), curr.west(), curr.north(), curr.south()
            };
            
            for (net.minecraft.util.math.BlockPos neighbor : neighbors) {
                net.minecraft.util.math.BlockPos target = null;
                
                // Allow dynamic hopping: flat (0), step up (+1), step down (-1 or -2)
                if (isWalkableAt(world, neighbor)) {
                    target = neighbor;
                } else if (isWalkableAt(world, neighbor.up())) {
                    target = neighbor.up();
                } else if (isWalkableAt(world, neighbor.down())) {
                    target = neighbor.down();
                } else if (isWalkableAt(world, neighbor.down().down())) {
                    target = neighbor.down().down();
                }
                
                if (target != null && !visited.contains(target)) {
                    visited.add(target);
                    queue.add(target);
                    
                    if (target.getX() < minX) minX = target.getX();
                    if (target.getX() > maxX) maxX = target.getX();
                    if (target.getZ() < minZ) minZ = target.getZ();
                    if (target.getZ() > maxZ) maxZ = target.getZ();
                    
                    int area = (maxX - minX + 1) * (maxZ - minZ + 1);
                    if (area > 100) {
                        return false; // Bounding box area exceeds 100 blocks, so not trapped!
                    }
                }
            }
        }
        
        int finalArea = (maxX - minX + 1) * (maxZ - minZ + 1);
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        double ratio = (double) spanX / (double) spanZ;
        return finalArea <= 100 && count <= 100 && ratio >= 0.01D && ratio <= 100.0D;
    }

    private boolean isWalkableAt(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos) {
        return isPassable(world, pos) && isPassable(world, pos.up()) && !isPassable(world, pos.down());
    }

    private boolean isPassable(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos) {
        net.minecraft.block.BlockState state = world.getBlockState(pos);
        if (state.isAir()) return true;
        try {
            return state.canPathfindThrough(net.minecraft.entity.ai.pathing.NavigationType.LAND);
        } catch (Exception e) {
            return state.getCollisionShape(world, pos, net.minecraft.block.ShapeContext.absent()).isEmpty();
        }
    }
}
