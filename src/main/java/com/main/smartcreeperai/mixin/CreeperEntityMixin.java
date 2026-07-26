package com.main.smartcreeperai.mixin;

import com.main.smartcreeperai.CreeperInventoryProvider;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreeperEntity.class)
public abstract class CreeperEntityMixin extends HostileEntity implements CreeperInventoryProvider {

    @Shadow private int lastFuseTime;
    @Shadow private int currentFuseTime;
    @Shadow private int fuseTime;
    @Shadow public abstract void explode();
    @Shadow public abstract boolean isIgnited();
    @Shadow public abstract void setFuseSpeed(int fuseSpeed);
    @Shadow public abstract int getFuseSpeed();

    @Unique
    private final SimpleInventory tntInventory = new SimpleInventory(1);

    protected CreeperEntityMixin(EntityType<? extends HostileEntity> entityType, World world) {
        super(entityType, world);
    }

    @Inject(method = "createCreeperAttributes", at = @At("RETURN"), cancellable = true)
    private static void onCreateCreeperAttributes(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<?> cir) {
        // SUCCESSFUL FIX: Extract the object reference and mutate it directly.
        // Skipping setReturnValue completely bypasses the strict generic wildcard compiler lock!
        Object returnValue = cir.getReturnValue();
        if (returnValue instanceof net.minecraft.entity.attribute.DefaultAttributeContainer.Builder builder) {
            builder.add(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.0D);
        }
    }

    @Inject(method = "initGoals", at = @At("TAIL"))
    private void onInitGoals(CallbackInfo ci) {
        // Clear vanilla CreeperIgniteGoal
        this.goalSelector.clear(goal -> goal instanceof net.minecraft.entity.ai.goal.CreeperIgniteGoal);
        
        // --- GOAL PRIORITY OVERHAUL ---
        // We push the Door and Trapdoor interact goals to Priority 0 and 1.
        // This forces the AI engine to temporarily pause Chasing/Fleeing goals when a door is hit.
        
        // Add door-opening goal at priority 0 (Absolute highest priority so it breaks chases/flee freezes)
        this.goalSelector.add(0, new com.main.smartcreeperai.CreeperOpenDoorGoal((CreeperEntity)(Object)this));

        // Add custom trapdoor goal at priority 1 (Absolute highest priority)
        this.goalSelector.add(1, new com.main.smartcreeperai.CreeperInteractTrapdoorGoal((CreeperEntity)(Object)this));

        // Add SneakyCreeperGoal at priority 2 (Moved down slightly so doors override it)
        this.goalSelector.add(2, new com.main.smartcreeperai.SneakyCreeperGoal((CreeperEntity)(Object)this));

        // Add towering goal at priority 4 
        this.goalSelector.add(4, new com.main.smartcreeperai.CreeperTowerGoal((CreeperEntity)(Object)this));

        // Enable door navigation
        if (this.getNavigation() instanceof net.minecraft.entity.ai.pathing.MobNavigation) {
            ((net.minecraft.entity.ai.pathing.MobNavigation) this.getNavigation()).setCanPathThroughDoors(true);
        }

        // Increase follow range attribute to 64 blocks so it doesn't lose track of player
        if (this.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_FOLLOW_RANGE) != null) {
            this.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_FOLLOW_RANGE).setBaseValue(64.0D);
        }

        // Grant attack damage to creeper: Set to 4.0 (2 hearts of damage)
        if (this.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE) != null) {
            this.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(4.0D);
        } else {
            this.getAttributes().getCustomInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE);
            if (this.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE) != null) {
                this.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(4.0D);
            }
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void onTick(CallbackInfo ci) {
        if (this.isAlive()) {
            this.lastFuseTime = this.currentFuseTime;
            if (this.isIgnited()) {
                this.setFuseSpeed(1);
            }
            int i = this.getFuseSpeed();
            if (i > 0 && this.currentFuseTime == 0) {
                this.playSound(net.minecraft.sound.SoundEvents.ENTITY_CREEPER_PRIMED, 1.0F, 0.5F);
                this.emitGameEvent(net.minecraft.world.event.GameEvent.PRIME_FUSE);
            }
            this.currentFuseTime += i;
            if (this.currentFuseTime < 0) {
                this.currentFuseTime = 0;
            }
            if (this.currentFuseTime >= this.fuseTime) {
                this.currentFuseTime = this.fuseTime;
                this.explode();
            }
        }
        super.tick();
        ci.cancel();
    }

    @Override
    public SimpleInventory getTntInventory() {
        return this.tntInventory;
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("HEAD"))
    private void writeTntInventoryNbt(NbtCompound nbt, CallbackInfo ci) {
        nbt.put("TntInventory", this.tntInventory.toNbtList(this.getRegistryManager()));
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("HEAD"))
    private void readTntInventoryNbt(NbtCompound nbt, CallbackInfo ci) {
        if (nbt.contains("TntInventory", 9)) { // 9 is NbtElement.LIST_TYPE
            this.tntInventory.readNbtList(nbt.getList("TntInventory", 10), this.getRegistryManager());
        }
    }

    @Override
    protected void dropInventory() {
        super.dropInventory();
        com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Creeper died. Processing custom inventory drop. Size: " + this.tntInventory.size());
        for (int i = 0; i < this.tntInventory.size(); i++) {
            ItemStack stack = this.tntInventory.getStack(i);
            if (!stack.isEmpty()) {
                com.main.smartcreeperai.SmartCreeperMod.LOGGER.info("[SmartCreeperAI] Successfully dropped item stack from creeper inventory: " + stack.getItem() + " x" + stack.getCount());
                this.dropStack(stack);
            }
        }
        this.tntInventory.clear();
    }
}
