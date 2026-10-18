package dev.yu.mwi.service;

import dev.yu.mwi.domain.ProfileKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

final class TransitionJournal {
    private TransitionJournal() {
    }

    static CompoundTag prepared(String reason, String fromWorld, String toWorld, List<Change> changes) {
        CompoundTag journal = new CompoundTag();
        journal.putString("status", "prepared");
        journal.putString("reason", reason);
        journal.putString("from_world", fromWorld);
        journal.putString("to_world", toWorld);
        ListTag list = new ListTag();
        for (Change change : changes) {
            CompoundTag item = new CompoundTag();
            item.putString("share_id", change.shareId());
            if (change.fromKey() != null) {
                item.put("from_key", encodeKey(change.fromKey()));
            }
            item.put("to_key", encodeKey(change.toKey()));
            item.put("before", change.before());
            list.add(item);
        }
        journal.put("changes", list);
        return journal;
    }

    /**
     * Prepares a login journal without claiming that the vanilla login snapshot is the canonical
     * target's durable before-image. Existing target payloads are stored separately so startup
     * recovery can restore the real canonical value; an absent target is marked explicitly.
     */
    static CompoundTag preparedLogin(
            String world,
            List<Change> changes,
            java.util.Map<ProfileKey, CompoundTag> canonicalBefore) {
        CompoundTag journal = prepared("login", world, world, changes);
        ListTag list = journal.getList("changes", Tag.TAG_COMPOUND);
        for (int i = 0; i < changes.size() && i < list.size(); i++) {
            Change change = changes.get(i);
            CompoundTag item = list.getCompound(i);
            CompoundTag canonical = canonicalBefore.get(change.toKey());
            item.putBoolean("target_existed", canonical != null);
            if (canonical != null) {
                item.put("canonical_before", canonical.copy());
            }
        }
        return journal;
    }

    static void complete(CompoundTag journal, String status, List<Change> changes, String error) {
        journal.putString("status", status);
        if (error != null && !error.isBlank()) {
            journal.putString("error", error);
        }
        ListTag list = journal.getList("changes", Tag.TAG_COMPOUND);
        for (int i = 0; i < changes.size() && i < list.size(); i++) {
            CompoundTag item = list.getCompound(i);
            CompoundTag after = changes.get(i).after();
            if (after != null) {
                item.put("after", after);
            }
        }
    }

    static List<RecoveredChange> recoverBefore(CompoundTag journal) {
        if (!journal.contains("changes", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Revision contains no changes");
        }
        List<RecoveredChange> result = new ArrayList<>();
        ListTag changes = journal.getList("changes", Tag.TAG_COMPOUND);
        for (int i = 0; i < changes.size(); i++) {
            CompoundTag item = changes.getCompound(i);
            if (!item.contains("before", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Revision change " + i + " has no before snapshot");
            }
            ProfileKey toKey = decodeKey(item.getCompound("to_key"));
            ProfileKey fromKey = item.contains("from_key", Tag.TAG_COMPOUND)
                    ? decodeKey(item.getCompound("from_key"))
                    : null;
            result.add(new RecoveredChange(
                    item.getString("share_id"), fromKey, toKey, item.getCompound("before").copy()));
        }
        return List.copyOf(result);
    }

    static CompoundTag encodeKey(ProfileKey key) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("player_uuid", key.playerId());
        tag.putString("scope_type", key.scope().type().name());
        tag.putString("scope_id", key.scope().id());
        tag.putString("game_mode", key.gameMode().name());
        tag.putString("share_id", key.shareId());
        return tag;
    }

    static ProfileKey decodeKey(CompoundTag tag) {
        if (!tag.hasUUID("player_uuid")) {
            throw new IllegalArgumentException("Revision profile key has no player UUID");
        }
        try {
            return new ProfileKey(
                    tag.getUUID("player_uuid"),
                    new dev.yu.mwi.domain.ScopeKey(
                            dev.yu.mwi.domain.ScopeKey.Type.valueOf(tag.getString("scope_type")),
                            tag.getString("scope_id")),
                    dev.yu.mwi.domain.GameModeBucket.valueOf(tag.getString("game_mode")),
                    tag.getString("share_id"));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Revision contains an invalid profile key", invalid);
        }
    }

    record Change(
            String shareId,
            ProfileKey fromKey,
            ProfileKey toKey,
            CompoundTag before,
            CompoundTag after
    ) {
        Change withAfter(CompoundTag newAfter) {
            return new Change(shareId, fromKey, toKey, before, newAfter);
        }
    }

    record RecoveredChange(String shareId, ProfileKey fromKey, ProfileKey toKey, CompoundTag payload) {
        ProfileKey recoveryKey() {
            return fromKey == null ? toKey : fromKey;
        }
    }
}
