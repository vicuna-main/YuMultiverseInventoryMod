# Yu Multiverse Inventory 1.0.5

## Unloaded respawn dimension recovery

- Restores a syntactically valid `bed_spawn` dimension key even when the target `ServerLevel` is
  currently unloaded or no longer exists; inventory initialization no longer fails or quarantines
  the player for that lifecycle state.
- Validates the complete respawn payload before changing live state, keeps malformed NBT and
  non-finite angles fatal, and preserves full transaction rollback behavior.
- Never calls a world-loading API while restoring or inspecting a respawn location.
- Adds offline `bed-spawn --dry-run` and explicit scope/all `--clear` commands. Clear archives the
  original canonical profile and commits the cleared payload, revision journal, payload checksum,
  and envelope checksum through the existing atomic transaction path before invalidating all player
  profile caches.
- Resolves offline names only through UUID entries already known to MWI and the local profile cache;
  UUID input remains available for every offline player.

## Upgrade note

After deploying 1.0.5, restore private worlds to their intended on-demand lifecycle and verify login
while the saved respawn dimension is unloaded. Do not clear a valid respawn profile merely because
its target world reports `loaded=false`.
