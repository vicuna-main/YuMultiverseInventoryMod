package dev.yu.mwi.adapter.vanilla;

import com.mojang.serialization.DynamicOps;
import dev.yu.mwi.api.AdapterIds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/** Captures complete effect instances and replaces them without firing add/remove gameplay events. */
public final class PotionEffectsAdapter extends AbstractVanillaAdapter {
    private static final String EFFECTS = "effects";
    private final Map<UUID, Set<Holder<MobEffect>>> removedEffects = new HashMap<>();

    public PotionEffectsAdapter() {
        super(AdapterIds.POTION_EFFECTS);
    }

    public PotionEffectPayloads.RemovedEffects prepareAfterUninstall(CompoundTag payload) {
        return PotionEffectPayloads.prepareAfterUninstall(payload, BuiltInRegistries.MOB_EFFECT::containsKey,
                namespace -> net.neoforged.fml.ModList.get().isLoaded(namespace));
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        List<MobEffectInstance> active = new ArrayList<>(player.getActiveEffects());
        active.sort(Comparator.comparing(PotionEffectsAdapter::effectId));
        ListTag encoded = new ListTag();
        for (MobEffectInstance effect : active) {
            Tag tag = effect.save();
            if (!(tag instanceof CompoundTag)) {
                throw new IllegalStateException("mob effect codec produced non-compound NBT for " + effectId(effect));
            }
            encoded.add(tag);
        }
        CompoundTag data = new CompoundTag();
        data.put(EFFECTS, encoded);
        return PotionEffectPayloads.normalize(data);
    }

    @Override
    public boolean equivalent(CompoundTag first, CompoundTag second) {
        return PotionEffectPayloads.verify(first, second).accepted();
    }

    @Override
    public dev.yu.mwi.api.RestoreVerification verifyRestored(ServerPlayer player, CompoundTag expected,
            CompoundTag actual, HolderLookup.Provider lookup) {
        return PotionEffectPayloads.verify(expected, actual);
    }

    @Override
    public void clear(ServerPlayer player) {
        Map<Holder<MobEffect>, MobEffectInstance> active = player.getActiveEffectsMap();
        Set<Holder<MobEffect>> removed = new HashSet<>(active.keySet());
        removedEffects.put(player.getUUID(), removed);
        for (MobEffectInstance effect : List.copyOf(active.values())) {
            effect.getEffect().value().removeAttributeModifiers(player.getAttributes());
        }
        active.clear();
        player.effectsDirty = true;
        if (!player.getActiveEffects().isEmpty()) {
            throw new IllegalStateException("mob effects could not be cleared for player " + player.getUUID());
        }
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        NbtPayload.requireData(data, id().toString());
        ListTag encoded = NbtPayload.requireCompoundList(PotionEffectPayloads.normalize(data), EFFECTS, id().toString());
        List<MobEffectInstance> decoded = new ArrayList<>(encoded.size());
        Set<Holder<MobEffect>> seen = new HashSet<>();
        DynamicOps<Tag> ops = NbtOps.INSTANCE;
        for (int index = 0; index < encoded.size(); index++) {
            CompoundTag effectTag = encoded.getCompound(index);
            MobEffectInstance effect;
            try {
                effect = MobEffectInstance.CODEC.parse(ops, effectTag).getOrThrow(IllegalArgumentException::new);
            } catch (IllegalArgumentException exception) {
                throw NbtPayload.invalid(id().toString(),
                        "effect entry " + index + " cannot be decoded: " + exception.getMessage());
            }
            if (!seen.add(effect.getEffect())) {
                throw NbtPayload.invalid(id().toString(), "duplicate effect " + effectId(effect));
            }
            decoded.add(effect);
        }

        Map<Holder<MobEffect>, MobEffectInstance> active = player.getActiveEffectsMap();
        if (!active.isEmpty()) {
            throw new IllegalStateException("mob effects must be cleared before apply for player " + player.getUUID());
        }
        for (MobEffectInstance effect : decoded) {
            active.put(effect.getEffect(), effect);
            effect.getEffect().value().addAttributeModifiers(player.getAttributes(), effect.getAmplifier());
        }
        player.effectsDirty = true;
    }

    @Override
    public void sync(ServerPlayer player) {
        Map<Holder<MobEffect>, MobEffectInstance> active = player.getActiveEffectsMap();
        Set<Holder<MobEffect>> removed = removedEffects.remove(player.getUUID());
        if (removed != null) {
            for (Holder<MobEffect> effect : removed) {
                if (!active.containsKey(effect)) {
                    player.connection.send(new ClientboundRemoveMobEffectPacket(player.getId(), effect));
                }
            }
        }
        for (MobEffectInstance effect : active.values()) {
            player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), effect, true));
        }
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        return false;
    }

    @Override
    public void sessionClosed(ServerPlayer player) {
        removedEffects.remove(player.getUUID());
    }

    private static ResourceLocation effectId(MobEffectInstance effect) {
        ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
        if (id == null) {
            throw new IllegalStateException("unregistered mob effect in player state: " + effect.getEffect());
        }
        return id;
    }
}
