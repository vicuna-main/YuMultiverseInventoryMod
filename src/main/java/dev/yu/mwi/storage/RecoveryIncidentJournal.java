package dev.yu.mwi.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable incidents are separate from immutable forensic inputs. Access under the player's lock. */
final class RecoveryIncidentJournal {
    private final Path root;
    private final AtomicNbtFile.FaultInjector faults;

    RecoveryIncidentJournal(Path root, AtomicNbtFile.FaultInjector faults) {
        this.root = root;
        this.faults = faults;
    }

    CompoundTag read(UUID owner) throws IOException {
        Path file = path(owner);
        if (!Files.exists(file)) {
            CompoundTag empty = new CompoundTag();
            empty.putInt("schema_version", 1);
            empty.putUUID("player_uuid", owner);
            empty.putLong("generation", 0);
            empty.put("incidents", new ListTag());
            return empty;
        }
        CompoundTag document = AtomicNbtFile.read(file);
        if (document.getInt("schema_version") != 1 || !document.hasUUID("player_uuid")
                || !owner.equals(document.getUUID("player_uuid")) || document.getLong("generation") < 0) {
            throw new ProfileCorruptionException(file, "Invalid automatic recovery journal");
        }
        verify(file, document);
        return document;
    }

    void failed(UUID owner, Path source, IOException failure) throws IOException {
        CompoundTag document = read(owner);
        String relative = relative(source);
        AtomicNbtFile.Snapshot snapshot = snapshot(failure);
        String hash = snapshot == null ? "unavailable" : snapshot.sha256();
        if (snapshot != null) {
            Path evidence = root.resolve("diagnostics/inputs").resolve(owner.toString()).resolve(hash + ".bin");
            if (!Files.exists(evidence)) AtomicNbtFile.writeBytes(evidence, snapshot.bytes(), faults);
        }
        ListTag entries = document.getList("incidents", Tag.TAG_COMPOUND);
        CompoundTag incident = null;
        for (Tag tag : entries) {
            CompoundTag candidate = (CompoundTag) tag;
            if (relative.equals(candidate.getString("source"))) { incident = candidate; break; }
        }
        long now = Instant.now().toEpochMilli();
        if (incident == null) {
            incident = new CompoundTag();
            incident.putString("source", relative);
            incident.putLong("first_failure", now);
            entries.add(incident);
        }
        if (!hash.equals(incident.getString("input_sha256")) || "RESOLVED".equals(incident.getString("state"))) {
            document.putLong("generation", Math.addExact(document.getLong("generation"), 1));
        }
        incident.putString("input_sha256", hash);
        incident.putString("state", "WAITING_FOR_SOURCE");
        incident.putString("failure_type", failure.getClass().getSimpleName());
        incident.putString("reason", String.valueOf(failure.getMessage()));
        incident.putLong("last_failure", now);
        incident.putInt("attempts", Math.incrementExact(incident.getInt("attempts")));
        document.put("incidents", entries);
        write(owner, document);
    }

    void resolved(UUID owner, Path source, String result) throws IOException {
        CompoundTag document = read(owner);
        boolean changed = false;
        for (Tag tag : document.getList("incidents", Tag.TAG_COMPOUND)) {
            CompoundTag incident = (CompoundTag) tag;
            if (relative(source).equals(incident.getString("source")) && !"RESOLVED".equals(incident.getString("state"))) {
                incident.putString("state", "RESOLVED");
                incident.putString("result", result);
                incident.putLong("resolved_at", Instant.now().toEpochMilli());
                changed = true;
            }
        }
        if (changed) write(owner, document);
    }

    List<Path> unresolved(UUID owner) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Tag tag : read(owner).getList("incidents", Tag.TAG_COMPOUND)) {
            CompoundTag incident = (CompoundTag) tag;
            if (!"RESOLVED".equals(incident.getString("state"))) {
                Path source = root.resolve(incident.getString("source")).normalize();
                if (!source.startsWith(root)) throw new IOException("Incident source escapes storage root");
                result.add(source);
            }
        }
        return result;
    }

    void preserveLive(UUID owner, UUID session, CompoundTag snapshot) throws IOException {
        Path file = root.resolve("diagnostics/recovery-sessions").resolve(owner.toString()).resolve(session + ".dat");
        if (Files.exists(file)) return;
        CompoundTag document = new CompoundTag();
        document.putUUID("player_uuid", owner);
        document.putUUID("session", session);
        document.put("adapters", snapshot.copy());
        seal(document);
        AtomicNbtFile.write(file, document, faults);
    }

    static void seal(CompoundTag document) throws IOException {
        document.remove("recovery_digest");
        document.putString("recovery_digest", NbtChecksums.canonicalSha256(document));
    }

    static void verify(Path file, CompoundTag document) throws IOException {
        CompoundTag copy = document.copy();
        String expected = copy.getString("recovery_digest");
        copy.remove("recovery_digest");
        if (!expected.equals(NbtChecksums.canonicalSha256(copy)))
            throw new ProfileCorruptionException(file, "Automatic recovery checksum mismatch");
    }

    private void write(UUID owner, CompoundTag document) throws IOException {
        seal(document);
        AtomicNbtFile.write(path(owner), document, faults);
    }

    private Path path(UUID owner) { return root.resolve("recovery_incidents").resolve(owner + ".dat"); }
    private String relative(Path source) throws IOException {
        Path normalized = source.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("Incident source escapes storage root");
        return root.relativize(normalized).toString();
    }
    private static AtomicNbtFile.Snapshot snapshot(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AtomicNbtFile.SnapshotFailure input) return input.snapshot();
        }
        return null;
    }
}
