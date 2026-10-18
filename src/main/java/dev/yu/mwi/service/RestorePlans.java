package dev.yu.mwi.service;

import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.storage.*;
import net.minecraft.nbt.*;
import java.io.IOException;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Detached administrator planning. All profile writes still use the existing player WAL. */
final class RestorePlans {
    private RestorePlans() {}
    static Set<ProfileKey> keys(CompoundTag context, UUID owner) throws IOException {
        Set<ProfileKey> result = new LinkedHashSet<>();
        for (Tag tag : context.getList("keys", Tag.TAG_COMPOUND)) {
            ProfileKey key = TransitionJournal.decodeKey((CompoundTag) tag);
            if (!owner.equals(key.playerId()) || !result.add(key)) throw new IOException("恢复范围身份不完整");
        }
        if (result.isEmpty()) throw new IOException("尚无可靠的当前世界组记录；请让该玩家登录恢复会话后重试");
        return Set.copyOf(result);
    }
    static CompoundTag prepare(ProfileStore store, UUID owner, String selector, Set<ProfileKey> required,
            RecoveryInspection current, ZoneId zone, String operator) throws IOException {
        if (!current.ready(required)) throw new IOException("当前存储仍缺少可靠来源，不能用历史数据掩盖未决事务；请先查看恢复状态");
        List<RestorePoint> points = store.restorePoints(owner);
        RestorePoint point = selector.matches("#[0-9]+")
                ? points.stream().filter(p -> p.revision() == Long.parseLong(selector.substring(1))).findFirst()
                        .orElseThrow(() -> new IOException("指定修订没有可验证的恢复点"))
                : RestorePointSelector.select(selector, points, zone);
        if (!point.completeFor(required)) throw new IOException("该记录是部分数据，缺少完整恢复依赖或验证失败");
        CompoundTag identity = new CompoundTag(); identity.putLong("point", point.revision());
        ListTag keys = new ListTag(); required.stream().sorted(Comparator.comparing(Object::toString))
                .forEach(key -> keys.add(TransitionJournal.encodeKey(key))); identity.put("keys", keys);
        UUID operation = UUID.nameUUIDFromBytes((owner + ":" + NbtChecksums.canonicalSha256(identity)).getBytes(StandardCharsets.UTF_8));
        CompoundTag prior = store.restoreRequest(owner);
        if ("PREPARED".equals(prior.getString("state")) && (!prior.hasUUID("operation_id")
                || !operation.equals(prior.getUUID("operation_id"))))
            throw new IOException("已有不同目标的恢复任务，请先处理现有任务");
        var receipt = store.restoreOperation(owner, operation);
        if (receipt.isPresent()) return receipt.orElseThrow();
        if (prior.hasUUID("operation_id") && operation.equals(prior.getUUID("operation_id"))) return prior;
        if ("PREPARED".equals(prior.getString("state"))) throw new IOException("已有不同目标的恢复任务，请先处理现有任务");
        requireNoSettledSourceRollback(store, owner, required, point.revision());
        CompoundTag request = new CompoundTag(); request.putUUID("operation_id", operation);
        request.putString("state", "PREPARED"); request.putString("selector", selector);
        request.putLong("point_revision", point.revision()); request.putLong("point_time", point.committedAt().toEpochMilli());
        request.putString("operator", operator); request.put("keys", keys);
        ListTag profiles = new ListTag();
        for (ProfileKey key : required) {
            StoredProfile before = current.profiles().get(key);
            if (before == null) throw new IOException("当前恢复范围缺少 " + key.shareId() + "，不能猜测原状态");
            CompoundTag entry = new CompoundTag(); entry.put("key", TransitionJournal.encodeKey(key));
            entry.putString("before_digest", NbtChecksums.canonicalSha256(before.payload()));
            entry.put("before", before.payload()); entry.put("target", point.profiles().get(key).payload());
            profiles.add(entry);
        }
        request.put("profiles", profiles); store.saveRestoreRequest(owner, request); return request;
    }
    static Map<String, CompoundTag> targets(CompoundTag request, Map<String, ProfileKey> active,
            Map<ProfileKey, StoredProfile> current) throws IOException {
        if (!"PREPARED".equals(request.getString("state"))) return Map.of();
        if (!keys(request, active.values().iterator().next().playerId()).equals(Set.copyOf(active.values())))
            throw new IOException("恢复任务的世界组或启用档案已变化，请重新检查范围");
        Map<String, CompoundTag> result = new LinkedHashMap<>();
        for (Tag tag : request.getList("profiles", Tag.TAG_COMPOUND)) {
            CompoundTag entry=(CompoundTag)tag; ProfileKey key=TransitionJournal.decodeKey(entry.getCompound("key"));
            StoredProfile before=current.get(key);
            if (before==null || !entry.getString("before_digest").equals(NbtChecksums.canonicalSha256(before.payload())))
                throw new IOException("恢复准备后权威档案已变化，拒绝覆盖 " + key.shareId());
            if (!entry.contains("target",Tag.TAG_COMPOUND) || result.put(key.shareId(),entry.getCompound("target").copy())!=null)
                throw new IOException("恢复任务档案集合损坏");
        }
        if (!result.keySet().equals(active.keySet())) throw new IOException("恢复任务不是完整档案集合");
        return result;
    }
    private static void requireNoSettledSourceRollback(ProfileStore store, UUID owner, Set<ProfileKey> required, long point) throws IOException {
        for (StoredRecovery summary : store.listRecoveries(owner)) {
            if (summary.profileRevision() <= point) continue;
            var source=store.loadRecovery(owner,summary.sourceId()).orElseThrow(() -> new IOException("恢复来源凭证不完整"));
            CompoundTag scope=source.payload().getCompound("identity").getCompound("scope");
            for(ProfileKey key:required) {
                CompoundTag entry=scope.getCompound(key.shareId());
                if(key.scope().type().name().equals(entry.getString("scope_type")) && key.scope().id().equals(entry.getString("scope_id"))
                        && key.gameMode().name().equals(entry.getString("game_mode")))
                    throw new IOException("该时间早于已有物品领取或托管凭证；拒绝回滚已结案来源，请核对恢复中心记录");
            }
        }
    }
}
