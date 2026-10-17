package dev.yu.mwi.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Profile checksum helpers.
 *
 * <p>The 1.0.0 storage format originally checksummed the binary serialization of an in-memory
 * {@link CompoundTag}. Compound tags are maps, so their iteration order is not a stable part of NBT
 * semantics. A payload could therefore be intact on disk while producing a different digest after
 * being read back. The canonical algorithm below sorts compound keys recursively and preserves list
 * order, making the digest independent of map iteration order.</p>
 */
public final class NbtChecksums {
    static final String CANONICAL_ALGORITHM = "canonical_nbt_sha256_v1";

    private static final int MAX_DEPTH = 512;
    private static final int MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024;
    private static final byte[] CANONICAL_HEADER = "MWI-CANONICAL-NBT-V1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private NbtChecksums() {
    }

    public static String canonicalSha256(CompoundTag payload) throws IOException {
        return canonicalDigest(payload).sha256();
    }

    static Digest canonicalDigest(CompoundTag payload) throws IOException {
        MessageDigest digest = sha256Digest();
        CountingOutputStream counter = new CountingOutputStream(OutputStream.nullOutputStream());
        try (DataOutputStream output = new DataOutputStream(
                new DigestOutputStream(counter, digest))) {
            output.write(CANONICAL_HEADER);
            writeCanonical(payload, output, 0);
        }
        return new Digest(HexFormat.of().formatHex(digest.digest()), counter.count());
    }

    /** The unstable checksum implementation used by older profile files. */
    static String legacySha256(CompoundTag payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(payload, output);
        }
        return sha256(bytes.toByteArray());
    }

    /**
     * Computes the old checksum from the payload bytes exactly as they are stored inside the
     * compressed profile document. This allows a legacy file to be proven intact even when parsing
     * and reserializing its compound maps changes their iteration order.
     */
    static String legacySha256OfStoredPayload(Path profileFile) throws IOException {
        return legacySha256OfStoredPayload(AtomicNbtFile.snapshot(profileFile, false).bytes());
    }

    static String legacySha256OfStoredPayload(byte[] compressed) throws IOException {
        byte[] uncompressed = readGzipLimited(compressed);
        ByteArrayInputStream source = new ByteArrayInputStream(uncompressed);
        try (DataInputStream input = new DataInputStream(source)) {
            int rootType = input.readUnsignedByte();
            if (rootType != Tag.TAG_COMPOUND) {
                throw new IOException("Profile root is not a compound tag");
            }
            input.readUTF();

            int payloadStart = -1;
            int payloadEnd = -1;
            while (true) {
                int type = input.readUnsignedByte();
                if (type == Tag.TAG_END) {
                    break;
                }
                String name = input.readUTF();
                int valueStart = position(uncompressed, source);
                skipPayload(input, type, 0);
                int valueEnd = position(uncompressed, source);
                if ("payload".equals(name)) {
                    if (type != Tag.TAG_COMPOUND) {
                        throw new IOException("Profile payload is not a compound tag");
                    }
                    if (payloadStart >= 0) {
                        throw new IOException("Profile contains duplicate payload tags");
                    }
                    payloadStart = valueStart;
                    payloadEnd = valueEnd;
                }
            }
            if (payloadStart < 0) {
                throw new IOException("Profile payload tag is missing");
            }
            if (source.available() != 0) {
                throw new IOException("Profile contains trailing uncompressed NBT data");
            }

            ByteArrayOutputStream root = new ByteArrayOutputStream(payloadEnd - payloadStart + 3);
            try (DataOutputStream output = new DataOutputStream(root)) {
                output.writeByte(Tag.TAG_COMPOUND);
                output.writeUTF("");
                output.write(uncompressed, payloadStart, payloadEnd - payloadStart);
            }
            return sha256(root.toByteArray());
        } catch (EOFException truncated) {
            throw new IOException("Profile NBT is truncated", truncated);
        }
    }

    private static void writeCanonical(Tag tag, DataOutputStream output, int depth) throws IOException {
        if (tag == null) {
            throw new IOException("Canonical NBT contains a null tag");
        }
        if (depth > MAX_DEPTH) {
            throw new IOException("Canonical NBT exceeds maximum nesting depth");
        }

        output.writeByte(tag.getId());
        if (tag instanceof CompoundTag compound) {
            List<String> keys = new ArrayList<>(compound.getAllKeys());
            keys.sort(String::compareTo);
            output.writeInt(keys.size());
            for (String key : keys) {
                output.writeUTF(key);
                Tag child = compound.get(key);
                if (child == null) {
                    throw new IOException("Canonical NBT key has no value: " + key);
                }
                writeCanonical(child, output, depth + 1);
            }
            return;
        }
        if (tag instanceof ListTag list) {
            output.writeInt(list.size());
            for (int index = 0; index < list.size(); index++) {
                writeCanonical(list.get(index), output, depth + 1);
            }
            return;
        }

        // Every remaining vanilla NBT type has a deterministic binary encoding. Wrapping one leaf
        // under a single fixed compound key avoids depending on concrete mapped tag accessor names.
        ByteArrayOutputStream leafBytes = new ByteArrayOutputStream();
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("value", tag);
        try (DataOutputStream leafOutput = new DataOutputStream(leafBytes)) {
            NbtIo.write(wrapper, leafOutput);
        }
        byte[] encoded = leafBytes.toByteArray();
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static byte[] readGzipLimited(byte[] bytes) throws IOException {
        try (InputStream raw = new ByteArrayInputStream(bytes);
             GZIPInputStream gzip = new GZIPInputStream(raw);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = gzip.read(buffer)) >= 0) {
                total = Math.addExact(total, read);
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new IOException("Profile exceeds maximum decompressed NBT size");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (ArithmeticException overflow) {
            throw new IOException("Profile decompressed size overflow", overflow);
        }
    }

    private static void skipPayload(DataInputStream input, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("Profile NBT exceeds maximum nesting depth");
        }
        switch (type) {
            case Tag.TAG_END -> {
                // Only valid as a compound terminator or an empty list element type.
            }
            case Tag.TAG_BYTE -> skipFully(input, 1);
            case Tag.TAG_SHORT -> skipFully(input, 2);
            case Tag.TAG_INT, Tag.TAG_FLOAT -> skipFully(input, 4);
            case Tag.TAG_LONG, Tag.TAG_DOUBLE -> skipFully(input, 8);
            case Tag.TAG_BYTE_ARRAY -> skipArray(input, 1);
            case Tag.TAG_STRING -> input.readUTF();
            case Tag.TAG_LIST -> {
                int elementType = input.readUnsignedByte();
                int length = input.readInt();
                requireNonNegative(length, "list");
                if (elementType == Tag.TAG_END && length != 0) {
                    throw new IOException("Non-empty NBT list has TAG_End element type");
                }
                for (int index = 0; index < length; index++) {
                    skipPayload(input, elementType, depth + 1);
                }
            }
            case Tag.TAG_COMPOUND -> {
                while (true) {
                    int childType = input.readUnsignedByte();
                    if (childType == Tag.TAG_END) {
                        break;
                    }
                    input.readUTF();
                    skipPayload(input, childType, depth + 1);
                }
            }
            case Tag.TAG_INT_ARRAY -> skipArray(input, 4);
            case Tag.TAG_LONG_ARRAY -> skipArray(input, 8);
            default -> throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static void skipArray(DataInputStream input, int elementBytes) throws IOException {
        int length = input.readInt();
        requireNonNegative(length, "array");
        long bytes;
        try {
            bytes = Math.multiplyExact((long) length, elementBytes);
        } catch (ArithmeticException overflow) {
            throw new IOException("NBT array size overflow", overflow);
        }
        skipFully(input, bytes);
    }

    private static void skipFully(DataInputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                throw new EOFException("NBT stream ended unexpectedly");
            }
            remaining--;
        }
    }

    private static void requireNonNegative(int value, String kind) throws IOException {
        if (value < 0) {
            throw new IOException("Negative NBT " + kind + " length");
        }
    }

    private static int position(byte[] sourceBytes, ByteArrayInputStream source) {
        return sourceBytes.length - source.available();
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record Digest(String sha256, long canonicalBytes) {
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        private CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            count = Math.addExact(count, length);
        }

        private long count() {
            return count;
        }
    }
}
