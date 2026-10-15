package dev.yu.mwi.recovery;

import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

public final class RecoveryCheckpoint {
    private static final String RECEIPT = "mwi:recovery_checkpoint";
    private RecoveryCheckpoint() {}
    public static void save(ServerPlayer player, UUID operation) throws IOException {
        player.getPersistentData().putUUID(RECEIPT, operation);
        ListTag inventory = player.getInventory().save(new ListTag());
        player.server.getPlayerList().save(player);
        Path path = player.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getUUID() + ".dat");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
        try (var input = new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {
            CompoundTag saved = NbtIo.read(input, NbtAccounter.create(64L * 1024 * 1024));
            if (input.read() != -1 || !saved.hasUUID("UUID") || !player.getUUID().equals(saved.getUUID("UUID"))
                    || !saved.getCompound("NeoForgeData").hasUUID(RECEIPT)
                    || !operation.equals(saved.getCompound("NeoForgeData").getUUID(RECEIPT))
                    || !inventoryMatches(inventory, saved.getList("Inventory", Tag.TAG_COMPOUND))) {
                throw new IOException("恢复玩家存档回读不一致，领取凭证已保留");
            }
        }
    }
    private static boolean inventoryMatches(ListTag expected, ListTag saved) throws IOException {
        CompoundTag left = new CompoundTag(), right = new CompoundTag(); left.put("items", expected); right.put("items", saved);
        return dev.yu.mwi.storage.NbtChecksums.canonicalSha256(left).equals(dev.yu.mwi.storage.NbtChecksums.canonicalSha256(right));
    }
}
