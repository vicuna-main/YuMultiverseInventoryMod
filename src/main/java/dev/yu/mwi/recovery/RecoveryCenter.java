package dev.yu.mwi.recovery;

import dev.yu.mwi.adapter.vanilla.ItemListNbtCodec;
import dev.yu.mwi.storage.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.util.*;

/** Server-authoritative case workflow. A mismatch is evidence, never a grant authorization. */
public final class RecoveryCenter {
    public interface Host {
        void awaitPendingWrites(UUID owner) throws IOException;
        CompoundTag scope(ServerPlayer player) throws IOException;
        String sessionToken(ServerPlayer player) throws IOException;
        void commit(ServerPlayer player, CompoundTag scope, Optional<CompoundTag> expectedInventory,
                    Optional<CompoundTag> inventory, RecoveryMutation mutation) throws IOException;
    }
    public record Preview(UUID id, long version, String checksum, String sessionToken, CompoundTag scope,
                          CompoundTag inventory, String state, List<ItemStack> items, String warning) {
        public Preview { scope = scope.copy(); inventory = inventory.copy(); items = items.stream().map(ItemStack::copy).toList(); }
        @Override public CompoundTag scope() { return scope.copy(); }
        @Override public CompoundTag inventory() { return inventory.copy(); }
        @Override public List<ItemStack> items() { return items.stream().map(ItemStack::copy).toList(); }
    }
    private final ProfileStore store;
    private final Host host;
    public RecoveryCenter(ProfileStore store, Host host) { this.store = store; this.host = host; }

    public List<StoredRecovery> list(UUID owner) throws IOException {
        host.awaitPendingWrites(owner);
        return store.listRecoveries(owner);
    }
    public StoredRecovery get(UUID owner, UUID id) throws IOException {
        host.awaitPendingWrites(owner);
        return store.loadRecovery(owner, id).orElseThrow(() -> new IOException("恢复记录不存在"));
    }
    public CompoundTag scope(ServerPlayer player) throws IOException { return host.scope(player); }
    public String sessionToken(ServerPlayer player) throws IOException { return host.sessionToken(player); }
    public String blockedReason(ServerPlayer player, CompoundTag payload) {
        return RecoverySourcePolicy.blockedReason(payload);
    }

    private void requireEligible(ServerPlayer player, CompoundTag payload) throws IOException {
        String reason = blockedReason(player, payload);
        if (!reason.isEmpty()) throw new IOException(reason);
    }

    /** Visibility describes a case; execution eligibility is checked independently at claim time. */
    public List<StoredRecovery> visible(ServerPlayer player, boolean readyOnly) throws IOException {
        if (!player.isAlive()) return List.of();
        List<StoredRecovery> result = new ArrayList<>();
        for (StoredRecovery summary : list(player.getUUID())) {
            if ("CLOSED".equals(summary.payload().getString("state"))) continue;
            StoredRecovery record;
            try { record = get(player.getUUID(), summary.sourceId()); }
            catch (IOException failure) {
                dev.yu.mwi.YuMultiverseInventory.LOGGER.error("Recovery source {} unreadable for {}; retained for inspection",
                        summary.sourceId(), player.getUUID(), failure);
                if (!readyOnly) result.add(summary);
                continue;
            }
            CompoundTag payload = record.payload();
            if (readyOnly && (!RecoverySourcePolicy.nativeSource(payload) || !"AVAILABLE".equals(payload.getString("state")) || !payload.getBoolean("notify_ready"))) continue;
            result.add(record);
        }
        return List.copyOf(result);
    }

    public boolean shouldNotify(ServerPlayer player, UUID source) throws IOException {
        if (!player.isAlive()) return false;
        CompoundTag payload = get(player.getUUID(), source).payload();
        return RecoverySourcePolicy.nativeSource(payload) && !"CLOSED".equals(payload.getString("state")) && payload.getBoolean("notify_ready");
    }

    public Preview preview(ServerPlayer player, UUID id) throws IOException {
        if (!player.isAlive()) throw new IOException("请复活后打开恢复中心");
        StoredRecovery record = get(player.getUUID(), id);
        CompoundTag data = record.payload();
        CompoundTag scope = host.scope(player);
        List<ItemStack> items;
        String warning = eligibilityWarning(player, data, scope);
        try { items = RecoveryItems.decode(data.get("remaining"), player.registryAccess()); }
        catch (IOException failure) {
            // Unreadable evidence is still reviewable, but never becomes a partial item grant.
            items = List.of(); warning = "物品暂时无法完整解析 · 原始记录保留 · 仅可申请核验";
        }
        return new Preview(id, record.version(), record.checksum(), host.sessionToken(player), scope,
                ItemListNbtCodec.encode(player.getInventory().items, player.registryAccess()),
                RecoverySourcePolicy.nativeSource(data) ? data.getString("state") : "HISTORY", items, warning);
    }

    private String eligibilityWarning(ServerPlayer player, CompoundTag data, CompoundTag scope) {
        String blocked = blockedReason(player, data);
        if (!blocked.isEmpty()) return blocked;
        if (player.isCreative() || player.isSpectator()) return "物品已保留，请进入生存或冒险模式后领取";
        if (!scope.equals(data.getCompound("identity").getCompound("scope")))
            return "物品已保留，请返回来源所属的世界或背包共享组后领取";
        return "";
    }

    public int claim(ServerPlayer player, Preview preview) throws IOException {
        RecoveryNotifications.requireAuthenticated(player);
        if (!player.isAlive() || player.isSpectator() || player.isCreative()) throw new IOException("当前状态无法领取");
        if (!preview.sessionToken().equals(host.sessionToken(player))) throw new IOException("会话、世界或配置已变化，请重新预览");
        StoredRecovery record = get(player.getUUID(), preview.id());
        if (record.version() != preview.version() || !record.checksum().equals(preview.checksum()))
            throw new IOException("记录已更新，请重新打开预览");
        CompoundTag payload = record.payload();
        if (!"AVAILABLE".equals(payload.getString("state"))) throw new IOException("本条尚未核验或已经领完，不能补发");
        requireEligible(player, payload);
        if (!preview.scope().equals(payload.getCompound("identity").getCompound("scope")))
            throw new IOException("恢复范围不匹配");
        List<ItemStack> incoming = RecoveryItems.decode(payload.get("remaining"), player.registryAccess());
        List<ItemStack> before = ItemListNbtCodec.decode(preview.inventory(), 36, player.registryAccess(), "recovery");
        RecoveryItems.Plan plan = RecoveryItems.plan(before, incoming);
        if (plan.moved() == 0) throw new IOException("背包空间不足，全部物品仍在托管中，请腾出空间后重试");
        RecoveryItems.verifyBalance(before, incoming, plan, player.registryAccess());
        payload.put("remaining", RecoveryItems.encode(plan.remaining(), player.registryAccess()));
        payload.putString("state", plan.remaining().isEmpty() ? "CLOSED" : "AVAILABLE");
        payload.putString("last_action", "claim");
        host.commit(player, preview.scope(), Optional.of(preview.inventory()),
                Optional.of(ItemListNbtCodec.encode(plan.inventory(), player.registryAccess())),
                new RecoveryMutation(preview.id(), preview.version(), UUID.randomUUID(), payload));
        return plan.moved();
    }

    /** Explicit review records the operator and evidence checksum. Closing is permanent. */
    public void requestReview(ServerPlayer player, Preview preview) throws IOException {
        RecoveryNotifications.requireAuthenticated(player);
        if (!player.isAlive() || !preview.sessionToken().equals(host.sessionToken(player)))
            throw new IOException("会话已变化，请重新预览");
        StoredRecovery record = get(player.getUUID(), preview.id());
        CompoundTag payload = record.payload();
        requireEligible(player, payload);
        if (record.version() != preview.version() || !record.checksum().equals(preview.checksum())
                || !"REVIEW".equals(payload.getString("state")))
            throw new IOException("记录已更新，请重新预览");
        if (payload.getBoolean("review_requested")) return;
        payload.putBoolean("review_requested", true); payload.putLong("requested_at", System.currentTimeMillis());
        store.commitRecovery(player.getUUID(), store.nextRevision(player.getUUID()), Map.of(),
                new RecoveryMutation(preview.id(), record.version(), UUID.randomUUID(), payload));
    }

    public void review(ServerPlayer player, UUID id, String expected, boolean approve, String operator, String reason)
            throws IOException {
        if (reason.isBlank() || reason.length() > 512) throw new IOException("请填写 1–512 字的核验依据");
        StoredRecovery record = get(player.getUUID(), id);
        if (!record.checksum().equals(expected)) throw new IOException("核验码变化，请重新 inspect");
        CompoundTag payload = record.payload();
        if (approve && !"REVIEW".equals(payload.getString("state"))) throw new IOException("只能批准待核验记录");
        requireEligible(player, payload);
        if (approve && RecoveryItems.decode(payload.get("remaining"), player.registryAccess()).isEmpty())
            throw new IOException("该异常仅保留证据，尚无核实过的物品来源，不能批准补发");
        payload.putString("state", approve ? "AVAILABLE" : "CLOSED");
        if (approve) payload.putBoolean("notify_ready", true);
        payload.putString("reviewed_by", operator); payload.putString("review_reason", reason);
        payload.putLong("reviewed_at", System.currentTimeMillis());
        host.commit(player, payload.getCompound("identity").getCompound("scope"), Optional.empty(), Optional.empty(),
                new RecoveryMutation(id, record.version(), UUID.randomUUID(), payload));
        RecoveryNotifications.enqueue(player);
    }

}
