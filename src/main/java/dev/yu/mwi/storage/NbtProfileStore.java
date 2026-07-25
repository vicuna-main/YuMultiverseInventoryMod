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
    private final Map<ProfileKey, StoredProfile> cache;
    private final Map<ProfileKey, Long> cacheWeights = new LinkedHashMap<>();
    private final int maxCacheEntries;
    private final long maxCacheBytes;
    private final FaultInjector faultInjector;
    private long cacheBytes;
    private long cacheHits;
    private long cacheMisses;
    private final Map<UUID, Long> revisions = new LinkedHashMap<>();
    private final Map<UUID, Long> durableRevisions = new LinkedHashMap<>();
    private final Map<UUID, Object> persistenceLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, java.util.Set<ProfileKey>> startupResetTargets = new LinkedHashMap<>();
    private final java.util.Set<UUID> canonicalRecoveryPlayers = new HashSet<>();
    private final boolean recoveredFromUncleanShutdown;
    private boolean cleanCloseAllowed = true;
    private boolean cleanShutdown;

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
        this.cache = new LinkedHashMap<>(64, 0.75f, true);
        Files.createDirectories(root);
        recoverPendingTransactions();
        boolean previousShutdownClean = loadIndex();
        boolean recoveredPreparedRevision = recoverOrphanedPreparedRevisions();
        this.recoveredFromUncleanShutdown = !previousShutdownClean || recoveredPreparedRevision;
        this.cleanShutdown = false;
        writeIndex();
    }

    @Override
    public synchronized Optional<StoredProfile> load(ProfileKey key) throws IOException {
        StoredProfile cached = cache.get(key);
        if (cached != null) {
            cacheHits++;
            return Optional.of(copy(cached));
        }
        cacheMisses++;
        Path file = profilePath(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        CompoundTag document;
        try {
            document = AtomicNbtFile.read(file);
        } catch (RuntimeException | IOException failure) {
            throw new ProfileCorruptionException(file, "Unable to read profile", failure);
        }
        DecodedProfile decoded = decodeProfile(file, key, document);
        StoredProfile profile = decoded.profile();
        putCache(key, profile, decoded.canonicalBytes());
        return Optional.of(copy(profile));
    }

    @Override
    public synchronized Map<ProfileKey, StoredProfile> loadPlayerProfiles(UUID playerId) throws IOException {
        Path playerRoot = root.resolve("profiles").resolve(playerId.toString());
        if (!Files.isDirectory(playerRoot)) {
            return Map.of();
        }
        LinkedHashMap<ProfileKey, StoredProfile> profiles = new LinkedHashMap<>();
        try (var paths = Files.walk(playerRoot)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .sorted()
                    .toList()) {
                CompoundTag document = AtomicNbtFile.read(file);
                ProfileKey key;
                try {
                    key = new ProfileKey(
                            playerId,
                            new ScopeKey(
                                    ScopeKey.Type.valueOf(document.getString("scope_type").toUpperCase(Locale.ROOT)),
                                    document.getString("scope_id")),
                            GameModeBucket.valueOf(document.getString("game_mode").toUpperCase(Locale.ROOT)),
                            document.getString("share_id"));
                } catch (IllegalArgumentException invalid) {
                    throw new ProfileCorruptionException(file, "Profile key metadata is invalid", invalid);
                }
                if (!profilePath(key).equals(file.toAbsolutePath().normalize())) {
                    throw new ProfileCorruptionException(file, "Profile metadata does not match its path");
                }
                DecodedProfile decoded = decodeProfile(file, key, document);
                putCache(key, decoded.profile(), decoded.canonicalBytes());
                profiles.put(key, copy(decoded.profile()));
            }
        }
        return Map.copyOf(profiles);
    }

    @Override
    public synchronized SaveResult save(ProfileKey key, CompoundTag payload, long revision) throws IOException {
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
        return SaveResult.WRITTEN;
    }

    @Override
    public void commitBatch(
            UUID playerId,
            long revision,
            Map<ProfileKey, CompoundTag> profiles,
            Optional<CompoundTag> completedJournal) throws IOException {
        synchronized (persistenceLocks.computeIfAbsent(playerId, ignored -> new Object())) {
            commitBatchLocked(playerId, revision, profiles, completedJournal);
        }
    }

    private void commitBatchLocked(
            UUID playerId,
            long revision,
            Map<ProfileKey, CompoundTag> profiles,
            Optional<CompoundTag> completedJournal) throws IOException {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
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
        if (writes.isEmpty()) {
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
        completedJournal.ifPresent(journal -> transaction.put("journal", journal.copy()));
        addTransactionChecksum(transaction);
        Path transactionFile = transactionPath(playerId, revision);
        AtomicNbtFile.write(transactionFile, transaction, faultInjector);
        try {
            faultInjector.transactionDurable(transactionFile);
            applyPendingTransaction(transactionFile, transaction, true);
        } catch (IOException failure) {
            cleanCloseAllowed = false;
            throw new PendingTransactionException(transactionFile, failure);
        }
    }

    @Override
    public synchronized void delete(ProfileKey key) throws IOException {
        Files.deleteIfExists(profilePath(key));
        removeCache(key);
    }

    @Override
    public synchronized long nextRevision(UUID playerId) throws IOException {
        Long current = revisions.get(playerId);
        if (current == null) {
            current = Math.max(
                    loadRevisionCounter(playerId),
                    Math.max(
                            listRevisions(playerId).stream().mapToLong(Long::longValue).max().orElse(0L),
                            maxProfileRevision(playerId)));
            long next = Math.addExact(current, 1L);
            writeRevisionCounter(playerId, next);
            durableRevisions.put(playerId, next);
            revisions.put(playerId, next);
            return next;
        }
        long next = Math.addExact(current, 1L);
        revisions.put(playerId, next);
        return next;
    }

    @Override
    public synchronized void writeRevision(UUID playerId, long revision, CompoundTag journal) throws IOException {
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
    public synchronized Optional<CompoundTag> loadRevision(UUID playerId, long revision) throws IOException {
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
    public synchronized List<Long> listRevisions(UUID playerId) throws IOException {
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
    public synchronized List<WorldProfile> listWorldProfiles(
            UUID playerId, GameModeBucket gameMode, String shareId) throws IOException {
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
                CompoundTag document = AtomicNbtFile.read(file);
                String worldId = document.getString("scope_id");
                ProfileKey key = new ProfileKey(
                        playerId, new ScopeKey(ScopeKey.Type.WORLD, worldId), gameMode, shareId);
                DecodedProfile decoded = decodeProfile(file, key, document);
                putCache(key, decoded.profile(), decoded.canonicalBytes());
                result.add(new WorldProfile(worldId, copy(decoded.profile())));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized void archive(ProfileKey key, String category, String reason, long revision) throws IOException {
        StoredProfile profile = load(key).orElse(null);
        if (profile == null) {
            return;
        }
        CompoundTag archived = new CompoundTag();
        archived.putString("category", requireSegment(category, "category"));
        archived.putString("reason", reason == null ? "" : reason);
        archived.putLong("archived_at", Instant.now().toEpochMilli());
        archived.putLong("source_revision", profile.revision());
        archived.putString("source_checksum", profile.checksum());
        archived.put("payload", profile.payload());
        AtomicNbtFile.write(archivePath(key, category, revision), archived);
    }

    @Override
    public synchronized void writeLostFound(
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
    public synchronized CacheStats cacheStats() {
        return new CacheStats(cacheHits, cacheMisses, cache.size(), cacheBytes, maxCacheBytes);
    }

    @Override
    public boolean recoveredFromUncleanShutdown() {
        return recoveredFromUncleanShutdown;
    }

    @Override
    public synchronized boolean requiresCanonicalRecovery(UUID playerId) {
        return canonicalRecoveryPlayers.contains(playerId);
    }

    @Override
    public synchronized boolean shouldResetLive(ProfileKey key) {
        return startupResetTargets.getOrDefault(key.playerId(), java.util.Set.of()).contains(key);
    }

    @Override
    public synchronized void completeStartupRecovery(UUID playerId) {
        canonicalRecoveryPlayers.remove(playerId);
        startupResetTargets.remove(playerId);
    }

    @Override
    public synchronized void markUncleanShutdown() {
        cleanCloseAllowed = false;
    }

    @Override
    public synchronized void close() throws IOException {
        cleanShutdown = cleanCloseAllowed;
        writeIndex();
    }

    private DecodedProfile decodeProfile(Path file, ProfileKey key, CompoundTag document) throws IOException {
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
        } else {
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
        if (transaction.contains("journal", Tag.TAG_COMPOUND)) {
            view.put("journal", transaction.getCompound("journal"));
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

    private void putCache(ProfileKey key, StoredProfile profile, long weight) {
        removeCache(key);
        cache.put(key, profile);
        cacheWeights.put(key, weight);
        cacheBytes = Math.addExact(cacheBytes, weight);
        while (cache.size() > maxCacheEntries || cacheBytes > maxCacheBytes) {
            ProfileKey eldest = cache.keySet().iterator().next();
            removeCache(eldest);
        }
    }

    private void removeCache(ProfileKey key) {
        cache.remove(key);
        Long removedWeight = cacheWeights.remove(key);
        if (removedWeight != null) {
            cacheBytes -= removedWeight;
        }
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
            CompoundTag transaction = AtomicNbtFile.readTransaction(file);
            applyPendingTransaction(file, transaction, false);
            LOGGER.warn("Completed interrupted MWI profile transaction {}", file);
        }
    }

    private void verifyTransaction(Path file, CompoundTag transaction) throws IOException {
        if (transaction.getInt("schema_version") != SCHEMA_VERSION
                || !transaction.hasUUID("player_uuid")
                || transaction.getLong("revision") < 1
                || !transaction.contains("profiles", Tag.TAG_LIST)) {
            throw new ProfileCorruptionException(file, "Pending transaction metadata is invalid");
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
                decodeProfile(file, key, write.getCompound("document"));
            }
        } else {
            verifyOrMigrateDocumentChecksum(file, transaction, "transaction");
        }
    }

    private void applyPendingTransaction(Path transactionFile, CompoundTag transaction, boolean trusted) throws IOException {
        if (!trusted) {
            verifyTransaction(transactionFile, transaction);
        }
        UUID playerId = transaction.getUUID("player_uuid");
        long revision = transaction.getLong("revision");
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
                CompoundTag installed = AtomicNbtFile.read(destination);
                long installedRevision = installed.getLong("revision");
                if (installedRevision > revision) {
                    continue;
                }
                if (installedRevision == revision
                        && installed.getString("checksum").equals(document.getString("checksum"))) {
                    DecodedProfile decoded = decodeProfile(destination, key, installed);
                    putCache(key, decoded.profile(), decoded.canonicalBytes());
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
            writeRevision(playerId, revision, transaction.getCompound("journal"));
        }
        advanceRevisionCounter(playerId, revision);
        AtomicNbtFile.deleteDurably(transactionFile);
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
            } else {
                // Login adoption has no outgoing key; its before image is the intended canonical profile.
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
        return root.resolve("archives")
                .resolve(requireSegment(category, "category"))
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
                        revisions.put(playerId, revision);
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
            for (Path profile : paths.filter(Files::isRegularFile).toList()) {
                CompoundTag document = AtomicNbtFile.read(profile);
                maximum = Math.max(maximum, document.getLong("revision"));
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
        long durable;
        synchronized (this) {
            Long known = durableRevisions.get(playerId);
            durable = known == null ? loadRevisionCounter(playerId) : known;
        }
        if (revision > durable) {
            writeRevisionCounter(playerId, revision);
            durable = revision;
        }
        synchronized (this) {
            durableRevisions.merge(playerId, durable, Math::max);
            revisions.merge(playerId, revision, Math::max);
        }
    }

    private Path revisionCounterPath(UUID playerId) {
        return root.resolve("revision_counters").resolve(playerId + ".dat");
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

    interface FaultInjector extends AtomicNbtFile.FaultInjector {
        FaultInjector NONE = new FaultInjector() {
        };

        default void transactionDurable(Path transactionFile) throws IOException {
        }

        default void beforeProfileApply(ProfileKey key, int index) throws IOException {
        }
    }

    private static Optional<Long> parseLong(String value) {
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
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
