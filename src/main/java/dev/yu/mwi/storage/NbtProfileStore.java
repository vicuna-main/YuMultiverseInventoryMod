package dev.yu.mwi.storage;

import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.GameModeBucket;
import dev.yu.mwi.domain.ScopeKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One compressed NBT file per share. Writes are atomic and checksummed; unchanged payloads are not rewritten.
 */
public final class NbtProfileStore implements ProfileStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(NbtProfileStore.class);
    private static final int SCHEMA_VERSION = 1;
    private static final int INDEX_SCHEMA_VERSION = 2;
    private static final String INDEX_FILE = "index.dat";
    private static final String ENVELOPE_ALGORITHM_V1 = "canonical_profile_envelope_sha256_v1";
    private static final String ENVELOPE_ALGORITHM = "profile_metadata_and_payload_sha256_v2";
    private static final String DOCUMENT_ALGORITHM = "canonical_document_sha256_v1";
    private static final String TRANSACTION_ALGORITHM = "profile_manifest_sha256_v2";
    private static final long MAX_PROFILE_BYTES = 64L * 1024L * 1024L;

    private final Path root;
    private final int maxRevisions;
    private final WeightedLruCache<ProfileKey, StoredProfile> cache;
    private final int maxCacheEntries;
    private final long maxCacheBytes;
    private final FaultInjector faultInjector;
    private final Map<UUID, RevisionState> revisionStates = new ConcurrentHashMap<>();
    private final Map<UUID, ProfileCorruptionException> blockedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Path> pendingPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> persistenceLocks = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.Set<ProfileKey>> startupResetTargets = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> canonicalRecoveryPlayers = ConcurrentHashMap.newKeySet();
    private final boolean recoveredFromUncleanShutdown;
    private final AtomicBoolean cleanCloseAllowed = new AtomicBoolean(true);
    private volatile boolean cleanShutdown;

    public NbtProfileStore(Path worldRoot, int maxRevisions, int maxCacheEntries) throws IOException {
        this(worldRoot, maxRevisions, maxCacheEntries, 128);
    }

    public NbtProfileStore(
            Path worldRoot,
            int maxRevisions,
            int maxCacheEntries,
            int maxCacheMegabytes) throws IOException {
        this(worldRoot, maxRevisions, maxCacheEntries, maxCacheMegabytes, FaultInjector.NONE);
    }

    NbtProfileStore(
            Path worldRoot,
            int maxRevisions,
            int maxCacheEntries,
            int maxCacheMegabytes,
            FaultInjector faultInjector) throws IOException {
        if (maxRevisions < 1) {
            throw new IllegalArgumentException("maxRevisions must be positive");
        }
        if (maxCacheEntries < 1) {
            throw new IllegalArgumentException("maxCacheEntries must be positive");
        }
        if (maxCacheMegabytes < 1) {
            throw new IllegalArgumentException("maxCacheMegabytes must be positive");
        }
        this.root = worldRoot.resolve("data").resolve("mwi").toAbsolutePath().normalize();
        this.maxRevisions = maxRevisions;
        this.maxCacheEntries = maxCacheEntries;
        this.maxCacheBytes = Math.multiplyExact((long) maxCacheMegabytes, 1024L * 1024L);
        this.faultInjector = java.util.Objects.requireNonNull(faultInjector, "faultInjector");
        this.cache = new WeightedLruCache<>(maxCacheEntries, maxCacheBytes);
        Files.createDirectories(root);
        loadQuarantineMarkers();
        recoverPendingTransactions();
        boolean previousShutdownClean = loadIndex();
        boolean recoveredPreparedRevision = recoverOrphanedPreparedRevisions();
        initializeRevisionStates();
        this.recoveredFromUncleanShutdown = !previousShutdownClean || recoveredPreparedRevision;
        this.cleanShutdown = false;
        writeIndex();
    }

    @Override
    public Optional<StoredProfile> load(ProfileKey key) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(key.playerId());
        try {
            return loadLocked(key);
        } finally {
            lock.unlock();
        }
    }

    private Optional<StoredProfile> loadLocked(ProfileKey key) throws IOException {
        requireHealthyPlayer(key.playerId());
        StoredProfile cached = cache.get(java.util.Objects.requireNonNull(key, "key"));
        if (cached != null) {
            return Optional.of(copy(cached));
        }
        Path file = profilePath(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        faultInjector.beforeProfileRead(file, key);
        DecodedProfile decoded = readCanonicalProfile(file, key, true);
        StoredProfile selected = putCache(key, decoded.profile(), decoded.canonicalBytes());
        return Optional.of(copy(selected));
    }

    @Override
    public Map<ProfileKey, StoredProfile> loadPlayerProfiles(UUID playerId) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(playerId);
        try {
            return readPlayerProfiles(playerId, true, true);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<ProfileKey, StoredProfile> inspectPlayerProfiles(UUID playerId) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(playerId);
        try {
            return readPlayerProfiles(playerId, false, false);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public PlayerProfileIds listPlayerProfileIds() throws IOException {
        Path profiles = root.resolve("profiles");
        if (!Files.isDirectory(profiles)) {
            return new PlayerProfileIds(List.of());
        }
        List<UUID> result = new ArrayList<>();
        try (var directories = Files.list(profiles)) {
            for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
                try {
                    result.add(UUID.fromString(directory.getFileName().toString()));
                } catch (IllegalArgumentException invalid) {
                    throw new ProfileCorruptionException(directory,
                            "Profile directory is not a player UUID", invalid);
                }
            }
        }
        return new PlayerProfileIds(result);
    }

    private Map<ProfileKey, StoredProfile> readPlayerProfiles(
            UUID playerId, boolean populateCache, boolean migrateMetadata) throws IOException {
        requireHealthyPlayer(playerId);
        Path playerRoot = root.resolve("profiles").resolve(playerId.toString());
        if (!Files.isDirectory(playerRoot)) {
            return Map.of();
        }
        LinkedHashMap<ProfileKey, StoredProfile> profiles = new LinkedHashMap<>();
        try (var paths = Files.walk(playerRoot)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> isCanonicalProfilePath(playerId, path))
                    .sorted()
                    .toList()) {
                ProfileKey key = keyFromPath(playerId, file);
                DecodedProfile decoded = readCanonicalProfile(file, key, migrateMetadata);
                if (populateCache) {
                    putCache(key, decoded.profile(), decoded.canonicalBytes());
                }
                profiles.put(key, copy(decoded.profile()));
            }
        }
        return Map.copyOf(profiles);
    }

    @Override
    public SaveResult save(ProfileKey key, CompoundTag payload, long revision) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(key.playerId());
        try {
            return saveLocked(key, payload, revision);
        } finally {
            lock.unlock();
        }
    }

    private SaveResult saveLocked(ProfileKey key, CompoundTag payload, long revision) throws IOException {
        requireNoPendingWrites(key.playerId());
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        CompoundTag storedPayload = payload.copy();
        NbtChecksums.Digest digest = NbtChecksums.canonicalDigest(storedPayload);
        requireProfileSize(digest.canonicalBytes());
        String checksum = digest.sha256();
        Optional<StoredProfile> existing = load(key);
        if (existing.isPresent() && checksum.equals(existing.get().checksum())) {
            return SaveResult.UNCHANGED;
        }

        Instant now = Instant.now();
        CompoundTag document = profileDocument(key, storedPayload, revision, now, checksum);
        AtomicNbtFile.write(profilePath(key), document);
        putCache(key, new StoredProfile(payload, revision, now, checksum), digest.canonicalBytes());
        advanceRevisionCounter(key.playerId(), revision);
        return SaveResult.WRITTEN;
    }

    @Override
    public void commitBatch(
            UUID playerId,
            long revision,
            Map<ProfileKey, CompoundTag> profiles,
            Optional<CompoundTag> completedJournal) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(playerId);
        try {
            commitBatchLocked(playerId, revision, profiles, completedJournal, List.of(), Optional.empty());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void commitBatch(
            UUID playerId,
            long revision,
            Map<ProfileKey, CompoundTag> profiles,
            Optional<CompoundTag> completedJournal,
            List<ProfileArchive> archives) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(playerId);
        try {
            commitBatchLocked(playerId, revision, profiles, completedJournal, List.copyOf(archives), Optional.empty());
        } finally {
            lock.unlock();
        }
    }

    private void commitBatchLocked(
            UUID playerId,
            long revision,
            Map<ProfileKey, CompoundTag> profiles,
            Optional<CompoundTag> completedJournal,
            List<ProfileArchive> archives, Optional<RecoveryMutation> recovery) throws IOException {
        requireNoPendingWrites(playerId);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        requireHealthyPlayer(playerId);
        Optional<CompoundTag> recoveryWrite = recovery.isPresent()
                ? Optional.of(prepareRecovery(playerId, revision, recovery.orElseThrow())) : Optional.empty();
        ListTag writes = new ListTag();
        Instant savedAt = Instant.now();
        for (Map.Entry<ProfileKey, CompoundTag> entry : profiles.entrySet()) {
            ProfileKey key = entry.getKey();
            if (!key.playerId().equals(playerId)) {
                throw new IllegalArgumentException("batch contains a profile for another player");
            }
            CompoundTag payload = entry.getValue().copy();
            NbtChecksums.Digest digest = NbtChecksums.canonicalDigest(payload);
            requireProfileSize(digest.canonicalBytes());
            Optional<StoredProfile> existing = load(key);
            if (existing.isPresent()) {
                StoredProfile current = existing.orElseThrow();
                if (current.revision() > revision) {
                    throw new IOException("Refusing stale revision " + revision + " for " + key
                            + "; current revision is " + current.revision());
                }
                if (current.revision() == revision && !digest.sha256().equals(current.checksum())) {
                    throw new IOException("Revision collision for " + key + " at " + revision);
                }
                if (digest.sha256().equals(current.checksum())) {
                    continue;
                }
            }
            CompoundTag write = new CompoundTag();
            write.put("key", encodeKey(key));
            write.put("document", profileDocument(key, payload, revision, savedAt, digest.sha256()));
            write.putLong("canonical_bytes", digest.canonicalBytes());
            writes.add(write);
        }
        ListTag archiveWrites = prepareArchiveWrites(playerId, revision, archives, profiles);
        if (writes.isEmpty() && archiveWrites.isEmpty() && recoveryWrite.isEmpty()) {
            if (completedJournal.isPresent()) {
                writeRevision(playerId, revision, completedJournal.orElseThrow());
            }
            return;
        }

        CompoundTag transaction = new CompoundTag();
        transaction.putInt("schema_version", SCHEMA_VERSION);
        transaction.putUUID("player_uuid", playerId);
        transaction.putLong("revision", revision);
        transaction.put("profiles", writes);
        if (!archiveWrites.isEmpty()) {
            transaction.put("archives", archiveWrites);
        }
        completedJournal.ifPresent(journal -> transaction.put("journal", journal.copy()));
        recoveryWrite.ifPresent(document -> transaction.put("recovery", document));
        addTransactionChecksum(transaction);
        Path transactionFile = transactionPath(playerId, revision);
        boolean walWritten = false;
        try {
            AtomicNbtFile.write(transactionFile, transaction, faultInjector);
            walWritten = true;
            faultInjector.transactionDurable(transactionFile);
            applyPendingTransaction(transactionFile, transaction, true);
        } catch (IOException | RuntimeException failure) {
            if (walWritten || Files.exists(transactionFile)) {
                cleanCloseAllowed.set(false);
                pendingPlayers.put(playerId, transactionFile);
                throw new PendingTransactionException(transactionFile, failure);
            }
            throw failure;
        }
    }

    private ListTag prepareArchiveWrites(
            UUID playerId, long revision, List<ProfileArchive> archives, Map<ProfileKey, CompoundTag> profiles) throws IOException {
        ListTag writes = new ListTag();
        java.util.Set<String> destinations = new HashSet<>();
        for (ProfileArchive request : archives) {
            ProfileKey key = request.key();
            if (!key.playerId().equals(playerId)) {
                throw new IllegalArgumentException("archive batch contains a profile for another player");
            }
            String category = requireSegment(request.category(), "category");
            String destination = archivePath(key, category, revision).toString();
            if (!destinations.add(destination)) {
                continue;
            }
            StoredProfile source = load(key).orElseThrow(
                    () -> new IOException("Cannot archive missing profile " + key));
            CompoundTag write = new CompoundTag();
            write.put("key", encodeKey(key));
            write.putString("category", category);
            write.put("document", archiveDocument(key, category, request.reason(), revision, source, request.details()));
            writes.add(write);
        }
        return writes;
    }

    @Override
    public void delete(ProfileKey key) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(key.playerId());
        try {
            Files.deleteIfExists(profilePath(key));
            removeCache(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void invalidate(ProfileKey key) {
        removeCache(java.util.Objects.requireNonNull(key, "key"));
    }

    @Override
    public void invalidatePlayer(UUID playerId) {
        java.util.Objects.requireNonNull(playerId, "playerId");
        cache.removeIf(key -> key.playerId().equals(playerId));
    }

    @Override
    public long nextRevision(UUID playerId) {
        if (blockedPlayers.containsKey(playerId)) {
            throw new IllegalStateException("MWI player data is quarantined: " + playerId, blockedPlayers.get(playerId));
        }
        RevisionState state = revisionStates.computeIfAbsent(
                java.util.Objects.requireNonNull(playerId, "playerId"), ignored -> new RevisionState(0L));
        return state.allocate();
    }

    @Override
    public void writeRevision(UUID playerId, long revision, CompoundTag journal) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(playerId);
        try {
            writeRevisionLocked(playerId, revision, journal);
            advanceRevisionCounter(playerId, revision);
        } finally {
            lock.unlock();
        }
    }

    private void writeRevisionLocked(UUID playerId, long revision, CompoundTag journal) throws IOException {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        CompoundTag document = new CompoundTag();
        document.putInt("schema_version", SCHEMA_VERSION);
        document.putUUID("player_uuid", playerId);
        document.putLong("revision", revision);
        document.putLong("updated_at", Instant.now().toEpochMilli());
        document.put("journal", journal.copy());
        addDocumentChecksum(document, "revision");
        AtomicNbtFile.write(revisionPath(playerId, revision), document);
        pruneRevisions(playerId);
    }

    @Override
    public Optional<CompoundTag> loadRevision(UUID playerId, long revision) throws IOException {
        Path file = revisionPath(playerId, revision);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        CompoundTag document = AtomicNbtFile.read(file);
        if (document.getInt("schema_version") != SCHEMA_VERSION
                || !document.hasUUID("player_uuid") || !playerId.equals(document.getUUID("player_uuid"))
                || document.getLong("revision") != revision || !document.contains("journal", Tag.TAG_COMPOUND)) {
            throw new ProfileCorruptionException(file, "Revision metadata does not match its path");
        }
        verifyOrMigrateDocumentChecksum(file, document, "revision");
        return Optional.of(document.getCompound("journal").copy());
    }

    @Override
    public List<Long> listRevisions(UUID playerId) throws IOException {
        Path directory = root.resolve("revisions").resolve(playerId.toString());
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".dat"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .map(NbtProfileStore::parseLong)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
    }

    @Override
    public List<WorldProfile> listWorldProfiles(
            UUID playerId, GameModeBucket gameMode, String shareId) throws IOException {
        requireHealthyPlayer(playerId);
        Path worldScopes = root.resolve("profiles").resolve(playerId.toString()).resolve("world");
        if (!Files.isDirectory(worldScopes)) {
            return List.of();
        }
        List<WorldProfile> result = new ArrayList<>();
        String encodedShare = encode(shareId) + ".dat";
        try (var scopeDirectories = Files.list(worldScopes)) {
            for (Path scopeDirectory : scopeDirectories.filter(Files::isDirectory).toList()) {
                Path file = scopeDirectory.resolve(gameMode.name().toLowerCase(Locale.ROOT)).resolve(encodedShare);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                if (!isCanonicalProfilePath(playerId, file)) {
                    continue;
                }
                ProfileKey key = keyFromPath(playerId, file);
                String worldId = key.scope().id();
                DecodedProfile decoded = readCanonicalProfile(file, key, true);
                putCache(key, decoded.profile(), decoded.canonicalBytes());
                result.add(new WorldProfile(worldId, copy(decoded.profile())));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void archive(ProfileKey key, String category, String reason, long revision) throws IOException {
        archive(key, category, reason, revision, new CompoundTag());
    }

    @Override
    public void archive(
            ProfileKey key, String category, String reason, long revision, CompoundTag details) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(key.playerId());
        try {
            StoredProfile profile = load(key).orElse(null);
            if (profile == null) {
                return;
            }
            CompoundTag archived = archiveDocument(key, category, reason, revision, profile, details);
            AtomicNbtFile.write(archivePath(key, category, revision), archived, faultInjector);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<CompatibilityArchiveEntry> listCompatibilityArchives(UUID playerId)
            throws IOException {
        Path playerArchives = root.resolve("archives").resolve("compatibility")
                .resolve("missing_items").resolve(playerId.toString());
        if (!Files.isDirectory(playerArchives)) {
            return List.of();
        }
        List<CompatibilityArchiveEntry> result = new ArrayList<>();
        try (var files = Files.walk(playerArchives)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                CompoundTag document = AtomicNbtFile.read(file);
                if (!document.hasUUID("player_uuid")
                        || !playerId.equals(document.getUUID("player_uuid"))
                        || !document.contains("source_key", Tag.TAG_COMPOUND)
                        || !document.contains("details", Tag.TAG_COMPOUND)) {
                    throw new ProfileCorruptionException(file, "Compatibility archive metadata is invalid");
                }
                ProfileKey key = decodeKey(document.getCompound("source_key"));
                if (!key.playerId().equals(playerId)
                        || !document.contains("payload", Tag.TAG_COMPOUND)
                        || !document.getString("source_checksum").equals(
                                NbtChecksums.canonicalSha256(document.getCompound("payload")))) {
                    throw new ProfileCorruptionException(file,
                            "Compatibility archive payload checksum is invalid");
                }
                CompoundTag details = document.getCompound("details");
                ListTag issues = details.getList("entries", Tag.TAG_COMPOUND);
                for (int index = 0; index < issues.size(); index++) {
                    String id = document.getLong("archive_revision") + "-"
                            + Integer.toUnsignedString(key.toString().hashCode(), 36) + '-' + index;
                    result.add(new CompatibilityArchiveEntry(
                            id, key, document.getLong("archive_revision"), issues.getCompound(index), document));
                }
            }
        }
        return List.copyOf(result);
    }

    private static CompoundTag archiveDocument(
            ProfileKey key,
            String category,
            String reason,
            long archiveRevision,
            StoredProfile profile,
            CompoundTag details) {
        CompoundTag archived = new CompoundTag();
        archived.putInt("schema_version", 1);
        archived.putUUID("player_uuid", key.playerId());
        archived.put("source_key", encodeKey(key));
        archived.putString("category", requireSegment(category, "category"));
        archived.putString("reason", reason == null ? "" : reason);
        archived.putLong("archive_revision", archiveRevision);
        archived.putLong("archived_at", Instant.now().toEpochMilli());
        archived.putLong("source_revision", profile.revision());
        archived.putString("source_checksum", profile.checksum());
        archived.put("payload", profile.payload());
        if (!details.isEmpty()) {
            archived.put("details", details.copy());
        }
        return archived;
    }

    @Override
    public void writeLostFound(
            UUID playerId, String shareId, CompoundTag payload, String reason, long revision) throws IOException {
        CompoundTag document = new CompoundTag();
        document.putString("share_id", shareId);
        document.putString("reason", reason == null ? "" : reason);
        document.putLong("revision", revision);
        document.putLong("saved_at", Instant.now().toEpochMilli());
        document.put("payload", payload);
        Path file = root.resolve("lost_found").resolve(playerId.toString())
                .resolve(revision + "-" + encode(shareId) + ".dat");
        AtomicNbtFile.write(file, document);
    }

    @Override
    public CacheStats cacheStats() {
        return cache.stats();
    }

    @Override
    public boolean recoveredFromUncleanShutdown() {
        return recoveredFromUncleanShutdown;
    }

    @Override
    public boolean requiresCanonicalRecovery(UUID playerId) {
        return canonicalRecoveryPlayers.contains(playerId);
    }

    @Override
    public boolean shouldResetLive(ProfileKey key) {
        return startupResetTargets.getOrDefault(key.playerId(), java.util.Set.of()).contains(key);
    }

    @Override
    public void completeStartupRecovery(UUID playerId) {
        canonicalRecoveryPlayers.remove(playerId);
        startupResetTargets.remove(playerId);
    }

    @Override
    public void markUncleanShutdown() {
        cleanCloseAllowed.set(false);
    }

    @Override
    public void close() throws IOException {
        cleanShutdown = cleanCloseAllowed.get();
        writeIndex();
    }

    private DecodedProfile decodeProfile(Path file, ProfileKey key, CompoundTag document) throws IOException {
        return decodeProfile(file, key, document, true);
    }

    private DecodedProfile decodeProfile(
            Path file, ProfileKey key, CompoundTag document, boolean migrateMetadata) throws IOException {
        if (document.getInt("schema_version") != SCHEMA_VERSION) {
            throw new ProfileCorruptionException(file, "Unsupported profile schema " + document.getInt("schema_version"));
        }
        if (!document.hasUUID("player_uuid") || !key.playerId().equals(document.getUUID("player_uuid"))
                || !key.scope().type().name().equalsIgnoreCase(document.getString("scope_type"))
                || !key.scope().id().equals(document.getString("scope_id"))
                || !key.gameMode().name().equalsIgnoreCase(document.getString("game_mode"))
                || !key.shareId().equals(document.getString("share_id"))) {
            throw new ProfileCorruptionException(file, "Profile metadata does not match its path");
        }
        if (!document.contains("payload", Tag.TAG_COMPOUND)) {
            throw new ProfileCorruptionException(file, "Profile payload is missing");
        }
        CompoundTag payload = document.getCompound("payload");
        String expected = document.getString("checksum");
        if (expected.isBlank()) {
            throw new ProfileCorruptionException(file, "Profile checksum is missing");
        }

        String algorithm = document.getString("checksum_algorithm");
        NbtChecksums.Digest digest = NbtChecksums.canonicalDigest(payload);
        requireProfileSize(digest.canonicalBytes());
        String canonical = digest.sha256();
        if (NbtChecksums.CANONICAL_ALGORITHM.equals(algorithm)) {
            if (!expected.equals(canonical)) {
                throw new ProfileCorruptionException(file, "Profile checksum mismatch");
            }
        } else if (algorithm.isBlank()) {
            boolean verifiedByReserialization = expected.equals(NbtChecksums.legacySha256(payload));
            boolean verifiedByStoredBytes = false;
            if (!verifiedByReserialization) {
                verifiedByStoredBytes = expected.equals(NbtChecksums.legacySha256OfStoredPayload(file));
            }
            if (!verifiedByReserialization && !verifiedByStoredBytes) {
                throw new ProfileCorruptionException(file, "Profile checksum mismatch");
            }
            if (migrateMetadata) {
                Path backup = null;
                if (verifiedByStoredBytes) {
                    backup = archiveLegacyChecksumProfile(file);
                }
                migrateChecksum(file, document, payload, canonical);
                if (backup != null) {
                    LOGGER.warn("Recovered legacy profile with unstable checksum ordering: {}. "
                            + "Migrated to {} and preserved the original at {}",
                            file, NbtChecksums.CANONICAL_ALGORITHM, backup);
                } else {
                    LOGGER.debug("Migrated legacy profile checksum metadata: {}", file);
                }
            }
        } else {
            throw new ProfileCorruptionException(file, "Unsupported checksum algorithm " + algorithm);
        }
        if (document.getLong("revision") < 1L || document.getLong("saved_at") < 1L) {
            throw new ProfileCorruptionException(file, "Profile revision and saved_at must be positive");
        }
        boolean envelopePresent = document.contains("envelope_checksum_algorithm", Tag.TAG_STRING)
                || document.contains("envelope_checksum", Tag.TAG_STRING);
        if (envelopePresent) {
            String envelopeAlgorithm = document.getString("envelope_checksum_algorithm");
            if (!ENVELOPE_ALGORITHM.equals(envelopeAlgorithm)
                    && !ENVELOPE_ALGORITHM_V1.equals(envelopeAlgorithm)) {
                throw new ProfileCorruptionException(file, "Unsupported profile envelope checksum algorithm");
            }
            String actualEnvelope = ENVELOPE_ALGORITHM.equals(envelopeAlgorithm)
                    ? envelopeChecksum(document)
                    : legacyEnvelopeChecksum(document);
            if (!actualEnvelope.equals(document.getString("envelope_checksum"))) {
                throw new ProfileCorruptionException(file, "Profile envelope checksum mismatch");
            }
        } else if (migrateMetadata) {
            migrateEnvelopeChecksum(file, document);
        }
        return new DecodedProfile(new StoredProfile(
                payload,
                document.getLong("revision"),
                Instant.ofEpochMilli(document.getLong("saved_at")),
                canonical), digest.canonicalBytes());
    }

    private void migrateChecksum(Path file, CompoundTag document, CompoundTag payload, String canonical)
            throws IOException {
        document.putString("checksum_algorithm", NbtChecksums.CANONICAL_ALGORITHM);
        document.putString("checksum", canonical);
        document.put("payload", payload.copy());
        addEnvelopeChecksum(document);
        AtomicNbtFile.write(file, document);
    }

    private void migrateEnvelopeChecksum(Path file, CompoundTag document) throws IOException {
        CompoundTag migrated = document.copy();
        addEnvelopeChecksum(migrated);
        AtomicNbtFile.write(file, migrated);
    }

    private Path archiveLegacyChecksumProfile(Path file) throws IOException {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IOException("Profile path is outside the MWI storage root: " + file);
        }
        Path destination = root.resolve("archives")
                .resolve("checksum_migration")
                .resolve(Long.toString(Instant.now().toEpochMilli()) + "-" + UUID.randomUUID())
                .resolve(relative);
        Files.createDirectories(destination.getParent());
        Files.copy(file, destination);
        return destination;
    }

    private static CompoundTag profileDocument(
            ProfileKey key,
            CompoundTag payload,
            long revision,
            Instant savedAt,
            String payloadChecksum) throws IOException {
        CompoundTag document = new CompoundTag();
        document.putInt("schema_version", SCHEMA_VERSION);
        document.putUUID("player_uuid", key.playerId());
        document.putString("scope_type", key.scope().type().name().toLowerCase(Locale.ROOT));
        document.putString("scope_id", key.scope().id());
        document.putString("game_mode", key.gameMode().name().toLowerCase(Locale.ROOT));
        document.putString("share_id", key.shareId());
        document.putLong("revision", revision);
        document.putLong("saved_at", savedAt.toEpochMilli());
        document.putString("checksum_algorithm", NbtChecksums.CANONICAL_ALGORITHM);
        document.putString("checksum", payloadChecksum);
        document.put("payload", payload);
        addEnvelopeChecksum(document);
        return document;
    }

    private static void addEnvelopeChecksum(CompoundTag document) throws IOException {
        document.putString("envelope_checksum_algorithm", ENVELOPE_ALGORITHM);
        document.remove("envelope_checksum");
        document.putString("envelope_checksum", envelopeChecksum(document));
    }

    private static String envelopeChecksum(CompoundTag document) throws IOException {
        CompoundTag metadata = new CompoundTag();
        for (String key : document.getAllKeys()) {
            if (!"payload".equals(key) && !"envelope_checksum".equals(key)) {
                Tag value = document.get(key);
                if (value != null) {
                    metadata.put(key, value);
                }
            }
        }
        // The included payload checksum authenticates the canonical payload without recursively
        // traversing it again for the envelope digest.
        return NbtChecksums.canonicalSha256(metadata);
    }

    private static String legacyEnvelopeChecksum(CompoundTag document) throws IOException {
        String checksum = document.getString("envelope_checksum");
        document.remove("envelope_checksum");
        try {
            return NbtChecksums.canonicalSha256(document);
        } finally {
            if (!checksum.isEmpty()) {
                document.putString("envelope_checksum", checksum);
            }
        }
    }

    private static void addDocumentChecksum(CompoundTag document, String prefix) throws IOException {
        document.putString(prefix + "_checksum_algorithm", DOCUMENT_ALGORITHM);
        document.remove(prefix + "_checksum");
        document.putString(prefix + "_checksum", NbtChecksums.canonicalSha256(document));
    }

    private static void addTransactionChecksum(CompoundTag transaction) throws IOException {
        transaction.putString("transaction_checksum_algorithm", TRANSACTION_ALGORITHM);
        transaction.remove("transaction_checksum");
        transaction.putString(
                "transaction_checksum",
                NbtChecksums.canonicalSha256(transactionChecksumView(transaction)));
    }

    private static CompoundTag transactionChecksumView(CompoundTag transaction) {
        CompoundTag view = new CompoundTag();
        view.putInt("schema_version", transaction.getInt("schema_version"));
        view.putUUID("player_uuid", transaction.getUUID("player_uuid"));
        view.putLong("revision", transaction.getLong("revision"));
        view.putString("transaction_checksum_algorithm", transaction.getString("transaction_checksum_algorithm"));
        ListTag summaries = new ListTag();
        ListTag profiles = transaction.getList("profiles", Tag.TAG_COMPOUND);
        for (int index = 0; index < profiles.size(); index++) {
            CompoundTag write = profiles.getCompound(index);
            CompoundTag document = write.getCompound("document");
            CompoundTag summary = new CompoundTag();
            summary.put("key", write.getCompound("key"));
            summary.putLong("canonical_bytes", write.getLong("canonical_bytes"));
            summary.putString(
                    "envelope_checksum_algorithm", document.getString("envelope_checksum_algorithm"));
            summary.putString("envelope_checksum", document.getString("envelope_checksum"));
            summaries.add(summary);
        }
        view.put("profiles", summaries);
        if (transaction.contains("archives", Tag.TAG_LIST)) {
            view.put("archives", transaction.getList("archives", Tag.TAG_COMPOUND));
        }
        if (transaction.contains("journal", Tag.TAG_COMPOUND)) {
            view.put("journal", transaction.getCompound("journal"));
        }
        if (transaction.contains("recovery")) {
            view.put("recovery", transaction.get("recovery"));
        }
        return view;
    }

    private static void verifyOrMigrateDocumentChecksum(Path file, CompoundTag document, String prefix)
            throws IOException {
        String algorithmKey = prefix + "_checksum_algorithm";
        String checksumKey = prefix + "_checksum";
        if (!document.contains(algorithmKey) && !document.contains(checksumKey)) {
            addDocumentChecksum(document, prefix);
            AtomicNbtFile.write(file, document);
            return;
        }
        if (!DOCUMENT_ALGORITHM.equals(document.getString(algorithmKey))) {
            throw new ProfileCorruptionException(file, "Unsupported " + prefix + " checksum algorithm");
        }
        String expected = document.getString(checksumKey);
        document.remove(checksumKey);
        String actual;
        try {
            actual = NbtChecksums.canonicalSha256(document);
        } finally {
            document.putString(checksumKey, expected);
        }
        if (!actual.equals(expected)) {
            throw new ProfileCorruptionException(file, prefix + " checksum mismatch");
        }
    }

    private static void requireProfileSize(long bytes) throws IOException {
        if (bytes > MAX_PROFILE_BYTES) {
            throw new IOException("Profile payload exceeds " + MAX_PROFILE_BYTES + " canonical bytes");
        }
    }

    private StoredProfile putCache(ProfileKey key, StoredProfile profile, long weight) {
        return cache.put(key, profile, weight,
                (current, candidate) -> current.revision() >= candidate.revision());
    }

    private void removeCache(ProfileKey key) {
        cache.remove(key);
    }

    CacheInvariant cacheInvariant() {
        WeightedLruCache.Invariant invariant = cache.invariant();
        return new CacheInvariant(invariant.valid(), invariant.description(), invariant.entries(),
                invariant.bytes(), invariant.maxBytes());
    }

    private Path profilePath(ProfileKey key) {
        return root.resolve("profiles")
                .resolve(key.playerId().toString())
                .resolve(key.scope().type().name().toLowerCase(Locale.ROOT))
                .resolve(encode(key.scope().id()))
                .resolve(key.gameMode().name().toLowerCase(Locale.ROOT))
                .resolve(encode(key.shareId()) + ".dat");
    }

    private Path transactionPath(UUID playerId, long revision) {
        return root.resolve("transactions").resolve(playerId.toString()).resolve(revision + ".dat");
    }

    private void recoverPendingTransactions() throws IOException {
        Path transactions = root.resolve("transactions");
        if (!Files.isDirectory(transactions)) {
            return;
        }
        List<Path> pending;
        try (var paths = Files.walk(transactions)) {
            pending = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .sorted(Comparator
                            .comparing((Path path) -> path.getParent().getFileName().toString())
                            .thenComparingLong(NbtProfileStore::transactionRevision))
                    .toList();
        }
        for (Path file : pending) {
            UUID owner = parseUuid(file.getParent().getFileName().toString()).orElse(null);
            if (owner == null || blockedPlayers.containsKey(owner)) {
                continue;
            }
            try {
                CompoundTag transaction = AtomicNbtFile.readTransaction(file);
                if (!transaction.hasUUID("player_uuid") || !owner.equals(transaction.getUUID("player_uuid"))
                        || !transactionPath(owner, transaction.getLong("revision")).equals(file)) {
                    throw new ProfileCorruptionException(file, "Transaction identity does not match its path");
                }
                applyPendingTransaction(file, transaction, false);
                LOGGER.warn("Completed interrupted MWI profile transaction {}", file);
            } catch (IOException failure) {
                blockPlayer(owner, failure instanceof ProfileCorruptionException corruption
                        ? corruption.file() : file, failure);
            }
        }
    }

    private void verifyTransaction(Path file, CompoundTag transaction) throws IOException {
        if (transaction.getInt("schema_version") != SCHEMA_VERSION
                || !transaction.hasUUID("player_uuid")
                || transaction.getLong("revision") < 1
                || !transaction.contains("profiles", Tag.TAG_LIST)) {
            throw new ProfileCorruptionException(file, "Pending transaction metadata is invalid");
        }
        if (transaction.contains("recovery")) {
            verifyRecovery(file, transaction.getCompound("recovery"), transaction.getUUID("player_uuid"), transaction.getLong("revision"));
        }
        if (TRANSACTION_ALGORITHM.equals(transaction.getString("transaction_checksum_algorithm"))) {
            String expected = transaction.getString("transaction_checksum");
            String actual = NbtChecksums.canonicalSha256(transactionChecksumView(transaction));
            if (!actual.equals(expected)) {
                throw new ProfileCorruptionException(file, "transaction checksum mismatch");
            }
            ListTag writes = transaction.getList("profiles", Tag.TAG_COMPOUND);
            for (int index = 0; index < writes.size(); index++) {
                CompoundTag write = writes.getCompound(index);
                if (!write.contains("key", Tag.TAG_COMPOUND)
                        || !write.contains("document", Tag.TAG_COMPOUND)) {
                    throw new ProfileCorruptionException(file, "Pending transaction entry is incomplete");
                }
                ProfileKey key = decodeKey(write.getCompound("key"));
                decodeProfile(file, key, write.getCompound("document"), false);
            }
            verifyArchiveWrites(file, transaction);
        } else {
            verifyOrMigrateDocumentChecksum(file, transaction, "transaction");
        }
    }

    private static void verifyArchiveWrites(Path file, CompoundTag transaction) throws IOException {
        if (!transaction.contains("archives")) {
            return;
        }
        Tag raw = transaction.get("archives");
        if (!(raw instanceof ListTag archives)
                || (!archives.isEmpty() && archives.getElementType() != Tag.TAG_COMPOUND)) {
            throw new ProfileCorruptionException(file, "Pending transaction archives are invalid");
        }
        for (int index = 0; index < archives.size(); index++) {
            CompoundTag write = archives.getCompound(index);
            if (!write.contains("key", Tag.TAG_COMPOUND)
                    || !write.contains("category", Tag.TAG_STRING)
                    || !write.contains("document", Tag.TAG_COMPOUND)) {
                throw new ProfileCorruptionException(file, "Pending archive entry is incomplete");
            }
            ProfileKey key = decodeKey(write.getCompound("key"));
            String category;
            try {
                category = requireSegment(write.getString("category"), "category");
            } catch (IllegalArgumentException invalid) {
                throw new ProfileCorruptionException(file, "Pending archive category is invalid", invalid);
            }
            CompoundTag document = write.getCompound("document");
            if (!document.hasUUID("player_uuid")
                    || !key.playerId().equals(document.getUUID("player_uuid"))
                    || !category.equals(document.getString("category"))
                    || !document.contains("payload", Tag.TAG_COMPOUND)
                    || !document.getString("source_checksum").equals(
                            NbtChecksums.canonicalSha256(document.getCompound("payload")))) {
                throw new ProfileCorruptionException(file, "Pending archive document is invalid");
            }
        }
    }

    private void applyArchiveWrites(
            Path transactionFile, CompoundTag transaction, UUID playerId, long revision) throws IOException {
        ListTag archives = transaction.getList("archives", Tag.TAG_COMPOUND);
        for (int index = 0; index < archives.size(); index++) {
            CompoundTag write = archives.getCompound(index);
            ProfileKey key = decodeKey(write.getCompound("key"));
            if (!playerId.equals(key.playerId())) {
                throw new ProfileCorruptionException(transactionFile,
                        "Pending archive contains another player");
            }
            String category = requireSegment(write.getString("category"), "category");
            CompoundTag document = write.getCompound("document");
            Path destination = archivePath(key, category, revision);
            if (Files.isRegularFile(destination)) {
                CompoundTag installed = AtomicNbtFile.read(destination);
                if (!installed.equals(document)) {
                    throw new ProfileCorruptionException(destination,
                            "Compatibility archive revision collision");
                }
                continue;
            }
            AtomicNbtFile.write(destination, document, faultInjector);
        }
    }

    private void applyPendingTransaction(Path transactionFile, CompoundTag transaction, boolean trusted) throws IOException {
        if (!trusted) {
            verifyTransaction(transactionFile, transaction);
        }
        UUID playerId = transaction.getUUID("player_uuid");
        long revision = transaction.getLong("revision");
        applyArchiveWrites(transactionFile, transaction, playerId, revision);
        ListTag writes = transaction.getList("profiles", Tag.TAG_COMPOUND);
        for (int index = 0; index < writes.size(); index++) {
            CompoundTag write = writes.getCompound(index);
            if (!write.contains("key", Tag.TAG_COMPOUND) || !write.contains("document", Tag.TAG_COMPOUND)) {
                throw new ProfileCorruptionException(transactionFile, "Pending transaction entry is incomplete");
            }
            ProfileKey key = decodeKey(write.getCompound("key"));
            if (!key.playerId().equals(playerId)) {
                throw new ProfileCorruptionException(transactionFile, "Pending transaction contains another player");
            }
            CompoundTag document = write.getCompound("document");
            faultInjector.beforeProfileApply(key, index);
            Path destination = profilePath(key);
            if (!trusted && Files.isRegularFile(destination)) {
                DecodedProfile installed = readCanonicalProfile(destination, key, false);
                long installedRevision = installed.profile().revision();
                if (installedRevision > revision) {
                    continue;
                }
                if (installedRevision == revision
                        && installed.profile().checksum().equals(document.getString("checksum"))) {
                    putCache(key, installed.profile(), installed.canonicalBytes());
                    continue;
                }
            }
            AtomicNbtFile.write(destination, document, faultInjector);
            if (trusted) {
                long canonicalBytes = write.getLong("canonical_bytes");
                requireProfileSize(canonicalBytes);
                putCache(key, new StoredProfile(
                        document.getCompound("payload"),
                        document.getLong("revision"),
                        Instant.ofEpochMilli(document.getLong("saved_at")),
                        document.getString("checksum")), canonicalBytes);
            } else {
                DecodedProfile decoded = decodeProfile(destination, key, document);
                putCache(key, decoded.profile(), decoded.canonicalBytes());
            }
        }
        if (transaction.contains("journal", Tag.TAG_COMPOUND)) {
            writeRevisionLocked(playerId, revision, transaction.getCompound("journal"));
        }
        advanceRevisionCounter(playerId, revision);
        if (transaction.contains("recovery")) {
            applyRecovery(transactionFile, transaction.getCompound("recovery"), playerId, revision);
        }
        AtomicNbtFile.deleteDurably(transactionFile);
    }

    @Override
    public void commitRecovery(UUID playerId, long revision, Map<ProfileKey, CompoundTag> profiles,
                               RecoveryMutation mutation) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(playerId);
        try {
            commitBatchLocked(playerId, revision, profiles, Optional.empty(), List.of(), Optional.of(mutation));
        } finally { lock.unlock(); }
    }

    @Override
    public Optional<StoredRecovery> loadRecovery(UUID playerId, UUID sourceId) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(playerId);
        try {
            requireHealthyPlayer(playerId);
            Path path = recoveryPath("recovery_sources", playerId, sourceId);
            if (!Files.exists(path)) return Optional.empty();
            CompoundTag document = readRecovery(path, playerId);
            if (!sourceId.equals(document.getUUID("source_id"))) throw new IOException("Recovery source identity mismatch");
            return Optional.of(new StoredRecovery(sourceId, document.getLong("version"),
                    document.getUUID("operation_id"), document.getLong("revision"),
                    document.getCompound("payload"), document.getString("recovery_checksum")));
        } finally { lock.unlock(); }
    }

    @Override
    public List<StoredRecovery> listRecoveries(UUID playerId) throws IOException {
        Path directory = root.resolve("recovery_sources").resolve(playerId.toString());
        if (!Files.isDirectory(directory)) return List.of();
        List<StoredRecovery> result = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.dat")) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                UUID id = parseUuid(name.substring(0, name.length() - 4))
                        .orElseThrow(() -> new IOException("Invalid recovery source filename"));
                if (result.size() >= 4096) throw new IOException("Recovery source listing exceeds safety limit");
                try {
                    StoredRecovery record = loadRecovery(playerId, id).orElseThrow();
                    CompoundTag summary = new CompoundTag();
                    summary.putString("state", record.payload().getString("state"));
                    summary.putLong("created_at", record.payload().getLong("created_at"));
                    result.add(new StoredRecovery(id, record.version(), record.operationId(), record.profileRevision(), summary, record.checksum()));
                } catch (IOException failure) {
                    LOGGER.error("Recovery source {} is isolated; other cases remain available", path, failure);
                    CompoundTag summary = new CompoundTag(); summary.putString("state", "REVIEW");
                    summary.putString("error", "原件损坏或无法校验，禁止自动恢复");
                    result.add(new StoredRecovery(id, -1, id, 0, summary, ""));
                }
            }
        }
        result.sort(Comparator.comparingLong(StoredRecovery::profileRevision).reversed());
        return List.copyOf(result);
    }

    @Override
    public Optional<CompoundTag> recoveryOperation(UUID playerId, UUID operationId) throws IOException {
        ReentrantLock lock = acquirePersistenceLock(playerId);
        try {
            requireHealthyPlayer(playerId);
            Path path = recoveryPath("recovery_operations", playerId, operationId);
            if (!Files.exists(path)) return Optional.empty();
            CompoundTag document = readRecovery(path, playerId);
            if (!operationId.equals(document.getUUID("operation_id"))) throw new IOException("Recovery operation identity mismatch");
            return Optional.of(document.copy());
        } finally { lock.unlock(); }
    }

    private CompoundTag prepareRecovery(UUID playerId, long revision, RecoveryMutation mutation) throws IOException {
        requireNoPendingWrites(playerId);
        if (recoveryOperation(playerId, mutation.operationId()).isPresent()) {
            throw new IOException("Recovery operation already committed; query its receipt, do not reissue");
        }
        Optional<StoredRecovery> current = loadRecovery(playerId, mutation.sourceId());
        if (current.map(StoredRecovery::version).orElse(-1L) != mutation.expectedVersion()) {
            throw new IOException("Recovery source changed; reopen the preview");
        }
        CompoundTag payload = mutation.payload();
        if (!payload.contains("identity", Tag.TAG_COMPOUND)
                || !java.util.Set.of("REVIEW", "AVAILABLE", "CLOSED").contains(payload.getString("state"))) {
            throw new IOException("Invalid recovery payload");
        }
        requireProfileSize(NbtChecksums.canonicalDigest(payload).canonicalBytes());
        if (current.isPresent()) {
            CompoundTag before = current.orElseThrow().payload();
            if (!before.getCompound("identity").equals(payload.getCompound("identity"))) {
                throw new IOException("Recovery evidence and scope are immutable");
            }
            if ("CLOSED".equals(before.getString("state"))) throw new IOException("Recovery source is permanently closed");
        }
        CompoundTag document = new CompoundTag();
        document.putInt("schema_version", 1);
        document.putUUID("player_uuid", playerId);
        document.putUUID("source_id", mutation.sourceId());
        document.putUUID("operation_id", mutation.operationId());
        document.putLong("version", Math.addExact(mutation.expectedVersion(), 1));
        document.putLong("revision", revision);
        document.put("payload", payload);
        addDocumentChecksum(document, "recovery");
        return document;
    }

    private Path recoveryPath(String category, UUID playerId, UUID id) {
        return root.resolve(category).resolve(playerId.toString()).resolve(id + ".dat");
    }

    private CompoundTag readRecovery(Path path, UUID owner) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Recovery record cannot be a symbolic link");
        CompoundTag document = AtomicNbtFile.read(path);
        verifyRecovery(path, document, owner, document.getLong("revision"));
        return document;
    }

    private static void verifyRecovery(Path path, CompoundTag document, UUID owner, long revision) throws IOException {
        if (document.getInt("schema_version") != 1 || !document.hasUUID("player_uuid")
                || !owner.equals(document.getUUID("player_uuid")) || !document.hasUUID("source_id")
                || !document.hasUUID("operation_id") || !document.contains("version", Tag.TAG_LONG)
                || document.getLong("version") < 0 || document.getLong("revision") != revision || revision < 1
                || !document.contains("payload", Tag.TAG_COMPOUND)
                || !DOCUMENT_ALGORITHM.equals(document.getString("recovery_checksum_algorithm"))) {
            throw new ProfileCorruptionException(path, "Invalid recovery record envelope");
        }
        // Unlike old profile journals, recovery evidence is never migrated from unchecked data.
        String expected = document.getString("recovery_checksum");
        CompoundTag checked = document.copy(); checked.remove("recovery_checksum");
        if (!expected.equals(NbtChecksums.canonicalSha256(checked))) {
            throw new ProfileCorruptionException(path, "Recovery checksum mismatch");
        }
    }

    private void applyRecovery(Path transactionPath, CompoundTag document, UUID playerId, long revision) throws IOException {
        verifyRecovery(transactionPath, document, playerId, revision);
        UUID sourceId = document.getUUID("source_id"), operationId = document.getUUID("operation_id");
        Path sourcePath = recoveryPath("recovery_sources", playerId, sourceId);
        Path operationPath = recoveryPath("recovery_operations", playerId, operationId);
        if (Files.exists(operationPath)) {
            if (!readRecovery(operationPath, playerId).equals(document)) throw new IOException("Recovery operation collision");
        } else AtomicNbtFile.write(operationPath, document, faultInjector);
        if (Files.exists(sourcePath)) {
            CompoundTag installed = readRecovery(sourcePath, playerId);
            long installedVersion = installed.getLong("version"), version = document.getLong("version");
            if (installedVersion > version) return; // Later committed progress is authoritative.
            if (installedVersion == version) {
                if (!installed.equals(document)) throw new IOException("Recovery source version collision");
                return;
            }
            if (installedVersion + 1 != version) throw new IOException("Recovery source history gap");
        } else if (document.getLong("version") != 0) throw new IOException("Missing recovery source history");
        AtomicNbtFile.write(sourcePath, document, faultInjector);
    }

    private boolean recoverOrphanedPreparedRevisions() throws IOException {
        Path revisionsRoot = root.resolve("revisions");
        if (!Files.isDirectory(revisionsRoot)) {
            return false;
        }
        List<Path> revisionFiles;
        try (var paths = Files.walk(revisionsRoot)) {
            revisionFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .sorted()
                    .toList();
        }
        boolean recovered = false;
        for (Path file : revisionFiles) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            UUID owner = parseUuid(file.getParent().getFileName().toString()).orElse(null);
            if (owner == null || blockedPlayers.containsKey(owner)) {
                continue;
            }
            try {
                CompoundTag document = AtomicNbtFile.read(file);
                if (document.getInt("schema_version") != SCHEMA_VERSION
                        || !document.hasUUID("player_uuid")
                        || document.getLong("revision") < 1L
                        || !document.contains("journal", Tag.TAG_COMPOUND)) {
                    throw new ProfileCorruptionException(file, "Revision metadata is invalid");
                }
                UUID playerId = document.getUUID("player_uuid");
                long revision = document.getLong("revision");
                if (!revisionPath(playerId, revision).equals(file.toAbsolutePath().normalize())) {
                    throw new ProfileCorruptionException(file, "Revision metadata does not match its path");
                }
                verifyOrMigrateDocumentChecksum(file, document, "revision");
                CompoundTag journal = document.getCompound("journal");
                if (!"prepared".equals(journal.getString("status"))) {
                    continue;
                }
                LinkedHashMap<ProfileKey, CompoundTag> rollbackWrites = new LinkedHashMap<>();
                java.util.Set<ProfileKey> resetTargets = startupResetTargets.computeIfAbsent(
                        playerId, ignored -> new HashSet<>());
                decodePreparedRollback(file, playerId, journal, rollbackWrites, resetTargets);
                journal.putString("status", "rolled_back");
                journal.putString("error", "Recovered after shutdown before a durable profile batch was prepared");
                canonicalRecoveryPlayers.add(playerId);
                commitBatch(playerId, revision, rollbackWrites, Optional.of(journal));
                recovered = true;
            } catch (IOException failure) {
                blockPlayer(owner, failure instanceof ProfileCorruptionException corruption
                        ? corruption.file() : file, failure);
            }
        }
        return recovered;
    }

    private void decodePreparedRollback(
            Path file,
            UUID playerId,
            CompoundTag journal,
            Map<ProfileKey, CompoundTag> rollbackWrites,
            java.util.Set<ProfileKey> resetTargets) throws IOException {
        if (!journal.contains("changes", Tag.TAG_LIST)) {
            throw new ProfileCorruptionException(file, "Prepared revision contains no changes");
        }
        ListTag changes = journal.getList("changes", Tag.TAG_COMPOUND);
        for (int index = 0; index < changes.size(); index++) {
            CompoundTag change = changes.getCompound(index);
            if (!change.contains("to_key", Tag.TAG_COMPOUND)
                    || !change.contains("before", Tag.TAG_COMPOUND)) {
                throw new ProfileCorruptionException(file, "Prepared revision change is incomplete");
            }
            String shareId = change.getString("share_id");
            ProfileKey toKey = decodeKey(change.getCompound("to_key"));
            ProfileKey fromKey = change.contains("from_key", Tag.TAG_COMPOUND)
                    ? decodeKey(change.getCompound("from_key"))
                    : null;
            validateJournalKey(file, playerId, shareId, toKey);
            if (fromKey != null) {
                validateJournalKey(file, playerId, shareId, fromKey);
                rollbackWrites.put(fromKey, change.getCompound("before").copy());
                if (!fromKey.equals(toKey) && !Files.isRegularFile(profilePath(toKey))) {
                    resetTargets.add(toKey);
                }
            } else if (change.contains("target_existed", Tag.TAG_BYTE)) {
                // New login journals keep the unknown vanilla live snapshot in "before" only for
                // forensic/manual revision recovery. Durable rollback must restore the canonical
                // before-image, never adopt that unowned snapshot into the target scope.
                if (change.getBoolean("target_existed")) {
                    if (!change.contains("canonical_before", Tag.TAG_COMPOUND)) {
                        throw new ProfileCorruptionException(
                                file, "Prepared login revision is missing its canonical before-image");
                    }
                    rollbackWrites.put(toKey, change.getCompound("canonical_before").copy());
                } else {
                    resetTargets.add(toKey);
                }
            } else {
                // Backward compatibility for legacy prepared login journals. Older versions treated
                // the login snapshot as canonical; retain that recovery rule only for those already
                // persisted journals so upgrades remain readable.
                rollbackWrites.put(toKey, change.getCompound("before").copy());
            }
        }
    }

    private static void validateJournalKey(Path file, UUID playerId, String shareId, ProfileKey key)
            throws ProfileCorruptionException {
        if (!key.playerId().equals(playerId) || shareId.isBlank() || !key.shareId().equals(shareId)) {
            throw new ProfileCorruptionException(file, "Prepared revision key does not match its player or share");
        }
    }

    private static CompoundTag encodeKey(ProfileKey key) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("player_uuid", key.playerId());
        tag.putString("scope_type", key.scope().type().name());
        tag.putString("scope_id", key.scope().id());
        tag.putString("game_mode", key.gameMode().name());
        tag.putString("share_id", key.shareId());
        return tag;
    }

    private static ProfileKey decodeKey(CompoundTag tag) throws IOException {
        try {
            if (!tag.hasUUID("player_uuid")) {
                throw new IllegalArgumentException("missing player UUID");
            }
            return new ProfileKey(
                    tag.getUUID("player_uuid"),
                    new ScopeKey(ScopeKey.Type.valueOf(tag.getString("scope_type")), tag.getString("scope_id")),
                    GameModeBucket.valueOf(tag.getString("game_mode")),
                    tag.getString("share_id"));
        } catch (RuntimeException invalid) {
            throw new IOException("Pending transaction contains an invalid profile key", invalid);
        }
    }

    private Path revisionPath(UUID playerId, long revision) {
        return root.resolve("revisions").resolve(playerId.toString()).resolve(revision + ".dat");
    }

    private Path archivePath(ProfileKey key, String category, long revision) {
        String safeCategory = requireSegment(category, "category");
        Path categoryRoot = safeCategory.equals("missing_items")
                ? root.resolve("archives").resolve("compatibility").resolve("missing_items")
                : root.resolve("archives").resolve(safeCategory);
        return categoryRoot
                .resolve(key.playerId().toString())
                .resolve(Long.toString(revision))
                .resolve(key.scope().type().name().toLowerCase(Locale.ROOT))
                .resolve(encode(key.scope().id()))
                .resolve(key.gameMode().name().toLowerCase(Locale.ROOT))
                .resolve(encode(key.shareId()) + ".dat");
    }

    private boolean loadIndex() throws IOException {
        Path file = root.resolve(INDEX_FILE);
        if (!Files.isRegularFile(file)) {
            return true;
        }
        try {
            CompoundTag index = AtomicNbtFile.read(file);
            int schema = index.getInt("schema_version");
            if (schema == 1) {
                ListTag entries = index.getList("player_revisions", Tag.TAG_COMPOUND);
                for (int position = 0; position < entries.size(); position++) {
                    CompoundTag entry = entries.getCompound(position);
                    if (entry.hasUUID("player_uuid") && entry.getLong("revision") >= 0) {
                        UUID playerId = entry.getUUID("player_uuid");
                        long revision = entry.getLong("revision");
                        writeRevisionCounter(playerId, revision);
                        revisionStates.computeIfAbsent(playerId, ignored -> new RevisionState(revision))
                                .markDurable(revision);
                    }
                }
                return !index.contains("clean_shutdown") || index.getBoolean("clean_shutdown");
            }
            if (schema != INDEX_SCHEMA_VERSION) {
                throw new ProfileCorruptionException(file, "Unsupported index schema " + schema);
            }
            verifyOrMigrateDocumentChecksum(file, index, "index");
            return index.getBoolean("clean_shutdown");
        } catch (IOException | RuntimeException invalid) {
            archiveCorruptMetadata(file, "index");
            LOGGER.error("MWI index metadata was corrupt; treating the previous shutdown as unclean", invalid);
            return false;
        }
    }

    private void writeIndex() throws IOException {
        CompoundTag index = new CompoundTag();
        index.putInt("schema_version", INDEX_SCHEMA_VERSION);
        index.putBoolean("clean_shutdown", cleanShutdown);
        index.putLong("updated_at", Instant.now().toEpochMilli());
        addDocumentChecksum(index, "index");
        AtomicNbtFile.write(root.resolve(INDEX_FILE), index);
    }

    private long loadRevisionCounter(UUID playerId) throws IOException {
        Path file = revisionCounterPath(playerId);
        if (!Files.isRegularFile(file)) {
            return rebuildRevisionCounter(playerId);
        }
        try {
            CompoundTag counter = AtomicNbtFile.read(file);
            if (counter.getInt("schema_version") != SCHEMA_VERSION
                    || !counter.hasUUID("player_uuid")
                    || !playerId.equals(counter.getUUID("player_uuid"))
                    || counter.getLong("revision") < 0) {
                throw new ProfileCorruptionException(file, "Revision counter metadata is invalid");
            }
            verifyOrMigrateDocumentChecksum(file, counter, "counter");
            return counter.getLong("revision");
        } catch (IOException | RuntimeException invalid) {
            archiveCorruptMetadata(file, "revision-counter");
            LOGGER.error("Revision counter for {} was corrupt and will be rebuilt", playerId, invalid);
            return rebuildRevisionCounter(playerId);
        }
    }

    private long rebuildRevisionCounter(UUID playerId) throws IOException {
        long journalRevision = listRevisions(playerId).stream().mapToLong(Long::longValue).max().orElse(0L);
        long profileRevision = maxProfileRevision(playerId);
        long recovered = Math.max(journalRevision, profileRevision);
        writeRevisionCounter(playerId, recovered);
        return recovered;
    }

    private long maxProfileRevision(UUID playerId) throws IOException {
        Path playerProfiles = root.resolve("profiles").resolve(playerId.toString());
        if (!Files.isDirectory(playerProfiles)) {
            return 0L;
        }
        long maximum = 0L;
        try (var paths = Files.walk(playerProfiles)) {
            for (Path profile : paths.filter(Files::isRegularFile)
                    .filter(path -> isCanonicalProfilePath(playerId, path)).toList()) {
                ProfileKey key = keyFromPath(playerId, profile);
                maximum = Math.max(maximum, readCanonicalProfile(profile, key, false).profile().revision());
            }
        }
        return maximum;
    }

    private void writeRevisionCounter(UUID playerId, long revision) throws IOException {
        CompoundTag counter = new CompoundTag();
        counter.putInt("schema_version", SCHEMA_VERSION);
        counter.putUUID("player_uuid", playerId);
        counter.putLong("revision", revision);
        addDocumentChecksum(counter, "counter");
        AtomicNbtFile.write(revisionCounterPath(playerId), counter);
    }

    private void advanceRevisionCounter(UUID playerId, long revision) throws IOException {
        RevisionState state = revisionStates.computeIfAbsent(playerId, ignored -> new RevisionState(0L));
        long durable = state.durable();
        if (revision > durable) {
            writeRevisionCounter(playerId, revision);
            state.markDurable(revision);
        }
    }

    private void initializeRevisionStates() throws IOException {
        java.util.Set<UUID> playerIds = new HashSet<>(revisionStates.keySet());
        collectPlayerDirectoryIds(root.resolve("profiles"), playerIds);
        collectPlayerDirectoryIds(root.resolve("revisions"), playerIds);
        Path counters = root.resolve("revision_counters");
        if (Files.isDirectory(counters)) {
            try (var files = Files.list(counters)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".dat")) {
                        continue;
                    }
                    parseUuid(name.substring(0, name.length() - 4)).ifPresent(playerIds::add);
                }
            }
        }
        for (UUID playerId : playerIds) {
            if (blockedPlayers.containsKey(playerId)) {
                continue;
            }
            try {
                long durable = Math.max(loadRevisionCounter(playerId),
                        Math.max(listRevisions(playerId).stream().mapToLong(Long::longValue).max().orElse(0L),
                                maxProfileRevision(playerId)));
                revisionStates.computeIfAbsent(playerId, ignored -> new RevisionState(durable)).markDurable(durable);
            } catch (IOException failure) {
                blockPlayer(playerId, failure instanceof ProfileCorruptionException corruption
                        ? corruption.file() : root.resolve("profiles").resolve(playerId.toString()), failure);
            }
        }
    }

    private static void collectPlayerDirectoryIds(Path directory, java.util.Set<UUID> target) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            paths.filter(Files::isDirectory)
                    .map(path -> parseUuid(path.getFileName().toString()))
                    .flatMap(Optional::stream)
                    .forEach(target::add);
        }
    }

    private ReentrantLock acquirePersistenceLock(UUID playerId) throws IOException {
        requireHealthyPlayer(playerId);
        ReentrantLock lock = persistenceLocks.computeIfAbsent(
                java.util.Objects.requireNonNull(playerId, "playerId"), ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new IOException("Profile persistence is already active for player " + playerId);
        }
        return lock;
    }

    private Path revisionCounterPath(UUID playerId) {
        return root.resolve("revision_counters").resolve(playerId + ".dat");
    }

    private ProfileKey keyFromPath(UUID playerId, Path file) {
        Path relative = root.resolve("profiles").resolve(playerId.toString()).relativize(file);
        String name = relative.getFileName().toString();
        return new ProfileKey(playerId,
                new ScopeKey(ScopeKey.Type.valueOf(relative.getName(0).toString().toUpperCase(Locale.ROOT)),
                        decodeSegment(relative.getName(1).toString())),
                GameModeBucket.valueOf(relative.getName(2).toString().toUpperCase(Locale.ROOT)),
                decodeSegment(name.substring(0, name.length() - 4)));
    }

    private static String decodeSegment(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private boolean isCanonicalProfilePath(UUID playerId, Path file) {
        Path relative = root.resolve("profiles").resolve(playerId.toString()).relativize(file);
        if (relative.getNameCount() != 4 || !file.getFileName().toString().endsWith(".dat")) {
            return false;
        }
        try {
            return profilePath(keyFromPath(playerId, file)).equals(file);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private DecodedProfile readCanonicalProfile(Path file, ProfileKey key, boolean migrate) throws IOException {
        try {
            CompoundTag document = AtomicNbtFile.read(file);
            // Verify the entire envelope before any legacy metadata rewrite.
            DecodedProfile decoded = decodeProfile(file, key, document, false);
            return migrate ? decodeProfile(file, key, document, true) : decoded;
        } catch (IOException failure) {
            blockPlayer(key.playerId(), file, failure);
            throw blockedPlayers.get(key.playerId());
        }
    }

    private void requireHealthyPlayer(UUID playerId) throws ProfileCorruptionException {
        ProfileCorruptionException failure = blockedPlayers.get(playerId);
        if (failure != null) {
            throw failure;
        }
    }

    private void requireNoPendingWrites(UUID playerId) throws ProfileCorruptionException {
        Path pending = pendingPlayers.get(playerId);
        if (pending != null) throw new ProfileCorruptionException(pending, "A durable transaction requires restart recovery");
    }

    private void loadQuarantineMarkers() throws IOException {
        Path directory = root.resolve("quarantine");
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            for (Path player : paths.filter(Files::isDirectory).toList()) {
                Optional<UUID> id = parseUuid(player.getFileName().toString());
                // Directory creation is the durable intent, even if archival was interrupted.
                id.ifPresent(uuid -> blockedPlayers.put(uuid, new ProfileCorruptionException(player,
                        "Player persistence quarantined; restore verified data before clearing quarantine")));
            }
        }
    }

    private void blockPlayer(UUID playerId, Path file, IOException cause) {
        ProfileCorruptionException failure = new ProfileCorruptionException(file,
                "Player persistence quarantined for " + playerId + "; original data retained", cause);
        if (blockedPlayers.putIfAbsent(playerId, failure) != null) {
            return;
        }
        invalidatePlayer(playerId);
        Path directory = root.resolve("quarantine").resolve(playerId.toString());
        try {
            Files.createDirectories(directory);
            // Never move/delete the canonical file: a failed archive must not become a missing profile.
            if (Files.isRegularFile(file)) {
                if (Files.size(file) > 256L * 1024L * 1024L) {
                    throw new IOException("Corrupt file exceeds automatic archival limit; preserve original in place");
                }
                byte[] bytes = Files.readAllBytes(file);
                String hash;
                try {
                    hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                } catch (java.security.NoSuchAlgorithmException impossible) {
                    throw new IllegalStateException(impossible);
                }
                AtomicNbtFile.writeBytes(directory.resolve(hash + ".bin"), bytes, FaultInjector.NONE);
                CompoundTag receipt = new CompoundTag();
                receipt.putUUID("player_uuid", playerId);
                receipt.putString("source", root.relativize(file).toString());
                receipt.putString("sha256", hash);
                receipt.putString("reason", cause.toString());
                receipt.putString("result", "blocked; original retained; no empty-profile fallback");
                AtomicNbtFile.write(directory.resolve(hash + ".dat"), receipt);
            }
        } catch (IOException archivalFailure) {
            failure.addSuppressed(archivalFailure);
            LOGGER.error("Unable to archive quarantined MWI data {}; original retained", file, archivalFailure);
        }
        LOGGER.error("MWI player {} quarantined at {}; recovery blocked, other players remain available",
                playerId, file, failure);
    }

    private void archiveCorruptMetadata(Path file, String category) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Path destination = root.resolve("archives").resolve("metadata_corruption")
                .resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + '-' + requireSegment(category, "category") + ".dat");
        Files.createDirectories(destination.getParent());
        Files.copy(file, destination);
    }

    private void pruneRevisions(UUID playerId) throws IOException {
        List<Long> all = listRevisions(playerId);
        if (all.size() <= maxRevisions) {
            return;
        }
        for (long revision : all.subList(maxRevisions, all.size())) {
            Files.deleteIfExists(revisionPath(playerId, revision));
        }
    }

    private static StoredProfile copy(StoredProfile profile) {
        return new StoredProfile(profile.payload(), profile.revision(), profile.savedAt(), profile.checksum());
    }

    private record DecodedProfile(StoredProfile profile, long canonicalBytes) {
    }

    record CacheInvariant(boolean valid, String description, int entries, long bytes, long maxBytes) {
    }

    private static final class RevisionState {
        private final AtomicLong allocated;
        private final AtomicLong durable;

        private RevisionState(long revision) {
            this.allocated = new AtomicLong(revision);
            this.durable = new AtomicLong(revision);
        }

        private long allocate() {
            return allocated.updateAndGet(current -> Math.addExact(current, 1L));
        }

        private long durable() {
            return durable.get();
        }

        private void markDurable(long revision) {
            durable.accumulateAndGet(revision, Math::max);
            allocated.accumulateAndGet(revision, Math::max);
        }
    }

    interface FaultInjector extends AtomicNbtFile.FaultInjector {
        FaultInjector NONE = new FaultInjector() {
        };

        default void transactionDurable(Path transactionFile) throws IOException {
        }

        default void beforeProfileApply(ProfileKey key, int index) throws IOException {
        }

        default void beforeProfileRead(Path file, ProfileKey key) throws IOException {
        }
    }

    private static Optional<Long> parseLong(String value) {
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static long transactionRevision(Path path) {
        String name = path.getFileName().toString();
        return parseLong(name.substring(0, name.length() - ".dat".length())).orElse(Long.MAX_VALUE);
    }

    private static String encode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("storage identifier must not be blank");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String requireSegment(String value, String name) {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException(name + " must match [a-z][a-z0-9_-]{0,31}");
        }
        return value;
    }
}
