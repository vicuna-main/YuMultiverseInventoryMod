package dev.yu.mwi.command;

import dev.yu.mwi.integration.mvinv.MvinvLegacyStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Chest GUI for taking or editing items in an offline Multiverse-Inventories profile. */
final class MvinvRecoveryMenu extends SimpleContainer implements ContainerListener {
    private static final int PAGE_SIZE = 54;

    private final MvinvLegacyStore store;
    private final MvinvLegacyStore.OpenProfile profile;
    private final ServerPlayer viewer;
    private final int pageOffset;
    private final int editableOnPage;
    private final Set<Integer> editedSlots = new LinkedHashSet<>();
    private List<ItemStack> previous;
    private boolean closed;

    private MvinvRecoveryMenu(
            int containerSize,
            int editableOnPage,
            int pageOffset,
            MvinvLegacyStore store,
            MvinvLegacyStore.OpenProfile profile,
            ServerPlayer viewer,
            List<ItemStack> pageItems) {
        super(containerSize);
        this.editableOnPage = editableOnPage;
        this.pageOffset = pageOffset;
        this.store = store;
        this.profile = profile;
        this.viewer = viewer;
        for (int index = 0; index < pageItems.size(); index++) {
            super.setItem(index, pageItems.get(index).copy());
        }
        previous = snapshotPage();
        addListener(this);
    }

    static Opened open(
            MvinvLegacyStore store,
            MvinvLegacyStore.OpenProfile profile,
            ServerPlayer viewer,
            int requestedPage) {
        Objects.requireNonNull(store, "store");
        List<ItemStack> items = profile.items();
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (requestedPage < 1 || requestedPage > pages) {
            throw new IllegalArgumentException("Page must be between 1 and " + pages);
        }
        int offset = (requestedPage - 1) * PAGE_SIZE;
        List<ItemStack> pageItems = items.subList(offset, Math.min(offset + PAGE_SIZE, items.size()));
        int rows = Math.max(1, (pageItems.size() + 8) / 9);
        MvinvRecoveryMenu container = new MvinvRecoveryMenu(
                rows * 9, pageItems.size(), offset, store, profile, viewer, pageItems);
        Component title = Component.literal("MVInv " + profile.playerName() + " | "
                + profile.section().commandName() + " | " + profile.sourceName()
                + (pages > 1 ? " | " + requestedPage + '/' + pages : ""));
        viewer.openMenu(new SimpleMenuProvider(
                (containerId, inventory, ignored) -> createChestMenu(rows, containerId, inventory, container), title));
        return new Opened(pages, requestedPage);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot >= 0 && slot < editableOnPage;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < editableOnPage) {
            super.setItem(slot, stack);
        }
    }

    @Override
    public void containerChanged(Container ignored) {
        if (closed) {
            return;
        }
        List<ItemStack> current = snapshotPage();
        for (int index = 0; index < current.size(); index++) {
            if (!ItemStack.matches(previous.get(index), current.get(index))) {
                editedSlots.add(pageOffset + index);
            }
        }
        previous = current;
    }

    @Override
    public void stopOpen(Player player) {
        if (!closed) {
            closed = true;
            removeListener(this);
            saveChanges();
        }
        super.stopOpen(player);
    }

    private void saveChanges() {
        if (editedSlots.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> changes = new LinkedHashMap<>();
        for (int globalSlot : editedSlots) {
            int localSlot = globalSlot - pageOffset;
            if (localSlot >= 0 && localSlot < editableOnPage) {
                changes.put(globalSlot, getItem(localSlot).copy());
            }
        }
        try {
            store.save(profile, changes, viewer.registryAccess());
            viewer.sendSystemMessage(Component.literal("MVInv recovery profile changes saved")
                    .withStyle(ChatFormatting.GRAY));
        } catch (IOException | RuntimeException | LinkageError failure) {
            viewer.sendSystemMessage(Component.literal("MVInv recovery changes were not saved: " + meaningful(failure))
                    .withStyle(ChatFormatting.RED));
        }
    }

    private List<ItemStack> snapshotPage() {
        ArrayList<ItemStack> items = new ArrayList<>(editableOnPage);
        for (int slot = 0; slot < editableOnPage; slot++) {
            items.add(getItem(slot).copy());
        }
        return List.copyOf(items);
    }

    private static AbstractContainerMenu createChestMenu(
            int rows, int containerId, Inventory inventory, Container container) {
        MenuType<ChestMenu> type = switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("Unsupported inventory row count: " + rows);
        };
        return new ChestMenu(type, containerId, inventory, container, rows);
    }

    private static String meaningful(Throwable failure) {
        Throwable current = failure;
        while ((current.getMessage() == null || current.getMessage().isBlank()) && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    record Opened(int pages, int page) {
    }
}
