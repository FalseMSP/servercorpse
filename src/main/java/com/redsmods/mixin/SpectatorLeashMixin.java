package com.redsmods.mixin;

import com.redsmods.SpectatorLimit;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks {@link ServerGamePacketListenerImpl#handleMovePlayer} to enforce the hardcore
 * spectator leash.
 *
 * <p>This is the "movement event" the rest of the mod talks about. Rather than scanning
 * every tracked death every server tick (the old approach, which did map iteration +
 * per-player work every tick even when nobody was moving), we hook the network handler
 * that processes the client's movement packets, so the leash check only fires when a
 * player actually moves.</p>
 *
 * <h2>Why hook the packet listener and not {@link net.minecraft.world.entity.Entity#move}?</h2>
 * It's tempting to hook {@code Entity#move(MoverType, Vec3)} since that's the generic
 * "an entity's position changed" method. But that method is the collision-resolution path
 * used for AI- and physics-driven movement (mobs, knockback, pistons, etc.) - a real
 * player's own position is never updated through it. When the client sends a movement
 * packet, {@link ServerGamePacketListenerImpl#handleMovePlayer} validates it and sets the
 * player's position directly. This is doubly true for spectators: there's no collision to
 * resolve for a noclipping player, so vanilla just trusts the client and places them there,
 * without ever touching {@code move()}. An earlier version of this mixin hooked
 * {@code Entity#move} and, as a result, never fired for real spectators at all - the leash
 * looked correct in code review but did nothing in game. Hooking the packet handler instead
 * catches every real movement update regardless of which internal branch vanilla takes.</p>
 *
 * <h2>Why TAIL?</h2>
 * We inject at the end of {@code handleMovePlayer} so the player's position has already
 * been updated by the time we check the leash. That way {@link SpectatorLimit#enforceLeash}
 * reads the post-move position and, if it's outside the leash, clamps it back. Injecting at
 * HEAD would mean we'd see the pre-move position, which is by definition inside the leash
 * (otherwise we'd already have clamped it on the previous move).</p>
 *
 * <h2>Why not skip vertical-only packets?</h2>
 * An earlier version tried to cheaply filter out purely-vertical moves before calling
 * {@link SpectatorLimit#enforceLeash}, since the leash only constrains horizontal distance.
 * That optimization doesn't have a clean equivalent here (the packet carries an absolute
 * position, not a delta), and {@link SpectatorLimit#enforceLeash} already short-circuits on
 * a HashMap lookup for any player who isn't tracked, so the extra check isn't worth the
 * complexity - movement packets are event-driven already, not a per-tick scan.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class SpectatorLeashMixin {

    /** The player this packet listener belongs to. Shadowed so we can talk to them. */
    @Shadow
    public ServerPlayer player;

    @Inject(
            method = "handleMovePlayer",
            at = @At("TAIL")
    )
    private void servercorpse$enforceLeashOnMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        SpectatorLimit.enforceLeash(this.player);
    }
}