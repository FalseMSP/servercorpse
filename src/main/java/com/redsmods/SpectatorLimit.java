package com.redsmods;

import com.redsmods.mixin.SpectatorLeashMixin;
import com.redsmods.mixin.SpectatorTeleportMixin;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Prevents hardcore-mode spectators from wandering away from their corpse.
 *
 * <p>In hardcore, vanilla locks a dead player into {@link GameType#SPECTATOR} for the rest of
 * the world's life. Without intervention that means a dead player can fly anywhere on the map,
 * which spoils the stakes of hardcore (the rest of the team has to actually survive without
 * a free-roaming ghost scout). This class keeps the dead player anchored to the spot they
 * died at: whenever a tracked spectator moves, {@link SpectatorLeashMixin} calls
 * {@link #enforceLeash(ServerPlayer)}, which clamps them back to within
 * {@link #MAX_DISTANCE_BLOCKS} blocks (horizontally) of their death point if they've strayed
 * too far. {@link SpectatorTeleportMixin} additionally blocks the spectator-menu teleport
 * packet, so a leashed spectator can't bypass the leash with the "press a number key to
 * jump to a player" feature.</p>
 *
 * <h2>Why we hook movement, not the server tick</h2>
 * The original implementation registered a {@code ServerTickEvents.END_SERVER_TICK} handler
 * that iterated over every tracked death every tick. That works but does map iteration +
 * per-player work every tick even when nobody is moving. The current implementation is
 * driven by {@link SpectatorLeashMixin} hooking {@code Entity#move(MoverType, Vec3)}, so
 * the leash check only runs when an entity actually moves. The mixin filters to
 * {@link ServerPlayer} (one {@code instanceof} check, which is essentially free) and
 * {@link #enforceLeash} itself short-circuits with a HashMap lookup if the player isn't
 * tracked. Net result: zero per-tick overhead for non-tracked players and no global map
 * iteration.</p>
 *
 * <h2>Why we track the death point rather than the Mannequin corpse</h2>
 * The corpse entity created by {@link ServerCorpse#spawnCorpse} is intentionally allowed to
 * be shoved around by pistons and gravity (see the comments in that method), and it is also
 * discarded the moment the player is revived. Tracking the death point instead means:</p>
 * <ul>
 *   <li>the leash anchor is stable even if the corpse is pushed into a wall or off a cliff,</li>
 *   <li>the leash keeps holding if the corpse is destroyed by something other than the
 *       revival ritual (e.g. the orbital strike in {@link SummoningCircle}), and</li>
 *   <li>we don't need to keep a hard reference to an entity that might be unloaded.</li>
 * </ul>
 *
 * <h2>Why we don't explicitly check {@code isHardcore()}</h2>
 * Tracking is added on every player death, but {@link #enforceLeash} immediately clears the
 * entry for any player who is not currently in spectator mode. In a non-hardcore world a
 * dead player respawns as survival on their very next move, so the entry is cleaned up
 * before it ever has a chance to act. In a hardcore world the player is forced into
 * spectator by vanilla and stays there, so the entry sticks. This avoids depending on the
 * (version-sensitive) {@code LevelData#isHardcore()} API while still only ever affecting
 * hardcore-style deaths.
 */
public final class SpectatorLimit {
    private SpectatorLimit() {
    }

    /** Maximum horizontal distance, in chunks, a hardcore spectator may roam from their corpse. */
    public static final int MAX_CHUNKS = 2;

    /** Vanilla chunk width, in blocks. Used to convert the chunk budget into a block radius. */
    public static final int CHUNK_SIZE = 16;

    /** Maximum horizontal distance, in blocks, computed from {@link #MAX_CHUNKS}. */
    public static final double MAX_DISTANCE_BLOCKS = MAX_CHUNKS * CHUNK_SIZE;

    /**
     * How far inside the hard cap we clamp the player back to. The cap is 32 blocks; we put
     * them at 31 instead. Without this buffer a player holding forward into the boundary
     * would re-trigger the clamp on (almost) every move call, which both feels bad and would
     * re-fire the warning message constantly. One block of slack is roughly two ticks of
     * spectator movement, so the player has to actually let go and push back in to trigger
     * another clamp.
     */
    public static final double SOFT_PADDING = 1.0;

    /**
     * Minimum number of ticks between warning messages sent to the same player. Without this
     * a player holding forward into the boundary would see the chat spam every time the
     * clamp fires. 100 ticks = 5 seconds, which is enough to be noticeable without being
     * annoying.
     */
    public static final long MESSAGE_COOLDOWN_TICKS = 100L;

    /**
     * Backing storage for the death-position map. This used to be a plain static
     * {@code HashMap}, which meant every leash was forgotten on server restart. It's now a
     * {@link SpectatorLeashData} - a {@code SavedData} that IS the live map (not a copy of
     * it), so every mutation here is automatically picked up next time the world saves.
     * Populated by {@link #init(MinecraftServer)}, which must be called once on server start
     * (e.g. from a {@code ServerLifecycleEvents.SERVER_STARTED} listener) before any of the
     * methods below are used.
     */
    private static SpectatorLeashData storage;

    /** Per-player cooldown tracker for the "you've gone too far" chat message. Not persisted -
     * losing a few seconds of cooldown state across a restart doesn't matter. */
    private static final Map<UUID, Long> LAST_WARNING_TICK = new HashMap<>();

    /**
     * Hooks this class up to persistent storage for the given server. Must be called once,
     * on server start, before any death is tracked - e.g.
     * {@code ServerLifecycleEvents.SERVER_STARTED.register(SpectatorLimit::init);}
     */
    public static void init(MinecraftServer server) {
        storage = SpectatorLeashData.get(server);
    }

    /** The live UUID -> death location map, backed by {@link #storage}. */
    private static Map<UUID, DeathLocation> deaths() {
        if (storage == null) {
            throw new IllegalStateException(
                    "SpectatorLimit.init(server) was never called - persistent leash storage is not set up");
        }
        return storage.deaths();
    }

    /**
     * Lightweight record of where a player died. We need both the position (for the leash
     * radius) and the dimension (so we can yank them back if they somehow end up in another
     * dimension - spectators normally can't change dimensions on their own, but commands
     * or other mods could).
     */
    public record DeathLocation(Vec3 position, ResourceKey<Level> dimension) {
    }

    /**
     * Called from  right after the corpse entity is spawned.
     * Records the player's death position so the move handler can start leashing them once
     * they enter spectator mode.
     */
    public static void trackDeath(ServerPlayer player) {
        deaths().put(
                player.getUUID(),
                new DeathLocation(player.position(), player.level().dimension())
        );
        storage.setDirty();
    }

    /** Removes the leash for a player. Called from . */
    public static void clearDeath(UUID playerId) {
        deaths().remove(playerId);
        LAST_WARNING_TICK.remove(playerId);
        storage.setDirty();
    }

    /** Convenience overload for {@link #clearDeath(UUID)}. */
    public static void clearDeath(ServerPlayer player) {
        clearDeath(player.getUUID());
    }

    /**
     * Returns true if the player is currently leashed to their corpse. Used by
     * {@link SpectatorTeleportMixin} to decide whether to block the spectator-menu
     * teleport packet.
     */
    public static boolean isLeashed(ServerPlayer player) {
        return deaths().containsKey(player.getUUID());
    }

    /**
     * Called from {@link SpectatorLeashMixin} every time a tracked player moves. Does the
     * actual leash enforcement:
     * <ol>
     *   <li>If they're not tracked, return immediately (cheap HashMap lookup).</li>
     *   <li>If they're no longer in spectator mode, stop tracking (they were revived or had
     *       their game mode changed by an admin). This also means the leash never affects
     *       non-hardcore worlds - in those, the dead player respawns as survival on the
     *       next move and the entry is cleaned up immediately.</li>
     *   <li>If they're in a different dimension than where they died, teleport them back to
     *       the death point. Spectators can't normally change dimensions, so this is mostly
     *       a safety net for commands / other mods.</li>
     *   <li>Otherwise, if they're more than {@link #MAX_DISTANCE_BLOCKS} blocks away
     *       horizontally, clamp them back to the boundary of the allowed circle.</li>
     * </ol>
     */
    public static void enforceLeash(ServerPlayer player) {
        DeathLocation death = deaths().get(player.getUUID());
        if (death == null) return; // not tracked - cheap short-circuit

        // No longer a spectator -> they were revived (SummoningCircle) or had their game
        // mode changed by an admin. Stop tracking them so we don't accidentally lock a
        // legitimate survival/adventure player.
        if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            clearDeath(player.getUUID());
            return;
        }

        MinecraftServer server = player.level().getServer();
        long currentTick = server != null ? server.getTickCount() : 0L;

        // Different dimension - yank them straight back to the death point. We don't do
        // a boundary clamp here because cross-dimension distances are meaningless.
        if (!player.level().dimension().equals(death.dimension())) {
            ServerLevel deathLevel = server != null ? server.getLevel(death.dimension()) : null;
            if (deathLevel != null) {
                player.teleportTo(
                        deathLevel,
                        death.position().x, death.position().y, death.position().z,
                        java.util.Set.of(),
                        player.getYRot(), player.getXRot(),
                        false
                );
                sendWarning(player, currentTick);
            }
            return;
        }

        // Only restrict on the horizontal plane. Letting hardcore spectators fly up/down
        // freely avoids trapping them under bedrock or above the world if their corpse
        // ended up at an awkward Y.
        double dx = player.getX() - death.position().x;
        double dz = player.getZ() - death.position().z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist <= MAX_DISTANCE_BLOCKS) return;

        // Clamp the player back onto the boundary of the allowed circle, in the direction
        // they were heading. We use the player's current Y so we don't fight them on
        // vertical movement.
        double clampedRadius = MAX_DISTANCE_BLOCKS - SOFT_PADDING;
        double scale = clampedRadius / horizontalDist;
        double clampedX = death.position().x + dx * scale;
        double clampedZ = death.position().z + dz * scale;

        player.teleportTo(
                player.level(),
                clampedX, player.getY(), clampedZ,
                java.util.Set.of(),
                player.getYRot(), player.getXRot(),
                false
        );

        sendWarning(player, currentTick);
    }

    /**
     * Sends the "you've gone too far" message to the player, subject to the per-player
     * cooldown. The cooldown prevents chat spam when a player holds forward into the
     * boundary.
     */
    private static void sendWarning(ServerPlayer player, long currentTick) {
        UUID id = player.getUUID();
        Long last = LAST_WARNING_TICK.get(id);
        if (last != null && currentTick - last < MESSAGE_COOLDOWN_TICKS) return;

        player.sendSystemMessage(
                Component.literal("You cannot wander more than " + MAX_CHUNKS
                                + " chunks from your corpse.")
                        .withStyle(ChatFormatting.RED)
                        .withStyle(ChatFormatting.BOLD)
        );
        LAST_WARNING_TICK.put(id, currentTick);
    }
}