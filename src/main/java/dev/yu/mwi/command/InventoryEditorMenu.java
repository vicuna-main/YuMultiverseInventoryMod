package dev.yu.mwi.command;

import dev.yu.mwi.service.InventoryIsolationService;
import dev.yu.mwi.service.ItemEditRejectedException;
import dev.yu.mwi.service.TransitionException;
import dev.yu.mwi.storage.PendingTransactionException;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Vanilla chest-backed operator editor with click-atomic live application and persistence. */
final class InventoryEditorMenu extends SimpleContainer {
    private static final int STANDARD_PAGE_SIZE = 54;
    private static final int LABELED_PAGE_SIZE = 27;

    private final InventoryIsolationService service;
    private final ServerPlayer viewer;
    private final ServerPlayer target;
    private final InventoryIsolationService.EditableProfile profile;
    private final String shareId;
    private final int pageOffset;
    private final List<Integer> editableMenuSlots;
    private final List<ItemProfileEditorCodec.SlotInfo> pageSlotInfos;
    private final List<String> allSlotKeys;
    private final boolean labeledLayout;
    private final CompoundTagHolder missingProfile;
    private List<ItemStack> previous;
    private boolean changed;
    private boolean interacted;
    private boolean failed;
    private boolean closed;

    private InventoryEditorMenu(
            int containerSize,
            List<Integer> editableMenuSlots,
            int pageOffset,
            InventoryIsolationService service,
            ServerPlayer viewer,
            ServerPlayer target,
            InventoryIsolationService.EditableProfile profile,
            net.minecraft.nbt.CompoundTag initialPayload,
            List<ItemProfileEditorCodec.SlotInfo> allSlotInfos,
            List<ItemProfileEditorCodec.SlotInfo> pageSlotInfos,
            boolean labeledLayout,
            List<ItemStack> pageItems) {
        super(containerSize);
        if (editableMenuSlots.size() != pageItems.size() || pageSlotInfos.size() != pageItems.size()) {
            throw new IllegalArgumentException("Inventory editor page layout does not match its items");
        }
        this.editableMenuSlots = List.copyOf(editableMenuSlots);
        this.pageSlotInfos = List.copyOf(pageSlotInfos);
        this.allSlotKeys = allSlotInfos.stream().map(ItemProfileEditorCodec.SlotInfo::key).toList();
        this.labeledLayout = labeledLayout;
        this.pageOffset = pageOffset;
        this.service = service;
        this.viewer = viewer;
        this.target = target;
        this.profile = profile;
        this.shareId = profile.shareId();
        this.missingProfile = new CompoundTagHolder(initialPayload);
        renderPage(pageItems);
        previous = snapshotPage();
    }

    static Opened open(
            InventoryIsolationService service,
            ServerPlayer viewer,
            ServerPlayer target,
            InventoryIsolationService.EditableProfile profile,
            int requestedPage) {
        Objects.requireNonNull(service, "service");
        net.minecraft.nbt.CompoundTag initial = profile.storedPayload().orElseGet(
                () -> ItemProfileEditorCodec.emptyLike(profile.shareId(), profile.liveTemplate()));
        ItemProfileEditorCodec.Document document = ItemProfileEditorCodec.decode(
                profile.shareId(), initial, target.registryAccess());
        List<ItemStack> allItems = document.items();
        List<ItemProfileEditorCodec.SlotInfo> allSlotInfos = document.slotInfos();
        if (allItems.isEmpty()) {
            throw new IllegalArgumentException("The selected inventory has no editable slots");
        }
        boolean labeled = requiresSlotLabels(profile.shareId());
        int pageSize = labeled ? LABELED_PAGE_SIZE : STANDARD_PAGE_SIZE;
        int pages = (allItems.size() + pageSize - 1) / pageSize;
        if (requestedPage < 1 || requestedPage > pages) {
            throw new IllegalArgumentException("Page must be between 1 and " + pages);
        }
        int offset = (requestedPage - 1) * pageSize;
        int end = Math.min(offset + pageSize, allItems.size());
        List<ItemStack> pageItems = allItems.subList(offset, end);
        List<ItemProfileEditorCodec.SlotInfo> pageSlotInfos = allSlotInfos.subList(offset, end);
        PageLayout layout = PageLayout.create(pageItems.size(), labeled);
        InventoryEditorMenu container = new InventoryEditorMenu(
                layout.rows() * 9, layout.editableMenuSlots(), offset, service, viewer, target, profile,
                initial, allSlotInfos, pageSlotInfos, labeled, pageItems);
        Component title = Component.literal("INV " + target.getGameProfile().getName() + " | "
                + displayName(profile.shareId()) + " | " + profile.worldName()
                + (pages > 1 ? " | " + requestedPage + '/' + pages : ""));
        viewer.openMenu(new SimpleMenuProvider(
                (containerId, inventory, ignored) -> createChestMenu(
                        layout.rows(), containerId, inventory, container), title));
        if (labeled) {
            viewer.sendSystemMessage(Component.literal(
                            "悬停彩色说明格可查看槽位；真正的物品槽位于说明格正下方。")
                    .withStyle(ChatFormatting.GRAY));
        }
        return new Opened(pages, requestedPage);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return editableMenuSlots.contains(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!editableMenuSlots.contains(slot)) {
            return;
        }
        super.setItem(slot, stack);
    }

    private boolean refreshIfStale() throws TransitionException {
        net.minecraft.nbt.CompoundTag latest = service.readItemEdit(
                target, profile, missingProfile.value());
        ItemProfileEditorCodec.Document document = ItemProfileEditorCodec.decode(
                shareId, latest, target.registryAccess());
        List<String> latestKeys = document.slotInfos().stream()
                .map(ItemProfileEditorCodec.SlotInfo::key)
                .toList();
        if (!latestKeys.equals(allSlotKeys)) {
            throw new TransitionException("目标槽位布局已变化，请重新打开编辑器");
        }
        List<ItemStack> allItems = document.items();
        int end = pageOffset + editableMenuSlots.size();
        if (end > allItems.size()) {
            throw new TransitionException("目标槽位数量已变化，请重新打开编辑器");
        }
        List<ItemStack> latestPage = copy(allItems.subList(pageOffset, end));
        missingProfile.set(latest);
        if (differences(previous, latestPage).isEmpty()) {
            return false;
        }
        renderPage(latestPage);
        previous = latestPage;
        viewer.sendSystemMessage(Component.literal(
                        "目标背包在你操作前发生了变化；本次点击已取消并刷新，请确认后重试。")
                .withStyle(ChatFormatting.YELLOW));
        return true;
    }

    private void commitChanges() throws TransitionException, ItemEditRejectedException {
        if (closed) {
            return;
        }
        List<ItemStack> current = snapshotPage();
        Map<Integer, ItemStack> changes = differences(previous, current);
        if (changes.isEmpty()) {
            return;
        }
        net.minecraft.nbt.CompoundTag latest = service.readItemEdit(
                target, profile, missingProfile.value());
        ItemProfileEditorCodec.Document document = ItemProfileEditorCodec.decode(
                shareId, latest, target.registryAccess());
        for (Map.Entry<Integer, ItemStack> change : changes.entrySet()) {
            service.validateItemEditSlot(
                    target, profile, pageSlotInfos.get(change.getKey()).key(), change.getValue());
        }
        Map<Integer, ItemStack> global = new LinkedHashMap<>();
        changes.forEach((local, stack) -> global.put(pageOffset + local, stack));
        net.minecraft.nbt.CompoundTag updated = document.patch(global);
        service.applyItemEdit(target, profile, updated, true);
        missingProfile.set(updated);
        previous = current;
        changed = true;
        interacted = true;
        if (changes.size() == 1) {
            int localSlot = changes.keySet().iterator().next();
            ItemStack replacement = changes.get(localSlot);
            Component item = replacement.isEmpty()
                    ? Component.literal("空").withStyle(ChatFormatting.GRAY)
                    : replacement.getHoverName().copy();
            viewer.displayClientMessage(Component.literal("已安全写入：")
                    .append(pageSlotInfos.get(localSlot).label())
                    .append(" → ")
                    .append(item), true);
        }
    }

    private void checkpointSource() throws TransitionException {
        service.checkpointImmediately(viewer, "inventory_editor_source_transfer");
        interacted = true;
    }

    @Override
    public void stopOpen(Player player) {
        if (!closed) {
            if (interacted) {
                try {
                    // ChestMenu returns its carried stack before invoking Container.stopOpen.
                    service.checkpointImmediatelyIfIdle(viewer, "inventory_editor_close");
                } catch (TransitionException | LinkageError failure) {
                    failed = true;
                    viewer.sendSystemMessage(Component.literal(
                                    "Inventory editor close checkpoint failed: " + meaningful(failure))
                            .withStyle(ChatFormatting.RED));
                }
            }
            closed = true;
            if (changed && !failed) {
                viewer.sendSystemMessage(Component.literal("Inventory changes saved")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        super.stopOpen(player);
    }

    private void restorePage(List<ItemStack> snapshot) {
        renderPage(snapshot);
        previous = copy(snapshot);
    }

    private void renderPage(List<ItemStack> items) {
        if (items.size() != editableMenuSlots.size()) {
            throw new IllegalArgumentException("Inventory editor page item count changed");
        }
        for (int index = 0; index < items.size(); index++) {
            int itemSlot = editableMenuSlots.get(index);
            ItemStack item = items.get(index);
            super.setItem(itemSlot, item.copy());
            if (labeledLayout) {
                super.setItem(itemSlot - 9, slotMarker(pageSlotInfos.get(index), item));
            }
        }
    }

    private void fail(Throwable failure) {
        failed = true;
        closed = true;
        viewer.sendSystemMessage(Component.literal("Inventory editor closed: " + meaningful(failure))
                .withStyle(ChatFormatting.RED));
        viewer.closeContainer();
    }

    private void acceptPendingCommit(Throwable failure) {
        previous = snapshotPage();
        changed = true;
        failed = true;
        closed = true;
        viewer.sendSystemMessage(Component.literal(
                        "Inventory edit is durably queued for recovery; the editor was closed: "
                                + meaningful(failure))
                .withStyle(ChatFormatting.RED));
        viewer.closeContainer();
    }

    private List<ItemStack> snapshotPage() {
        ArrayList<ItemStack> result = new ArrayList<>(editableMenuSlots.size());
        for (int slot : editableMenuSlots) {
            result.add(getItem(slot).copy());
        }
        return List.copyOf(result);
    }

    private boolean isEditorContainerSlot(int menuSlot) {
        return menuSlot >= 0 && menuSlot < getContainerSize();
    }

    private boolean isEditableMenuSlot(int menuSlot) {
        return editableMenuSlots.contains(menuSlot);
    }

    private void describeMarker(int menuSlot) {
        if (!labeledLayout) {
            return;
        }
        int itemSlot = menuSlot + 9;
        int index = editableMenuSlots.indexOf(itemSlot);
        if (index >= 0) {
            viewer.displayClientMessage(Component.literal("槽位：")
                    .append(pageSlotInfos.get(index).label()), true);
        }
    }

    private static ItemStack slotMarker(ItemProfileEditorCodec.SlotInfo info, ItemStack current) {
        ItemStack marker = new ItemStack(switch (info.kind()) {
            case CURIOS_EQUIPMENT -> Items.LIME_STAINED_GLASS_PANE;
            case CURIOS_COSMETIC -> Items.LIGHT_BLUE_STAINED_GLASS_PANE;
            case COSMETIC_ARMOR -> Items.MAGENTA_STAINED_GLASS_PANE;
            case STANDARD -> Items.GRAY_STAINED_GLASS_PANE;
        });
        marker.set(DataComponents.CUSTOM_NAME,
                Component.literal(info.label()).withStyle(ChatFormatting.GOLD));
        Component currentItem = current.isEmpty()
                ? Component.literal("当前：空").withStyle(ChatFormatting.DARK_GRAY)
                : Component.literal("当前：").withStyle(ChatFormatting.GRAY)
                        .append(current.getHoverName().copy());
        marker.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("正下方格子对应此槽位").withStyle(ChatFormatting.GRAY),
                currentItem)));
        return marker;
    }

    private static boolean requiresSlotLabels(String shareId) {
        return shareId.equals("curios") || shareId.equals("cosmetic_armor");
    }

    private static Map<Integer, ItemStack> differences(List<ItemStack> before, List<ItemStack> after) {
        Map<Integer, ItemStack> changes = new LinkedHashMap<>();
        for (int index = 0; index < before.size(); index++) {
            if (!ItemStack.matches(before.get(index), after.get(index))) {
                changes.put(index, after.get(index).copy());
            }
        }
        return changes;
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::copy).toList();
    }

    private static AbstractContainerMenu createChestMenu(
            int rows, int containerId, Inventory inventory, InventoryEditorMenu container) {
        MenuType<ChestMenu> type = switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("Unsupported inventory row count: " + rows);
        };
        return new TransactionalChestMenu(type, containerId, inventory, container, rows);
    }

    private static String displayName(String shareId) {
        return switch (shareId) {
            case "inventory_contents" -> "inventory";
            case "armor_contents" -> "armor";
            case "off_hand" -> "offhand";
            case "ender_chest" -> "ender chest";
            case "curios" -> "curios";
            case "cosmetic_armor" -> "cosmetic";
            case "trash_slot" -> "trash";
            default -> shareId;
        };
    }

    private static String meaningful(Throwable failure) {
        Throwable current = failure;
        while ((current.getMessage() == null || current.getMessage().isBlank()) && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static boolean hasPendingCommit(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PendingTransactionException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    record Opened(int pages, int page) {
    }

    private static final class CompoundTagHolder {
        private net.minecraft.nbt.CompoundTag value;

        private CompoundTagHolder(net.minecraft.nbt.CompoundTag value) {
            set(value);
        }

        private net.minecraft.nbt.CompoundTag value() {
            return value.copy();
        }

        private void set(net.minecraft.nbt.CompoundTag value) {
            this.value = value.copy();
        }
    }

    /** Commits once after a complete vanilla click and restores both sides if it fails. */
    private static final class TransactionalChestMenu extends ChestMenu {
        private final Inventory inventory;
        private final InventoryEditorMenu editor;

        private TransactionalChestMenu(
                MenuType<?> type,
                int containerId,
                Inventory inventory,
                InventoryEditorMenu editor,
                int rows) {
            super(type, containerId, inventory, editor, rows);
            this.inventory = inventory;
            this.editor = editor;
        }

        @Override
        public void clicked(int slot, int button, ClickType clickType, Player player) {
            // Ownership transfers are deliberately split into pickup/place clicks. The removal
            // side is durably persisted before a later click may add the stack to the other side.
            if (clickType != ClickType.PICKUP || slot == -999) {
                broadcastFullState();
                return;
            }

            if (editor.isEditorContainerSlot(slot) && !editor.isEditableMenuSlot(slot)) {
                editor.describeMarker(slot);
                broadcastFullState();
                return;
            }

            List<ItemStack> pageBefore = editor.snapshotPage();
            List<ItemStack> inventoryBefore = copy(inventory.items);
            ItemStack carriedBefore = getCarried().copy();
            try {
                if (editor.refreshIfStale()) {
                    broadcastFullState();
                    return;
                }
                super.clicked(slot, button, clickType, player);
                boolean pageChanged = !differences(pageBefore, editor.snapshotPage()).isEmpty();
                boolean inventoryChanged = !differences(inventoryBefore, inventory.items).isEmpty();
                if (pageChanged && inventoryChanged) {
                    throw new IllegalStateException("A single editor click changed both ownership domains");
                }
                if (pageChanged) {
                    editor.commitChanges();
                } else if (inventoryChanged) {
                    editor.checkpointSource();
                }
            } catch (ItemEditRejectedException rejection) {
                editor.restorePage(pageBefore);
                restoreInventory(inventoryBefore);
                setCarried(carriedBefore);
                broadcastFullState();
                editor.viewer.sendSystemMessage(Component.literal(
                                "物品未放入，槽位不匹配：" + meaningful(rejection))
                        .withStyle(ChatFormatting.YELLOW));
            } catch (TransitionException | RuntimeException | LinkageError failure) {
                if (hasPendingCommit(failure)) {
                    editor.acceptPendingCommit(failure);
                    return;
                }
                editor.restorePage(pageBefore);
                restoreInventory(inventoryBefore);
                setCarried(carriedBefore);
                broadcastFullState();
                editor.fail(failure);
            }
        }

        private void restoreInventory(List<ItemStack> snapshot) {
            for (int slot = 0; slot < inventory.items.size(); slot++) {
                inventory.items.set(slot, snapshot.get(slot).copy());
            }
            inventory.setChanged();
        }
    }

    private record PageLayout(int rows, List<Integer> editableMenuSlots) {
        private PageLayout {
            editableMenuSlots = List.copyOf(editableMenuSlots);
        }

        private static PageLayout create(int itemCount, boolean labeled) {
            if (itemCount < 1) {
                throw new IllegalArgumentException("Inventory editor page cannot be empty");
            }
            if (!labeled) {
                int rows = Math.max(1, (itemCount + 8) / 9);
                return new PageLayout(rows,
                        java.util.stream.IntStream.range(0, itemCount).boxed().toList());
            }
            int itemRows = (itemCount + 8) / 9;
            ArrayList<Integer> slots = new ArrayList<>(itemCount);
            for (int index = 0; index < itemCount; index++) {
                int group = index / 9;
                int column = index % 9;
                slots.add(group * 18 + 9 + column);
            }
            return new PageLayout(itemRows * 2, slots);
        }
    }
}
