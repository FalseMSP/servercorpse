package com.redsmods;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persists {@link SpectatorLimit}'s UUID -> death-location map across server restarts.
 *
 * <p>This class deliberately holds the live map, not a copy of it - {@link SpectatorLimit}
 * reads and writes directly into {@link #deaths}, and calls {@link #setDirty()} after every
 * mutation. That way there's exactly one map in memory, and it's automatically written to
 * {@code <world>/data/redsmods/spectator_leash.dat} whenever the level saves.</p>
 *
 * <h2>Why a flat list on disk instead of a map</h2>
 * NBT/codecs don't have a native "map keyed by UUID" representation, so we serialize as a
 * list of {@link Entry} records (player, dimension, x, y, z) and convert to/from the live
 * {@code Map<UUID, DeathLocation>} in the codec's {@code xmap}.
 */
public final class SpectatorLeashData extends SavedData {

    private record Entry(UUID playerId, ResourceKey<Level> dimension, double x, double y, double z) {
    }

    private static final Codec<Entry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("player").forGetter(Entry::playerId),
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Entry::dimension),
            Codec.DOUBLE.fieldOf("x").forGetter(Entry::x),
            Codec.DOUBLE.fieldOf("y").forGetter(Entry::y),
            Codec.DOUBLE.fieldOf("z").forGetter(Entry::z)
    ).apply(instance, Entry::new));

    private static final Codec<Map<UUID, SpectatorLimit.DeathLocation>> MAP_CODEC =
            ENTRY_CODEC.listOf().xmap(
                    entries -> entries.stream().collect(Collectors.toMap(
                            Entry::playerId,
                            entry -> new SpectatorLimit.DeathLocation(
                                    new Vec3(entry.x(), entry.y(), entry.z()), entry.dimension())
                    )),
                    map -> map.entrySet().stream()
                            .map(e -> new Entry(
                                    e.getKey(),
                                    e.getValue().dimension(),
                                    e.getValue().position().x,
                                    e.getValue().position().y,
                                    e.getValue().position().z))
                            .collect(Collectors.toList())
            );

    /**
     * The file is {@code <world>/data/redsmods/spectator_leash.dat}. Note that unlike some
     * older/in-between versions of this API, {@code SavedDataType} here takes a plain
     * {@code Supplier<T>} (no context) for the "nothing saved yet" case and a plain
     * {@code Codec<T>} (not a {@code Function<Context, Codec<T>>>} factory) - we don't need
     * per-level context for this data, so both are straightforward.
     */
    public static final SavedDataType<SpectatorLeashData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("redsmods", "spectator_leash"),
            () -> new SpectatorLeashData(new HashMap<>()),
            MAP_CODEC.xmap(SpectatorLeashData::new, SpectatorLeashData::deaths),
            null
    );

    private final Map<UUID, SpectatorLimit.DeathLocation> deaths;

    private SpectatorLeashData(Map<UUID, SpectatorLimit.DeathLocation> deaths) {
        this.deaths = deaths;
    }

    /**
     * Gets (or creates, on a fresh world) the leash data for this server. Attached to the
     * overworld since the leash isn't dimension-specific - a player who died in the Nether
     * is still tracked the same way.
     */
    public static SpectatorLeashData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** The live map. {@link SpectatorLimit} mutates this directly and calls {@link #setDirty()}. */
    Map<UUID, SpectatorLimit.DeathLocation> deaths() {
        return deaths;
    }
}