package com.redsmods;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.server.level.ServerPlayer;

/**
 * Isolates the (optional) Carpet dependency in its own class.
 *
 * Java resolves the classes referenced by a class's bytecode (e.g. the target of an
 * `instanceof`) the first time that class is loaded - not just when it's actually invoked.
 * If the `instanceof EntityPlayerMPFake` check lived directly in ServerCorpse, then simply
 * loading ServerCorpse (which happens unconditionally at mod init) would try to resolve
 * `carpet.patches.EntityPlayerMPFake`, and throw NoClassDefFoundError on any server that
 * doesn't have Carpet installed - even if the check itself is never reached.
 *
 * By putting the check in its own class, and only ever calling into this class after
 * confirming Carpet is loaded (see CarpetCompat.isAvailable() usage in ServerCorpse),
 * this class - and therefore the reference to EntityPlayerMPFake - is never loaded at all
 * on servers without Carpet.
 */
final class CarpetCompat {
    private CarpetCompat() {
    }

    static boolean isFakePlayer(ServerPlayer player) {
        return player instanceof EntityPlayerMPFake;
    }
}