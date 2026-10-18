package dev.yu.mwi.integration.curios;

import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Scopes synthetic rebuild losses to the source being restored; they must never enter Curios' delivery queue. */
public final class CuriosRestoreGuard implements AutoCloseable {
    private static final ThreadLocal<CuriosRestoreGuard> ACTIVE = new ThreadLocal<>();
    private final Object handler;
    private final List<ItemStack> rejected = new ArrayList<>();

    private CuriosRestoreGuard(Object handler) {
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested Curios restore is not supported");
        this.handler = handler;
        ACTIVE.set(this);
    }

    public static CuriosRestoreGuard open(Object handler) { return new CuriosRestoreGuard(handler); }

    public static boolean captureRejected(Object handler, ItemStack stack) {
        CuriosRestoreGuard guard = ACTIVE.get();
        if (guard == null || guard.handler != handler) return false;
        if (!stack.isEmpty()) guard.rejected.add(stack.copy());
        return true;
    }

    public List<ItemStack> rejected() { return rejected.stream().map(ItemStack::copy).toList(); }

    @Override public void close() {
        if (ACTIVE.get() != this) throw new IllegalStateException("Curios restore scope changed");
        ACTIVE.remove();
    }

    /** Curios 9.5.1 has no public pending-delivery API. Verify this audited layout before rebuilding. */
    static void requireEmptyDeliveryQueue(Object handler) {
        try {
            if (!handler.getClass().getName().equals("top.theillusivec4.curios.common.capability.CurioInventoryCapability"))
                throw new IllegalStateException("Unverified Curios handler implementation");
            var inventoryField = handler.getClass().getDeclaredField("curioInventory");
            inventoryField.setAccessible(true);
            Object inventory = inventoryField.get(handler);
            var queueField = inventory.getClass().getDeclaredField("invalidStacks");
            queueField.setAccessible(true);
            Object value = queueField.get(inventory);
            if (!(value instanceof List<?> queue)) throw new IllegalStateException("Unverified Curios delivery queue layout");
            if (!queue.isEmpty()) throw new IllegalStateException(
                    "Curios has pre-existing pending items; original queue retained for administrator reconciliation");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Curios delivery queue cannot be inspected safely", failure);
        }
    }
}
