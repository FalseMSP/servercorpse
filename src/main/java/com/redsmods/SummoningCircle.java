package com.redsmods;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.PardonCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanList;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.players.PlayerList;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class SummoningCircle {
    private static final DustParticleOptions RED_DUST =
            new DustParticleOptions(colorToInt(1f, 0f, 0f), 1.0f);

    private static final double RADIUS = 1.0;

    // 10 hearts = vanilla's normal max health
    private static final double NORMAL_MAX_HEALTH = 20.0;

    // 1 heart per golden apple
    private static final double HEALTH_PER_APPLE = 2.0;

    private static final Map<ServerLevel, Set<BlockPos>> ACTIVE_CIRCLES = new HashMap<>();

    // Orbital strike tuning, styled after the Unstable SMP / cubicmetre orbital strike cannon:
    // real primed TNT dropped in concentric rings from above. Each shell's fuse is calculated
    // from a simulated fall time so it always lands before it detonates, instead of a fixed
    // fuse that can go off mid-air.
    private static final int NUKE_RING_COUNT = 16;          // rings of TNT around the center - wide blast radius
    private static final double NUKE_RING_SPACING = 3.0;    // blocks between each ring (~48 block radius at the outer ring)
    private static final double NUKE_DROP_HEIGHT = 40.0;    // how high above the target the shells start
    private static final double NUKE_RING_HEIGHT_STEP = 1.0;// outer rings drop from a touch higher
    private static final int NUKE_TNT_PER_RING_STEP = 6;    // TNT added per ring, so outer rings are denser
    private static final int NUKE_LAND_DELAY_TICKS = 8;     // pause after landing, before it detonates
    private static final int NUKE_FUSE_JITTER_TICKS = 3;    // small random spread so shells don't all pop on the same tick

    // Mushroom cloud tuning - the cloud is made of real explosions (visual/sound only, no extra
    // block damage) so it actually looks and sounds like a chain of blasts climbing skyward.
    private static final int STRIKE_CLOUD_TICKS = 35;           // shorter = the cloud blooms out faster
    private static final double CLOUD_MAX_STEM_HEIGHT = 32.0;   // how tall the stem climbs
    private static final double CLOUD_MAX_CAP_RADIUS = 26.0;    // how wide the cap spreads
    private static final float CLOUD_EXPLOSION_POWER = 3.5F;    // visual explosion size (no block damage)
    private static final int CLOUD_EXPLOSION_INTERVAL_TICKS = 2;// how often a pulse of real explosions fires
    private static final int CLOUD_EXPLOSIONS_PER_PULSE = 10;   // real explosions triggered per pulse

    private static final List<StrikeAnimation> ACTIVE_STRIKES = new CopyOnWriteArrayList<>();
    private static final java.util.Random RANDOM = new java.util.Random();

    private static class StrikeAnimation {
        final ServerLevel level;
        final Vec3 origin;
        final int cloudStartTick;
        int tick = 0;

        StrikeAnimation(ServerLevel level, Vec3 origin, int cloudStartTick) {
            this.level = level;
            this.origin = origin;
            this.cloudStartTick = cloudStartTick;
        }
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SummoningCircle::onServerTick);
    }

    public static void addCircle(ServerLevel level, BlockPos pos) {
        ACTIVE_CIRCLES.computeIfAbsent(level, l -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }

    public static void removeCircle(ServerLevel level, BlockPos pos) {
        Set<BlockPos> set = ACTIVE_CIRCLES.get(level);
        if (set != null) set.remove(pos);
    }

    private static void onServerTick(MinecraftServer server) {
        for (Map.Entry<ServerLevel, Set<BlockPos>> entry : ACTIVE_CIRCLES.entrySet()) {
            ServerLevel level = entry.getKey();
            Iterator<BlockPos> iter = entry.getValue().iterator();

            while (iter.hasNext()) {
                BlockPos pos = iter.next();

                if (!level.getBlockState(pos).is(Blocks.STRUCTURE_VOID)) {
                    iter.remove();
                    continue;
                }

                Vec3 center = Vec3.atCenterOf(pos);
                spawnRedParticleCircle(level, center);
                checkForRevival(level, pos, center);
                checkForHeartGain(level, pos, center);
                checkForOrbitalStrike(level, pos, center);
            }
        }

        tickOrbitalStrikes();
    }

    private static void checkForRevival(ServerLevel level, BlockPos voidPos, Vec3 center) {
        AABB searchBox = new AABB(
                center.x - RADIUS, center.y - 1, center.z - RADIUS,
                center.x + RADIUS, center.y + 1, center.z + RADIUS
        );

        // Find a dropped totem item in radius
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox);
        ItemEntity totemEntity = null;
        for (ItemEntity item : items) {
            ItemStack stack = item.getItem();
            if (stack.is(Items.TOTEM_OF_UNDYING) || stack.is(ServerCorpseTags.REVIVE)) {
                totemEntity = item;
                break;
            }
        }
        if (totemEntity == null) return;

        // Find a Mannequin corpse in radius
        List<Mannequin> corpses = level.getEntitiesOfClass(Mannequin.class, searchBox);
        if (corpses.isEmpty()) return;

        Mannequin corpse = corpses.get(0);

        // Get the associated player's UUID from the resolved profile
        var profileComponent = corpse.get(net.minecraft.core.component.DataComponents.PROFILE);
        if (profileComponent == null) return;

        GameProfile playerId = profileComponent.partialProfile();
        if (playerId == null) return;
        if (level.getServer().getPlayerList().getPlayer(playerId.id()) == null) return;

        // Consume the totem
        ItemStack totemStack = totemEntity.getItem();
        totemStack.shrink(1);
        if (totemStack.isEmpty()) {
            totemEntity.discard();
        }

        // revive
        revivePlayer(level, corpse, playerId);
        level.setBlock(voidPos, Blocks.AIR.defaultBlockState(), 3);
    }

    private static void checkForHeartGain(ServerLevel level, BlockPos voidPos, Vec3 center) {
        AABB searchBox = new AABB(
                center.x - RADIUS, center.y - 1, center.z - RADIUS,
                center.x + RADIUS, center.y + 1, center.z + RADIUS
        );

        // Find a dropped golden apple in radius
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox);
        ItemEntity appleEntity = null;
        for (ItemEntity item : items) {
            ItemStack stack = item.getItem();
            if (stack.is(Items.GOLDEN_APPLE)) {
                appleEntity = item;
                break;
            }
        }
        if (appleEntity == null) return;

        // Find a player standing in the circle
        List<ServerPlayer> players = level.getEntitiesOfClass(ServerPlayer.class, searchBox);
        if (players.isEmpty()) return;

        ServerPlayer player = players.get(0);
        AttributeInstance maxHealthAttribute = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealthAttribute == null) return;

        // Already at (or above) normal max health, nothing to gain
        if (maxHealthAttribute.getBaseValue() >= NORMAL_MAX_HEALTH) return;

        // Consume the golden apple
        ItemStack appleStack = appleEntity.getItem();
        appleStack.shrink(1);
        if (appleStack.isEmpty()) {
            appleEntity.discard();
        }

        // Grant a heart, capped at normal max health
        double newMaxHealth = Math.min(NORMAL_MAX_HEALTH, maxHealthAttribute.getBaseValue() + HEALTH_PER_APPLE);
        maxHealthAttribute.setBaseValue(newMaxHealth);
        player.setHealth((float) Math.min(player.getHealth() + HEALTH_PER_APPLE, newMaxHealth));

        player.sendSystemMessage(
                Component.literal("A heart has been restored!")
                        .withStyle(ChatFormatting.RED)
                        .withStyle(ChatFormatting.BOLD)
        );

        // Consume the circle, same as a revival
        level.setBlock(voidPos, Blocks.AIR.defaultBlockState(), 3);
    }

    private static void checkForOrbitalStrike(ServerLevel level, BlockPos voidPos, Vec3 center) {
        AABB searchBox = new AABB(
                center.x - RADIUS, center.y - 1, center.z - RADIUS,
                center.x + RADIUS, center.y + 1, center.z + RADIUS
        );

        // Find a dropped TNT item in radius
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox);
        ItemEntity tntEntity = null;
        for (ItemEntity item : items) {
            ItemStack stack = item.getItem();
            if (stack.is(Items.TNT)) {
                tntEntity = item;
                break;
            }
        }
        if (tntEntity == null) return;

        // Consume the TNT
        ItemStack tntStack = tntEntity.getItem();
        tntStack.shrink(1);
        if (tntStack.isEmpty()) {
            tntEntity.discard();
        }

        // Consume the circle, same as a revival
        level.setBlock(voidPos, Blocks.AIR.defaultBlockState(), 3);

        launchOrbitalStrike(level, center);
    }

    private static void launchOrbitalStrike(ServerLevel level, Vec3 center) {
        AABB warningBox = new AABB(
                center.x - 64, center.y - 64, center.z - 64,
                center.x + 64, center.y + 64, center.z + 64
        );

        level.playSound(
                null,
                center.x, center.y, center.z,
                net.minecraft.sounds.SoundEvents.RAID_HORN,
                net.minecraft.sounds.SoundSource.HOSTILE,
                4.0F, 0.6F
        );

        int lastDetonationTick = dropNukeShells(level, center);
        ACTIVE_STRIKES.add(new StrikeAnimation(level, center, lastDetonationTick));
    }

    // Drops real primed TNT in concentric rings above the target. Each shell's fuse is derived
    // from a simulated fall time for its own height, so it always lands before it goes off
    // instead of detonating mid-air. Returns the tick of the last shell's detonation, so the
    // mushroom cloud can be timed to start right after.
    private static int dropNukeShells(ServerLevel level, Vec3 center) {
        int lastDetonationTick = spawnNukeTnt(level, center.x, center.y + NUKE_DROP_HEIGHT, center.z, NUKE_DROP_HEIGHT);

        for (int ring = 1; ring <= NUKE_RING_COUNT; ring++) {
            double radius = ring * NUKE_RING_SPACING;
            int tntInRing = Math.max(4, ring * NUKE_TNT_PER_RING_STEP);
            double dropHeight = NUKE_DROP_HEIGHT + ring * NUKE_RING_HEIGHT_STEP;

            for (int i = 0; i < tntInRing; i++) {
                double angle = (2 * Math.PI * i) / tntInRing;
                double x = center.x + radius * Math.cos(angle);
                double z = center.z + radius * Math.sin(angle);
                int detonationTick = spawnNukeTnt(level, x, center.y + dropHeight, z, dropHeight);
                lastDetonationTick = Math.max(lastDetonationTick, detonationTick);
            }
        }

        return lastDetonationTick;
    }

    // Spawns one primed TNT shell and returns the tick (relative to now) at which it will detonate.
    private static int spawnNukeTnt(ServerLevel level, double x, double y, double z, double dropHeight) {
        int fallTicks = simulateFallTicks(dropHeight);
        int jitter = NUKE_FUSE_JITTER_TICKS > 0 ? RANDOM.nextInt(NUKE_FUSE_JITTER_TICKS + 1) : 0;
        int fuse = fallTicks + NUKE_LAND_DELAY_TICKS + jitter;

        net.minecraft.world.entity.item.PrimedTnt tnt =
                new net.minecraft.world.entity.item.PrimedTnt(level, x, y, z, null);
        tnt.setFuse((short) fuse);
        level.addFreshEntity(tnt);

        return fuse;
    }

    // Rough simulation of vanilla-style falling physics (gravity ~0.04 blocks/tick^2, ~0.98 air
    // drag) used purely to time fuses so shells only detonate once they've actually hit the ground.
    private static int simulateFallTicks(double height) {
        double fallen = 0;
        double velocity = 0;
        int ticks = 0;
        while (fallen < height && ticks < 400) {
            velocity = (velocity + 0.04) * 0.98;
            fallen += velocity;
            ticks++;
        }
        return ticks;
    }

    private static void tickOrbitalStrikes() {
        for (StrikeAnimation strike : ACTIVE_STRIKES) {
            if (strike.tick >= strike.cloudStartTick) {
                spawnMushroomCloudFrame(strike, strike.tick - strike.cloudStartTick);
            }

            strike.tick++;

            if (strike.tick > strike.cloudStartTick + STRIKE_CLOUD_TICKS) {
                ACTIVE_STRIKES.remove(strike);
            }
        }
    }

    private static void spawnMushroomCloudFrame(StrikeAnimation strike, int cloudFrame) {
        ServerLevel level = strike.level;
        Vec3 origin = strike.origin;
        double cloudProgress = Math.min(1.0, cloudFrame / (double) STRIKE_CLOUD_TICKS);

        // Stem: real explosion pulses climbing upward, trailing smoke in between
        double stemHeight = CLOUD_MAX_STEM_HEIGHT * Math.min(1.0, cloudProgress * 2.2);
        for (double y = 0.5; y < stemHeight; y += 1.5) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE,
                    origin.x, origin.y + y, origin.z, 2, 0.6, 0, 0.6, 0.02);
        }

        // Expanding mushroom cap
        double capRadius = CLOUD_MAX_CAP_RADIUS * Math.min(1.0, cloudProgress * 1.8);
        double capY = origin.y + stemHeight;

        boolean pulseTick = cloudFrame % CLOUD_EXPLOSION_INTERVAL_TICKS == 0;

        if (pulseTick) {
            // A ring of real explosions (no block/entity damage) gives the cap an actual
            // fireball flash and boom instead of just particles.
            for (int i = 0; i < CLOUD_EXPLOSIONS_PER_PULSE; i++) {
                double angle = (2 * Math.PI * i) / CLOUD_EXPLOSIONS_PER_PULSE
                        + (RANDOM.nextDouble() * 0.2 - 0.1);
                double r = capRadius * (0.6 + RANDOM.nextDouble() * 0.4);
                double x = origin.x + r * Math.cos(angle);
                double z = origin.z + r * Math.sin(angle);
                double y = capY + (RANDOM.nextDouble() * 3.0 - 1.0);

                level.explode(
                        null,
                        x, y, z,
                        CLOUD_EXPLOSION_POWER,
                        Level.ExplosionInteraction.NONE
                );
            }
        }

        // Smoke fill between pulses so the cap reads as a solid cloud, not just flashes
        int ringPoints = 40;
        for (int i = 0; i < ringPoints; i++) {
            double angle = (2 * Math.PI * i) / ringPoints;
            double x = origin.x + capRadius * Math.cos(angle);
            double z = origin.z + capRadius * Math.sin(angle);

            level.sendParticles(ParticleTypes.LARGE_SMOKE,
                    x, capY + 0.5, z, 1, 0.6, 0.4, 0.6, 0.02);
            level.sendParticles(ParticleTypes.LARGE_SMOKE,
                    x, capY - 2.0, z, 1, 0.6, 0.2, 0.6, 0.02);
        }
    }

    private static void revivePlayer(ServerLevel level, Mannequin corpse, GameProfile playerId) {
        MinecraftServer server = level.getServer();
        PlayerList playerList = server.getPlayerList();


        Vec3 corpsePos = corpse.position();

        corpse.discard();

        // Step 4: If the player is currently online (e.g. spectating after hardcore death),
        // set them back to survival and teleport/heal them.
        ServerPlayer onlinePlayer = playerList.getPlayer(playerId.id());
        if (onlinePlayer != null) {
            onlinePlayer.setGameMode(GameType.SURVIVAL);
            onlinePlayer.setHealth(onlinePlayer.getMaxHealth());
            onlinePlayer.teleportTo(level, corpsePos.x, corpsePos.y, corpsePos.z,
                    java.util.Set.of(), onlinePlayer.getYRot(), onlinePlayer.getXRot(), false);
            // Drop the spectator leash now that the player is alive again. Strictly speaking
            // SpectatorLimit.tick would also clean this up on the next tick (because the
            // player is no longer in spectator mode), but clearing it here means there's no
            // window where a just-revived player could be yanked back to the corpse.
            SpectatorLimit.clearDeath(playerId.id());
        }
        server.sendSystemMessage(
                Component.literal(onlinePlayer.getName() + " Has been Revived")
                        .withStyle(ChatFormatting.RED)
                        .withStyle(ChatFormatting.BOLD)
        );
    }

    public static void spawnRedParticleCircle(ServerLevel level, Vec3 center) {
        int particleCount = 32;
        double radius = 1.0;

        for (int i = 0; i < particleCount; i++) {
            double angle = (2 * Math.PI * i) / particleCount;
            double x = center.x + radius * Math.cos(angle);
            double z = center.z + radius * Math.sin(angle);

            level.sendParticles(
                    RED_DUST,
                    x, center.y + 0.1, z,
                    1,
                    0, 0, 0,
                    0
            );
        }
    }

    public static int colorToInt(float r, float g, float b) {
        int red = (int)(r * 255.0f) & 0xFF;
        int green = (int)(g * 255.0f) & 0xFF;
        int blue = (int)(b * 255.0f) & 0xFF;
        return (red << 16) | (green << 8) | blue;
    }
}