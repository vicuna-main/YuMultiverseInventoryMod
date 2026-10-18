package dev.yu.mwi.recovery;

import dev.yu.mwi.adapter.vanilla.ItemListNbtCodec;
import dev.yu.mwi.storage.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.util.*;
import java.util.function.BiFunction;

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
    private final Map<String, BiFunction<ServerPlayer, CompoundTag, String>> sourceGuards = new HashMap<>();
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
    public void registerSourceGuard(String provider, BiFunction<ServerPlayer, CompoundTag, String> guard) {
        sourceGuards.put(Objects.requireNonNull(provider), Objects.requireNonNull(guard));
    }

    public String blockedReason(ServerPlayer player, CompoundTag payload) {
        CompoundTag evidence = payload.getCompound("identity").getCompound("evidence");
        String type = evidence.getString("type");
        if ("grave_claim".equals(type)) return "普通墓碑物品请通过墓碑领取";
        String provider = evidence.getString("provider");
        if ("yu_multiverse_inventory".equals(provider) && "activation_escrow".equals(type)) return "";
        // Earlier candidates had no provider field. They must not bypass the live grave check.
        if (provider.isEmpty() && ("death_escrow".equals(type) || evidence.hasUUID("death_id"))) provider = "tombstonedefender";
        if (provider.isEmpty()) return "";
        var guard = sourceGuards.get(provider);
        if (guard == null) return "来源状态检查尚未就绪，请稍后核验";
        try { return Objects.requireNonNull(guard.apply(player, evidence.copy())); }
        catch (RuntimeException failure) {
            dev.yu.mwi.YuMultiverseInventory.LOGGER.error("Recovery source check failed for {}", player.getUUID(), failure);
            return "墓碑状态暂时无法确认，已保留托管物品";
        }
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
            if (readyOnly && (!"AVAILABLE".equals(payload.getString("state")) || !payload.getBoolean("notify_ready"))) continue;
            result.add(record);
        }
        return List.copyOf(result);
    }

    public boolean shouldNotify(ServerPlayer player, UUID source) throws IOException {
        if (!player.isAlive()) return false;
        CompoundTag payload = get(player.getUUID(), source).payload();
        return !"CLOSED".equals(payload.getString("state")) && payload.getBoolean("notify_ready");
    }

    /** Called only by trusted server integrations after they exclusively fence their source. */
    public void deposit(ServerPlayer player, UUID sourceId, CompoundTag scope, ListTag items,
                        CompoundTag evidence, boolean certified) throws IOException {
        CompoundTag identity = new CompoundTag();
        identity.put("scope", scope.copy()); identity.put("evidence", evidence.copy());
        identity.put("original_items", items.copy());
        host.awaitPendingWrites(player.getUUID());
        Optional<StoredRecovery> existing = store.loadRecovery(player.getUUID(), sourceId);
        if (existing.isPresent()) {
            if (existing.orElseThrow().payload().getBoolean("external_closure")
                    && "CLOSED".equals(existing.orElseThrow().payload().getString("state"))) return;
            if (!NbtChecksums.canonicalSha256(existing.orElseThrow().payload().getCompound("identity"))
                    .equals(NbtChecksums.canonicalSha256(identity)))
                throw new IOException("同一来源已有不同证据，不能重建或覆盖");
            return; // Includes spent sources: an acknowledgement retry must not reopen them.
        }
        if (certified) {
            RecoveryItems.decode(items, player.registryAccess());
            if (items.isEmpty() && !"grave_escrow".equals(evidence.getString("type"))) throw new IOException("没有待恢复的托管物品");
        }
        CompoundTag payload = new CompoundTag();
        payload.put("identity", identity); payload.put("remaining", items.copy());
        payload.putString("state", certified ? "AVAILABLE" : "REVIEW");
        payload.putBoolean("notify_ready", Set.of("death_escrow", "grave_escrow").contains(evidence.getString("type"))
                && "tombstonedefender".equals(evidence.getString("provider")));
        payload.putLong("created_at", System.currentTimeMillis());
        host.commit(player, scope, Optional.empty(), Optional.empty(),
                new RecoveryMutation(sourceId, -1, UUID.randomUUID(), payload));
        RecoveryNotifications.enqueue(player);
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
                ItemListNbtCodec.encode(player.getInventory().items, player.registryAccess()), data.getString("state"), items, warning);
    }

    private String eligibilityWarning(ServerPlayer player, CompoundTag data, CompoundTag scope) {
        if (player.isCreative() || player.isSpectator()) return "物品已保留，请进入生存或冒险模式后领取";
        if (!scope.equals(data.getCompound("identity").getCompound("scope")))
            return "物品已保留，请返回来源所属的世界或背包共享组后领取";
        return blockedReason(player, data);
    }

    public int claim(ServerPlayer player, Preview preview) throws IOException {
        return claim(player, preview, false);
    }

    private int claim(ServerPlayer player, Preview preview, boolean allowFull) throws IOException {
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
        if (plan.moved() == 0 && !allowFull) throw new IOException("背包空间不足，全部物品仍在托管中，请腾出空间后重试");
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
        if (record.version() != preview.version() || !record.checksum().equals(preview.checksum())
                || !"REVIEW".equals(payload.getString("state")))
            throw new IOException("记录已更新，请重新预览");
        if (payload.getBoolean("review_requested")) return;
        payload.putBoolean("review_requested", true); payload.putLong("requested_at", System.currentTimeMillis());
        store.commitRecovery(player.getUUID(), store.nextRevision(player.getUUID()), Map.of(),
                new RecoveryMutation(preview.id(), record.version(), UUID.randomUUID(), payload));
    }

    /** TSD has durably fenced the physical grave before invoking this V2 ownership transfer. */
    public void depositGrave(ServerPlayer player, UUID id, CompoundTag scope, ListTag items, CompoundTag evidence) throws IOException {
        RecoveryNotifications.requireAuthenticated(player);
        host.awaitPendingWrites(player.getUUID());
        var existing = store.loadRecovery(player.getUUID(), id);
        if (existing.isPresent() && "CLOSED".equals(existing.orElseThrow().payload().getString("state"))) return;
        if (!"grave_escrow".equals(evidence.getString("type")) || !evidence.getBoolean("exclusive_escrow"))
            throw new IOException("墓碑必须先持久封存，才能交接物品");
        deposit(player, id, scope, items, evidence, true);
    }

    public int claimGrave(ServerPlayer player, UUID id) throws IOException {
        if ("CLOSED".equals(get(player.getUUID(), id).payload().getString("state"))) return 0;
        return claim(player, preview(player, id), true);
    }

    /** A normal grave transfer has completed; persist its current result, never replay its old snapshot. */
    public void completeExternalTransfer(ServerPlayer player, UUID id, CompoundTag evidence) throws IOException {
        host.awaitPendingWrites(player.getUUID());
        Optional<StoredRecovery> existing = store.loadRecovery(player.getUUID(), id);
        if (existing.isPresent() && "CLOSED".equals(existing.orElseThrow().payload().getString("state"))) return;
        if (!"grave_claim".equals(evidence.getString("type"))) throw new IOException("墓碑存档凭证类型不匹配");
        CompoundTag scope = host.scope(player), identity = new CompoundTag();
        identity.put("scope", scope); identity.put("evidence", evidence.copy()); identity.put("original_items", new ListTag());
        CompoundTag payload = existing.map(StoredRecovery::payload).orElseGet(CompoundTag::new);
        if (existing.isPresent() && !"grave_claim".equals(payload.getCompound("identity").getCompound("evidence").getString("type")))
            throw new IOException("墓碑存档凭证来源不匹配");
        if (existing.isEmpty()) { payload.put("identity", identity); payload.putLong("created_at", System.currentTimeMillis()); }
        payload.putString("state", "CLOSED"); payload.put("remaining", new ListTag());
        payload.putString("last_action", "normal_grave_claim_committed");
        host.commit(player, payload.getCompound("identity").getCompound("scope"), Optional.empty(), Optional.empty(),
                new RecoveryMutation(id, existing.map(StoredRecovery::version).orElse(-1L), UUID.randomUUID(), payload));
    }

    public void closeExternalSource(ServerPlayer player, UUID source, String evidenceChecksum, String operator, String reason)
            throws IOException {
        if (!evidenceChecksum.matches("[a-fA-F0-9]{64}") || reason.isBlank() || reason.length() > 512)
            throw new IOException("需要原始证据的 SHA-256 及 1–512 字结案依据");
        host.awaitPendingWrites(player.getUUID());
        if (store.loadRecovery(player.getUUID(), source).isPresent())
            throw new IOException("来源已有记录，请先 inspect 并用 close 核验码关闭");
        CompoundTag scope = host.scope(player), identity = new CompoundTag(), evidence = new CompoundTag();
        evidence.putString("type", "external_recovery_closed"); evidence.putString("sha256", evidenceChecksum.toLowerCase(Locale.ROOT));
        identity.put("scope", scope); identity.put("evidence", evidence); identity.put("original_items", new ListTag());
        CompoundTag payload = new CompoundTag(); payload.put("identity", identity); payload.putString("state", "CLOSED");
        payload.put("remaining", new ListTag()); payload.putBoolean("external_closure", true);
        payload.putString("reviewed_by", operator); payload.putString("review_reason", reason);
        payload.putLong("reviewed_at", System.currentTimeMillis());
        host.commit(player, scope, Optional.empty(), Optional.empty(), new RecoveryMutation(source, -1, UUID.randomUUID(), payload));
    }

    public void review(ServerPlayer player, UUID id, String expected, boolean approve, String operator, String reason)
            throws IOException {
        if (reason.isBlank() || reason.length() > 512) throw new IOException("请填写 1–512 字的核验依据");
        StoredRecovery record = get(player.getUUID(), id);
        if (!record.checksum().equals(expected)) throw new IOException("核验码变化，请重新 inspect");
        CompoundTag payload = record.payload();
        if (approve && !"REVIEW".equals(payload.getString("state"))) throw new IOException("只能批准待核验记录");
        if (approve) requireEligible(player, payload);
        if (approve && RecoveryItems.decode(payload.get("remaining"), player.registryAccess()).isEmpty())
            throw new IOException("该异常仅保留证据，尚无核实过的物品来源，不能批准补发");
        payload.putString("state", approve ? "AVAILABLE" : "CLOSED");
        if (approve) payload.putBoolean("notify_ready", "tombstonedefender".equals(
                payload.getCompound("identity").getCompound("evidence").getString("provider")));
        payload.putString("reviewed_by", operator); payload.putString("review_reason", reason);
        payload.putLong("reviewed_at", System.currentTimeMillis());
        host.commit(player, payload.getCompound("identity").getCompound("scope"), Optional.empty(), Optional.empty(),
                new RecoveryMutation(id, record.version(), UUID.randomUUID(), payload));
        RecoveryNotifications.enqueue(player);
    }

}
