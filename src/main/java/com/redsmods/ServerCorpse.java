package com.redsmods;

import com.mojang.datafixers.util.Unit;
import com.mojang.math.Transformation;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Rotations;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.redsmods.SummoningCircle.spawnRedParticleCircle;

public class ServerCorpse implements ModInitializer {
        public static final String MOD_ID = "servercorpse";

        @Override
        public void onInitialize() {
                boolean carpetLoaded = FabricLoader.getInstance().isModLoaded("carpet");

                ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
                        if (entity instanceof ServerPlayer player && !(carpetLoaded && CarpetCompat.isFakePlayer(player))) {
                                spawnCorpse(player);
                        }
                });
                SummoningCircle.register();
                ServerLifecycleEvents.SERVER_STARTED.register(SpectatorLimit::init);
                // The spectator leash is enforced by SpectatorLeashMixin (hooked into
                // Entity#move) rather than a per-tick scan of all dead players, and
                // SpectatorTeleportMixin blocks the spectator-menu teleport packet so
                // leashed players can't bypass the leash with the spectator menu.
        }

        private void spawnCorpse(ServerPlayer player) {
                ServerLevel world = (ServerLevel) player.level();

                // 1. Create the Mannequin
                Mannequin corpse = new Mannequin(EntityTypes.MANNEQUIN, world);

                // 2. Set spatial properties
                corpse.setPos(player.getX(), player.getY(), player.getZ());
                corpse.setYRot(player.getYRot());

                // 3. Apply the deceased player's identity (Skin, Cape, etc.)
                ResolvableProfile profile = ResolvableProfile.createResolved(player.getGameProfile());
                corpse.setComponent(DataComponents.PROFILE, profile);
                corpse.setCustomName(Component.literal(player.getName().getString() + "'s corpse"));
                corpse.setCustomNameVisible(true);
                setMannequinDescription(corpse, Component.empty(), true);
                corpse.setComponent(DataComponents.PROFILE, profile);
                corpse.refreshDimensions();

                // 4. Set the state
                // SLEEPING pose is the only one that makes them lay flat naturally.
                corpse.setPose(Pose.SWIMMING);

                // By default, Mannequin.DATA_IMMOVABLE is false, allowing pistons and gravity
                // to interact with it just like a regular mob.
                corpse.setInvulnerable(true);

                // 5. Transfer items
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                        ItemStack stack = player.getItemBySlot(slot);
                        if (!stack.isEmpty()) {
                                corpse.setItemSlot(slot, stack.copy());
                        }
                }

                // 6. Spawn into the world
                world.addFreshEntity(corpse);

                // 7. Record the death position so SpectatorLimit can leash the player once
                // vanilla forces them into spectator mode (hardcore). In non-hardcore the
                // entry is cleaned up on the next tick when the player respawns as survival,
                // so this never affects normal play.
                SpectatorLimit.trackDeath(player);
        }
        private void setMannequinDescription(Mannequin corpse, Component description, boolean hide) {
                try {
                        Method setDesc = Mannequin.class.getDeclaredMethod("setDescription", Component.class);
                        setDesc.setAccessible(true);
                        setDesc.invoke(corpse, description);

                        Method setHide = Mannequin.class.getDeclaredMethod("setHideDescription", boolean.class);
                        setHide.setAccessible(true);
                        setHide.invoke(corpse, hide);
                } catch (Exception e) {
                        e.printStackTrace();
                }
        }
}