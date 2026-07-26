package com.main.smartcreeperai.mixin;
import com.main.smartcreeperai.CreeperInventoryProvider;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEntity.class)
public abstract class MobEntityMixin {

    @Inject(
        method = "initialize(Lnet/minecraft/world/ServerWorldAccess;Lnet/minecraft/world/LocalDifficulty;Lnet/minecraft/entity/SpawnReason;Lnet/minecraft/entity/EntityData;)Lnet/minecraft/entity/EntityData;",
        at = @At("RETURN")
    )
    private void onInitialize(
        ServerWorldAccess world,
        LocalDifficulty difficulty,
        SpawnReason spawnReason,
        EntityData entityData,
        CallbackInfoReturnable<EntityData> cir
    ) {
        // Safe check to verify we are targeting a CreeperEntity
        if ((Object) this instanceof CreeperEntity) {
            CreeperInventoryProvider provider = (CreeperInventoryProvider) this;
            SimpleInventory inv = provider.getTntInventory();
            if (inv != null) {
                inv.setStack(0, new ItemStack(Items.TNT, 128));
            }
        }
    }
}
