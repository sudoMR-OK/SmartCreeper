package com.main.smartcreeperai.mixin;

import com.main.smartcreeperai.CreeperInventoryProvider;
import com.main.smartcreeperai.CreeperTowerControlProvider;
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
public abstract class CreeperEntityMixin extends HostileEntity implements CreeperInventoryProvider, CreeperTowerControlProvider {

    @Shadow private int lastFuseTime;
    @Shadow private int currentFuseTime;
    @Shadow private int fuseTime;
    @Shadow public abstract void explode();
    @Shadow public abstract boolean isIgnited();
    @Shadow public abstract void setFuseSpeed(int fuseSpeed);
    @Shadow public abstract int getFuseSpeed();

    @Unique
    private final SimpleInventory tntInventory = new SimpleInventory(1);

    @Unique
    private boolean smartcreeperai$towerControlLocked;

    protected CreeperEntityMixin(EntityType<? extends HostileEntity> entityType, World world) {
        super(entityType, world);
    }

    @Inject(method = "createCreeperAttributes", at = @At("RETURN"), cancellable = true)
    private static void onCreateCreeperAttributes(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<?> cir) {
        Object returnValue = cir.getReturnValue();
        if (returnValue instanceof net.minecraft.entity.attribute.DefaultAttributeContainer.Builder builder) {
            builder.add(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.0D);
        }
    }

    @Inject(method = "initGoals", at = @At("TAIL"))
    private void onInitGoals(CallbackInfo ci) {
        this.goalSelector.clear(goal -> goal instanceof net.minecraft.entity.ai.goal.CreeperIgniteGoal);

        this.goalSelector.add(0, new com.main.smartcreeperai.CreeperOpenDoorGoal((CreeperEntity)(Object)this));
        this.goalSelector.add(1, new com.main.smartcreeperai.CreeperInteractTrapdoorGoal((CreeperEntity)(Object)this));
        this.goalSelector.add(2, new com.main.smartcreeperai.CreeperTowerGoal((CreeperEntity)(Object)this));
        this.goalSelector.add(3, new com.main.smartcreeperai.SneakyCreeperGoal((CreeperEntity)(Object)this));

        if (this.getNavigation() instanceof net.minecraft.entity.ai.pathing.MobNavigation) {
            ((net.minecraft.entity.ai.pathing.MobNavigation) this.getNavigation()).setCanPathThroughDoors(true);
        }

        if (this.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_FOLLOW_RANGE) != null) {
            this.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_FOLLOW_RANGE).setBaseValue(64.0D);
        }

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

    @Override
    public boolean smartcreeperai$isTowerControlLocked() {
        return this.smartcreeperai$towerControlLocked;
    }

    @Override
    public void smartcreeperai$setTowerControlLocked(boolean locked) {
        this.smartcreeperai$towerControlLocked = locked;
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("HEAD"))
    private void writeTntInventoryNbt(NbtCompound nbt, CallbackInfo ci) {
        nbt.put("TntInventory", this.tntInventory.toNbtList(this.getRegistryManager()));
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("HEAD"))
    private void readTntInventoryNbt(NbtCompound nbt, CallbackInfo ci) {
        if (nbt.contains("TntInventory", 9)) {
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
