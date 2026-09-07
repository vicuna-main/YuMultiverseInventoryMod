package dev.yu.mwi.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

final class AtomicNbtFile {
    private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_TRANSACTION_BYTES = 256L * 1024L * 1024L;

    private AtomicNbtFile() {
    }

    static CompoundTag read(Path file) throws IOException {
        return read(file, MAX_NBT_BYTES);
    }

    static CompoundTag readTransaction(Path file) throws IOException {
        return read(file, MAX_TRANSACTION_BYTES);
    }

    private static CompoundTag read(Path file, long maxBytes) throws IOException {
        try (var input = new java.util.zip.GZIPInputStream(Files.newInputStream(file));
             var data = new java.io.DataInputStream(new java.io.BufferedInputStream(input))) {
            CompoundTag result = NbtIo.read(data, NbtAccounter.create(maxBytes));
            // NBT parsing stops at TAG_End. Read through EOF to validate the gzip trailer/CRC.
            if (data.read() != -1) {
                throw new IOException("Unexpected bytes after root NBT tag");
            }
            return result;
        } catch (net.minecraft.ReportedException failure) {
            if (failure.getCause() instanceof IOException || failure.getCause() instanceof net.minecraft.nbt.NbtException) {
                throw new ProfileCorruptionException(file, "Invalid compressed NBT", failure);
            }
            throw failure;
        } catch (net.minecraft.nbt.NbtException | NegativeArraySizeException failure) {
            throw new ProfileCorruptionException(file, "Invalid NBT structure or size", failure);
        }
    }

    static void write(Path file, CompoundTag data) throws IOException {
        write(file, data, FaultInjector.NONE);
    }

    static void write(Path file, CompoundTag data, FaultInjector faultInjector) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("NBT file has no parent directory: " + file);
        }
        Files.createDirectories(parent);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        NbtIo.writeCompressed(data, encoded);
        writeBytes(file, encoded.toByteArray(), faultInjector);
    }

    static void writeBytes(Path file, byte[] bytes, FaultInjector faultInjector) throws IOException {
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temporary = parent.resolve(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            faultInjector.beforeWrite(temporary, file, bytes.length);
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                if (channel.size() != bytes.length) {
                    throw new IOException("Incomplete atomic NBT write: expected " + bytes.length
                            + " bytes but wrote " + channel.size());
                }
                faultInjector.beforeForce(temporary, file);
                channel.force(true);
            }
            faultInjector.beforeMove(temporary, file);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void deleteDurably(Path file) throws IOException {
        if (Files.deleteIfExists(file) && file.getParent() != null) {
            forceDirectory(file.getParent());
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException unsupported) {
            // Some providers have no directory-fsync primitive; the file data itself was fsynced.
        } catch (IOException failure) {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                throw failure;
            }
            // Windows does not expose directory handles through FileChannel; file data was fsynced.
        }
    }

    interface FaultInjector {
        FaultInjector NONE = new FaultInjector() {
        };

        default void beforeWrite(Path temporary, Path destination, int bytes) throws IOException {
        }

        default void beforeForce(Path temporary, Path destination) throws IOException {
        }

        default void beforeMove(Path temporary, Path destination) throws IOException {
        }
    }
}
