package com.redsmods.mixin;

import com.redsmods.SpectatorLimit;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks the spectator-menu teleport for dead hardcore spectators.
 *
 * <p>Vanilla spectators can press a number key (or middle-click) to open the spectator
 * menu and instantly teleport to any other player on the server. That completely defeats
 * the point of the leash in {@link SpectatorLimit} - a dead player could just teleport to
 * a friend on the other side of the map and scout from there. This mixin intercepts the
 * packet that drives that teleport ({@link ServerboundTeleportToEntityPacket}) and
 * cancels it for any player who is currently leashed to their corpse.</p>
 *
 * <h2>What this does <em>not</em> block</h2>
 * <ul>
 *   <li><b>Admin {@code /tp} commands.</b> Those go through
 *       {@code ServerPlayer#teleportTo} directly on the server, never through this packet.
 *       Admins can still move spectators around if they want to.</li>
 *   <li><b>The revival teleport in {@link com.redsmods.SummoningCircle}.</b> That also
 *       calls {@code teleportTo} directly, and it explicitly clears the leash via
 *       {@link SpectatorLimit#clearDeath} before the teleport, so even if it did route
 *       through here it would already be unblocked.</li>
 *   <li><b>Dimension changes (portals, etc.).</b> Spectators can't use portals in vanilla,
 *       and any dimension change is caught and yanked back by
 *       {@link SpectatorLimit#enforceLeash} on the next move.</li>
 * </ul>
 *
 * <h2>Why HEAD + cancellable?</h2>
 * We inject at the very start of the packet handler and cancel the callback before any
 * of vanilla's logic runs. That means the teleport never happens - the player stays where
 * they were, and we send them a chat message explaining why. Injecting at TAIL would be
 * useless because by then the teleport has already happened.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class SpectatorTeleportMixin {

    /** The player this packet listener belongs to. Shadowed so we can talk to them. */
    @Shadow
    public ServerPlayer player;

    @Inject(
            method = "handleTeleportToEntityPacket",
            at = @At("HEAD"),
            cancellable = true
    )
    private void servercorpse$blockSpectatorMenuTeleport(
            ServerboundTeleportToEntityPacket packet, CallbackInfo ci) {
        if (SpectatorLimit.isLeashed(this.player)) {
            this.player.sendSystemMessage(
                    Component.literal("You cannot teleport away from your corpse.")
                            .withStyle(ChatFormatting.RED)
                            .withStyle(ChatFormatting.BOLD)
            );
            ci.cancel();
        }
    }
}
