# ServerCorpse

A Fabric mod that spawns a Mannequin corpse where players die, complete with their
skin, cape and equipped gear. Other players can revive a corpse by dropping a
Totem of Undying (or any item tagged `servercorpse:revive`) on a structure-void
"summoning circle" next to the body.

## Features

- **Corpse on death** — A `Mannequin` is spawned at the player's death location
  wearing their armour and holding their items. The corpse is invulnerable but
  can still be pushed around by pistons / gravity.
- **Summoning circle revival** — Drop a Totem of Undying onto a structure-void
  block next to a corpse to bring the dead player back to life at the corpse's
  position.
- **Heart restoration** — Drop a Golden Apple on a summoning circle while
  standing on it to regain one heart (capped at vanilla max).
- **Orbital strike** — Drop a TNT item on a summoning circle to call in a
  ring-of-TNT bombardment from above, followed by a particle-driven mushroom
  cloud.
- **Hardcore spectator leash** *(new)* — In hardcore worlds, dead players are
  locked to spectator mode by vanilla. This mod additionally leashes them to
  their corpse: they cannot fly more than 2 chunks (32 blocks) away from where
  they died. See below for details.

## Hardcore spectator leash

When a player dies in a hardcore world, vanilla Minecraft permanently forces
them into spectator mode. Without intervention that gives the dead player a
free-roaming ghost scout that can fly anywhere on the map — which spoils the
stakes of hardcore for the rest of the team.

To fix this, `SpectatorLimit` tracks each player's death position and clamps
any tracked spectator back to within **2 chunks (32 blocks)** of their corpse
(measured horizontally) whenever they move. The spectator-menu teleport
(press a number key to jump to another player) is also blocked for leashed
spectators, so they can't bypass the leash with a menu click. The leash is
automatically released when the player is revived through the summoning
circle ritual.

### Tuning

The leash constants live at the top of `SpectatorLimit.java`:

| Constant                  | Default | Meaning                                                        |
|---------------------------|---------|----------------------------------------------------------------|
| `MAX_CHUNKS`              | `2`     | Maximum horizontal distance, in chunks, from the corpse.       |
| `CHUNK_SIZE`              | `16`    | Vanilla chunk width, in blocks. Used to derive the block cap.  |
| `MAX_DISTANCE_BLOCKS`     | `32`    | `MAX_CHUNKS * CHUNK_SIZE`. The hard cap in blocks.             |
| `SOFT_PADDING`            | `1.0`   | How far inside the cap the player is clamped to. Prevents      |
|                           |         | per-tick rubber-banding and message spam.                      |
| `MESSAGE_COOLDOWN_TICKS`  | `100`   | Min ticks between "you've gone too far" chat messages (5 sec). |

Change `MAX_CHUNKS` to leash players to a different radius.

### How it works

The leash is **movement-driven, not tick-driven**. There is no per-tick scan
of all dead players; instead two Mixins hook the exact moments a spectator
could escape the leash:

- **`SpectatorLeashMixin`** hooks `Entity#move(MoverType, Vec3)` at `TAIL`.
  This is the vanilla method that applies positional movement to any entity.
  The mixin filters to `ServerPlayer` (one `instanceof` check, which is
  essentially free for non-players) and skips purely-vertical moves (the
  leash only constrains horizontal distance), then calls
  `SpectatorLimit.enforceLeash(player)`. That method does a cheap HashMap
  lookup; if the player isn't tracked it returns immediately, so the cost for
  non-tracked players is one HashMap miss per move.
- **`SpectatorTeleportMixin`** hooks `ServerGamePacketListenerImpl#handleTeleportToEntityPacket`
  at `HEAD` and cancels it for any leashed spectator. This is the packet that
  fires when a spectator uses the spectator menu (press a number key /
  middle-click) to teleport to another player. Cancelling it here means the
  teleport never happens; the player gets a red chat message instead.

The full per-move flow inside `SpectatorLimit.enforceLeash`:

1. If the player isn't tracked, return immediately (cheap HashMap lookup).
2. If the player is no longer in spectator mode (revived, or admin changed
   their game mode), clear the tracking entry and return. This also means
   the leash never affects non-hardcore worlds — in those, the dead player
   respawns as survival and the entry is cleaned up on their next move.
3. If the player is in a different dimension than where they died, teleport
   them straight back to the death point (safety net for commands / other
   mods that might move spectators across dimensions).
4. Otherwise, if the player is more than `MAX_DISTANCE_BLOCKS` blocks away
   horizontally, clamp them to the boundary of the allowed circle and send
   a red warning message (rate-limited per player).

`SummoningCircle.revivePlayer` explicitly clears the leash for a revived
player so there's no window where a just-revived player could be yanked
back to the corpse.

### What the teleport block does *not* block

- **Admin `/tp` commands** — those go through `ServerPlayer#teleportTo`
  directly on the server, never through the spectator-teleport packet.
  Admins can still move spectators around if they want to.
- **The revival teleport** in `SummoningCircle` — also calls `teleportTo`
  directly, and explicitly clears the leash before the teleport, so even if
  it routed through the packet handler it would already be unblocked.
- **Dimension changes (portals, etc.)** — spectators can't use portals in
  vanilla, and any cross-dimension move is caught and yanked back by
  `enforceLeash` on the next move.

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up)
related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it
and incorporate it in your own projects.
