package dev.yu.mwi.adapter.vanilla;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

final class NbtPayload {
    private NbtPayload() {
    }

    static CompoundTag requireData(CompoundTag data, String adapterId) {
        return Objects.requireNonNull(data, "data for " + adapterId);
    }

    static ListTag requireCompoundList(CompoundTag data, String key, String adapterId) {
        if (!data.contains(key, Tag.TAG_LIST)) {
            throw invalid(adapterId, "missing compound list '" + key + "'");
        }
        ListTag list = data.getList(key, Tag.TAG_COMPOUND);
        Tag raw = data.get(key);
        if (!(raw instanceof ListTag rawList) || (!rawList.isEmpty() && rawList.getElementType() != Tag.TAG_COMPOUND)) {
            throw invalid(adapterId, "'" + key + "' must contain compounds");
        }
        return list;
    }

    static ListTag requireStringList(CompoundTag data, String key, String adapterId) {
        if (!data.contains(key, Tag.TAG_LIST)) {
            throw invalid(adapterId, "missing string list '" + key + "'");
        }
        Tag raw = data.get(key);
        if (!(raw instanceof ListTag rawList) || (!rawList.isEmpty() && rawList.getElementType() != Tag.TAG_STRING)) {
            throw invalid(adapterId, "'" + key + "' must contain strings");
        }
        return data.getList(key, Tag.TAG_STRING);
    }

    static int requireInt(CompoundTag data, String key, String adapterId) {
        if (!data.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw invalid(adapterId, "missing integer '" + key + "'");
        }
        return data.getInt(key);
    }

    static long requireLong(CompoundTag data, String key, String adapterId) {
        if (!data.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw invalid(adapterId, "missing long '" + key + "'");
        }
        return data.getLong(key);
    }

    static float requireFloat(CompoundTag data, String key, String adapterId) {
        if (!data.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw invalid(adapterId, "missing float '" + key + "'");
        }
        float value = data.getFloat(key);
        if (!Float.isFinite(value)) {
            throw invalid(adapterId, "'" + key + "' must be finite");
        }
        return value;
    }

    static double requireDouble(CompoundTag data, String key, String adapterId) {
        if (!data.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw invalid(adapterId, "missing double '" + key + "'");
        }
        double value = data.getDouble(key);
        if (!Double.isFinite(value)) {
            throw invalid(adapterId, "'" + key + "' must be finite");
        }
        return value;
    }

    static boolean requireBoolean(CompoundTag data, String key, String adapterId) {
        if (!data.contains(key, Tag.TAG_BYTE)) {
            throw invalid(adapterId, "missing boolean '" + key + "'");
        }
        return data.getBoolean(key);
    }

    static String requireString(CompoundTag data, String key, String adapterId) {
        if (!data.contains(key, Tag.TAG_STRING)) {
            throw invalid(adapterId, "missing string '" + key + "'");
        }
        String value = data.getString(key);
        if (value.isBlank()) {
            throw invalid(adapterId, "'" + key + "' must not be blank");
        }
        return value;
    }

    static IllegalArgumentException invalid(String adapterId, String detail) {
        return new IllegalArgumentException("invalid " + adapterId + " payload: " + detail);
    }
}
