package com.redsmods.mixin;

import com.redsmods.SummoningCircle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.redsmods.SummoningCircle.spawnRedParticleCircle;

@Mixin(Level.class)
public class BlockPlaceMixin {

	@Inject(
			method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
			at = @At("RETURN")
	)
	private void onSetBlock(BlockPos pos, BlockState blockState, int updateFlags, CallbackInfoReturnable<Boolean> cir) {
		Level level = (Level)(Object)this;
		if (level.isClientSide()) return;

		if (blockState.is(Blocks.STRUCTURE_VOID)) {
			// spawn particles - cast to ServerLevel
			ServerLevel serverLevel = (ServerLevel) level;
			// call your particle method here
			SummoningCircle.addCircle(serverLevel, pos);
		}
	}
}