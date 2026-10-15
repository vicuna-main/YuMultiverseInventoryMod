package dev.yu.mwi.recovery;

import dev.yu.mwi.api.RecoveryBridgeV1;
import dev.yu.mwi.storage.StoredRecovery;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import java.io.IOException;
import java.util.*;

/** Phantom preview slots: no Minecraft click/drag/transfer path can remove their contents. */
public final class RecoveryMenu extends ChestMenu {
    private static final int PAGE_SIZE = 45;
    private final ServerPlayer owner;
    private final RecoveryCenter center;
    private final List<StoredRecovery> cases;
    private final RecoveryCenter.Preview preview;
    private final int page;
    private final int pages;
    private boolean submitted;

    private RecoveryMenu(int id, ServerPlayer owner, RecoveryCenter center, SimpleContainer display,
                         List<StoredRecovery> cases, RecoveryCenter.Preview preview, int page, int pages) {
        super(MenuType.GENERIC_9x6, id, owner.getInventory(), display, 6);
        this.owner = owner; this.center = center; this.cases = cases; this.preview = preview;
        this.page = page; this.pages = pages;
    }

    public static void open(ServerPlayer player, UUID source, int page) throws IOException {
        open(player, source, page, false);
    }

    public static void open(ServerPlayer player, UUID source, int page, boolean automatic) throws IOException {
        RecoveryNotifications.requireAuthenticated(player);
        RecoveryCenter center = RecoveryBridgeV1.service(player);
        if (!player.isAlive()) throw new IOException("请复活后打开恢复中心");
        player.closeContainer();
        RecoveryCenter.Preview preview = source == null ? null : center.preview(player, source);
        List<StoredRecovery> cases = preview == null ? center.visible(player, false) : List.of();
        List<ItemStack> items = preview == null ? List.of() : preview.items();
        int count = preview == null ? cases.size() : items.size();
        int pages = Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 0 || page >= pages) throw new IOException("页码无效");
        SimpleContainer display = new SimpleContainer(54);
        int offset = page * PAGE_SIZE;
        for (int slot = 0; slot < Math.min(PAGE_SIZE, count - offset); slot++) {
            if (preview != null) display.setItem(slot, items.get(offset + slot).copy());
            else {
                StoredRecovery record = cases.get(offset + slot);
                String state = record.payload().getString("state");
                display.setItem(slot, icon("AVAILABLE".equals(state) ? Items.CHEST : Items.PAPER,
                        ("AVAILABLE".equals(state) ? "物品已托管 · 查看领取条件 · " : "待核验 · ") + record.sourceId()));
            }
        }
        display.setItem(45, icon(Items.ARROW, "上一页"));
        display.setItem(53, icon(Items.ARROW, "下一页"));
        display.setItem(48, icon(Items.BOOK, "返回恢复记录"));
        display.setItem(49, icon(preview != null && "AVAILABLE".equals(preview.state()) ? Items.EMERALD : Items.PAPER,
                preview == null ? "点击上方记录查看物品" : !preview.warning().isEmpty() && "AVAILABLE".equals(preview.state())
                        ? "暂不可领取 · 请查看右侧原因" : "AVAILABLE".equals(preview.state())
                        ? "确认恢复 · 空间不足的物品继续托管" : "申请管理员核验 · 不会自动补发"));
        display.setItem(50, icon(Items.BARRIER, preview != null && !preview.warning().isEmpty()
                ? preview.warning() : "只读预览 · 物品不能直接取出"));
        Component title = Component.literal("背包恢复中心 " + (page + 1) + "/" + pages);
        var opened = player.openMenu(new SimpleMenuProvider((id, inventory, ignored) ->
                new RecoveryMenu(id, player, center, display, cases, preview, page, pages), title));
        if (opened.isEmpty()) throw new IOException("恢复界面暂时无法打开，记录仍保留");
        if (source != null && !automatic) RecoveryNotifications.viewed(player, source);
    }

    private static ItemStack icon(Item item, String name) {
        ItemStack stack = new ItemStack(item); stack.set(DataComponents.CUSTOM_NAME, Component.literal(name)); return stack;
    }

    static boolean isButtonClick(int slot, int button, ClickType type) {
        return slot >= 0 && slot < 54 && button == 0 && type == ClickType.PICKUP;
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (player != owner || submitted || !isButtonClick(slot, button, type)) { broadcastFullState(); return; }
        try {
            if (slot == 45 && page > 0) open(owner, preview == null ? null : preview.id(), page - 1);
            else if (slot == 53 && page + 1 < pages) open(owner, preview == null ? null : preview.id(), page + 1);
            else if (slot == 48) open(owner, null, 0);
            else if (slot < PAGE_SIZE && preview == null && page * PAGE_SIZE + slot < cases.size())
                open(owner, cases.get(page * PAGE_SIZE + slot).sourceId(), 0);
            else if (slot == 49 && preview != null) {
                RecoveryNotifications.viewed(owner, preview.id());
                if ("AVAILABLE".equals(preview.state()) && !preview.warning().isEmpty()) {
                    owner.sendSystemMessage(Component.literal("[背包恢复] " + preview.warning())); return;
                }
                submitted = true;
                if ("AVAILABLE".equals(preview.state())) {
                    int moved = center.claim(owner, preview);
                    owner.sendSystemMessage(Component.literal("[背包恢复] 已恢复 " + moved + " 件物品，存档已回读验证。未领取的部分仍在托管。"));
                    open(owner, null, 0);
                } else {
                    center.requestReview(owner, preview);
                    owner.sendSystemMessage(Component.literal("[背包恢复] 核验申请已保存，编号 " + preview.id() + "。管理员核实来源后才能开放领取。"));
                    owner.closeContainer();
                }
            } else broadcastFullState();
        } catch (IOException | RuntimeException failure) {
            submitted = true;
            owner.closeContainer();
            owner.sendSystemMessage(Component.literal("[背包恢复] " + failure.getMessage()));
        }
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return false; }
    @Override public boolean canDragTo(Slot slot) { return false; }
    @Override public boolean stillValid(Player player) { return player == owner && owner.isAlive() && !owner.isRemoved(); }
}
