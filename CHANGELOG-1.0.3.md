# Yu Multiverse Inventory 1.0.3

## Critical fixes

### Login live-state ownership / cross-group contamination

- Removed the 1.0.2 `recoverLive` behavior that overwrote an existing canonical target profile after any unclean shutdown.
- Existing canonical profiles now remain authoritative during login, independent of the global shutdown flag.
- Unknown vanilla login state may bootstrap a share only if that player has no MWI profile for the share in any scope.
- Missing targets with existing share history reset/migrate instead of copying unknown/foreign live state.
- Hybrid pre-login dimension transitions canonicalize the event source scope before reconciliation, even when no prepared-revision recovery marker exists.
- Login prepared journals now persist `canonical_before` and `target_existed`, so crash recovery restores the actual canonical before-image instead of adopting vanilla login state.
- World-profile backups are generated only after the selected canonical/reset state is applied, preventing the bad login snapshot from being duplicated into `world:*` backups.

### Payload writer/reader contract

- `FoodAdapter` uses one validation contract for both capture and apply.
- Modded finite hunger values such as saturation `20.600000381469727` are accepted; NaN/Infinity, negatives and corruption-scale values remain rejected.
- `HealthAdapter` now accepts `health=0`, a valid transient death state that 1.0.2 could capture but not restore.
- Experience payload capture/apply validation is symmetric and permits bounded modded progress above vanilla's normal fractional range.
- Statistics now restore every non-zero count that capture can persist, including negative modded/plugin values.
- Statistics whose type or value registry entry disappeared with a removed mod are skipped with an explicit warning instead of aborting player login; malformed identifiers and payloads remain fatal.

## Compatibility

### Missing item/component registry drift

- Missing item IDs and removed data-component types are isolated per stack instead of aborting the whole profile transaction.
- Original stack NBT is preserved in an atomic compatibility archive before the sanitized canonical profile is installed.
- Vanilla inventories, Curios equipment/cosmetics, Cosmetic Armor and TrashSlot are covered without weakening unknown-slot or structural validation.
- Explicit item aliases, offline dry-run/apply repair, archive list/restore and compatibility metrics are available to operators.

- Profile file schema is unchanged.
- Existing 1.0.2 checksums remain valid; no bulk NBT rewrite is required for the hunger fix.
- Legacy prepared revision journals remain readable through the old recovery fallback.
