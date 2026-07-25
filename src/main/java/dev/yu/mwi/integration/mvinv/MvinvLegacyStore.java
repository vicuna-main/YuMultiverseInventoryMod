package dev.yu.mwi.integration.mvinv;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import net.minecraft.SharedConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Safe offline reader/editor for Multiverse-Inventories 5.x JSON profiles. */
public final class MvinvLegacyStore {
    private static final Gson GSON = new Gson();
    private static final long MAX_JSON_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_ITEM_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_SLOTS = 4_096;
    private static final int MAX_SCOPE_SCAN_DEPTH = 32;
    private static final int MAX_SCOPE_SUGGESTIONS = 256;
    private static final int RECOVERY_TOKEN_SCHEMA = 1;
    private static final String RECOVERY_TOKEN_KEY = "yu_mwi_mvinv_recovery";
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Set<String> GAME_MODES = Set.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");

    private final Path root;

    public MvinvLegacyStore(Path dataDirectory) {
        root = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
    }

    /** Lists archive scopes that contain at least one player profile, for command completion. */
    public List<String> listScopes() throws IOException {
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        collectWorldScopes(root.resolve("worlds"), scopes);
        collectGroupScopes(root.resolve("groups"), scopes);
        return scopes.stream().sorted().limit(MAX_SCOPE_SUGGESTIONS).toList();
    }

    private static void collectWorldScopes(Path worlds, Set<String> scopes) throws IOException {
        if (!Files.isDirectory(worlds)) {
            return;
        }
        try (var paths = Files.walk(worlds, MAX_SCOPE_SCAN_DEPTH)) {
            paths.filter(Files::isRegularFile)
                    .filter(MvinvLegacyStore::isProfileFile)
                    .map(Path::getParent)
                    .map(worlds::relativize)
                    .map(MvinvLegacyStore::portablePath)
                    .filter(name -> !name.isBlank())
                    .forEach(scopes::add);
        }
    }

    private static void collectGroupScopes(Path groups, Set<String> scopes) throws IOException {
        if (!Files.isDirectory(groups)) {
            return;
        }
        try (var paths = Files.walk(groups, 2)) {
            paths.filter(Files::isRegularFile)
                    .filter(MvinvLegacyStore::isProfileFile)
                    .map(Path::getParent)
                    .map(groups::relativize)
                    .filter(relative -> relative.getNameCount() == 1)
                    .map(MvinvLegacyStore::portablePath)
                    .filter(name -> !name.isBlank())
                    .map(name -> "group:" + name)
                    .forEach(scopes::add);
        }
    }

    private static boolean isProfileFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private static String portablePath(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    public OpenProfile open(
            String playerNameOrUuid,
            String scopeExpression,
            String gameMode,
            Section section,
            HolderLookup.Provider lookup) throws IOException {
        requireDataDirectory();
        Objects.requireNonNull(section, "section");
        String normalizedMode = normalizeGameMode(gameMode);
        Scope scope = Scope.parse(scopeExpression);
        Path container = resolveContainer(scope);
        String playerName = resolvePlayerName(playerNameOrUuid, container);
        Path profileFile = resolveExistingProfile(container, playerName);
        JsonObject document = readDocument(profileFile);
        JsonObject profile = profile(document, normalizedMode, profileFile);
        List<Slot> slots = decodeSection(profile, section, lookup);
        if (slots.isEmpty()) {
            throw new IOException("MVInv profile section has no editable slots: " + section.commandName());
        }
        return new OpenProfile(profileFile, playerName, scope.displayName(), normalizedMode, section, slots);
    }

    public void save(
            OpenProfile opened,
            Map<Integer, ItemStack> changedSlots,
            HolderLookup.Provider lookup) throws IOException {
        Objects.requireNonNull(opened, "opened");
        Objects.requireNonNull(changedSlots, "changedSlots");
        if (changedSlots.isEmpty()) {
            return;
        }
        Path file = opened.profileFile();
        requireContainedFile(file);
        JsonObject document = readDocument(file);
        JsonObject profile = profile(document, opened.gameMode(), file);
        List<Slot> latest = decodeSection(profile, opened.section(), lookup);
        for (Map.Entry<Integer, ItemStack> change : changedSlots.entrySet()) {
            int index = change.getKey();
            if (index < 0 || index >= latest.size()) {
                throw new IOException("MVInv slot layout changed while the recovery editor was open");
            }
            Slot slot = latest.get(index);
            writeSlot(profile, slot, Objects.requireNonNull(change.getValue(), "changed item"), lookup);
        }
        backupOnce(file);
        writeAtomically(file, GSON.toJson(document));
    }

    private List<Slot> decodeSection(
            JsonObject profile, Section section, HolderLookup.Provider lookup) throws IOException {
        if (section == Section.ALL) {
            List<Slot> result = new ArrayList<>();
            appendSection(result, profile, Section.INVENTORY, lookup);
            appendSection(result, profile, Section.ARMOR, lookup);
            appendSection(result, profile, Section.OFF_HAND, lookup);
            appendSection(result, profile, Section.ENDER_CHEST, lookup);
            return List.copyOf(result);
        }
        List<Slot> result = new ArrayList<>();
        appendSection(result, profile, section, lookup);
        return List.copyOf(result);
    }

    private void appendSection(
            List<Slot> target, JsonObject profile, Section section, HolderLookup.Provider lookup) throws IOException {
        String field = section.jsonField();
        if (section.scalar()) {
            JsonElement encoded = profile.get(field);
            DecodedItem item = decodeItem(encoded, lookup, field);
            target.add(new Slot(field, -1, item.stack(), item.failure()));
            return;
        }
        JsonElement raw = profile.get(field);
        JsonObject items;
        if (raw == null || raw.isJsonNull()) {
            items = new JsonObject();
        } else if (raw.isJsonObject()) {
            items = raw.getAsJsonObject();
        } else {
            throw new IOException("MVInv field '" + field + "' is not a slot object");
        }
        int size = section.minimumSize();
        for (String key : items.keySet()) {
            int slot = parseSlot(key, field);
            size = Math.max(size, Math.addExact(slot, 1));
        }
        if (size < 1 || size > MAX_SLOTS) {
            throw new IOException("MVInv field '" + field + "' has an unsafe slot count: " + size);
        }
        for (int slot = 0; slot < size; slot++) {
            DecodedItem item = decodeItem(items.get(Integer.toString(slot)), lookup,
                    field + '[' + slot + ']');
            target.add(new Slot(field, slot, item.stack(), item.failure()));
        }
    }

    private static void writeSlot(
            JsonObject profile, Slot slot, ItemStack stack, HolderLookup.Provider lookup) throws IOException {
        if (slot.slotIndex() < 0) {
            if (stack.isEmpty()) {
                profile.remove(slot.field());
            } else {
                profile.addProperty(slot.field(), encodeItem(stack, lookup));
            }
            return;
        }
        JsonElement raw = profile.get(slot.field());
        JsonObject items = raw != null && raw.isJsonObject() ? raw.getAsJsonObject() : new JsonObject();
        String key = Integer.toString(slot.slotIndex());
        if (stack.isEmpty()) {
            items.remove(key);
        } else {
            items.addProperty(key, encodeItem(stack, lookup));
        }
        profile.add(slot.field(), items);
    }

    private static DecodedItem decodeItem(
            JsonElement encoded, HolderLookup.Provider lookup, String context) throws IOException {
        if (encoded == null || encoded.isJsonNull()) {
            return new DecodedItem(ItemStack.EMPTY, "");
        }
        if (!encoded.isJsonPrimitive() || !encoded.getAsJsonPrimitive().isString()) {
            throw new IOException("MVInv " + context + " uses unsupported legacy Bukkit item serialization");
        }
        byte[] compressed;
        try {
            compressed = Base64.getDecoder().decode(encoded.getAsString());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("MVInv " + context + " is not valid Base64", invalid);
        }
        if (compressed.length == 0 || compressed.length > MAX_ITEM_BYTES) {
            throw new IOException("MVInv " + context + " item data exceeds the safety limit");
        }
        CompoundTag item;
        try {
            item = NbtIo.readCompressed(new ByteArrayInputStream(compressed), NbtAccounter.create(MAX_ITEM_BYTES));
        } catch (RuntimeException failure) {
            throw new IOException("MVInv " + context + " contains invalid compressed NBT", failure);
        }
        String itemId = item.getString("id");
        String[] codecFailure = new String[1];
        DataResult<ItemStack> decoded = ItemStack.CODEC.parse(lookup.createSerializationContext(NbtOps.INSTANCE), item);
        ItemStack stack = decoded.resultOrPartial(message -> codecFailure[0] = message).orElse(null);
        if (stack == null || stack.isEmpty()) {
            String reason = codecFailure[0] == null ? "unknown or invalid item" : codecFailure[0];
            String label = itemId.isBlank() ? context : itemId;
            return new DecodedItem(recoveryToken(item, label, reason), abbreviate(reason));
        }
        return new DecodedItem(stack, "");
    }

    private static String encodeItem(ItemStack stack, HolderLookup.Provider lookup) throws IOException {
        Tag raw = stack.save(lookup);
        if (!(raw instanceof CompoundTag encoded)) {
            throw new IOException("Minecraft item codec returned non-compound NBT");
        }
        encoded.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(encoded, output);
        if (output.size() > MAX_ITEM_BYTES) {
            throw new IOException("Edited MVInv item exceeds the safety limit");
        }
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private Path resolveContainer(Scope scope) throws IOException {
        Path base = root.resolve(scope.group() ? "groups" : "worlds").normalize();
        Path container = base.resolve(scope.name().replace('/', java.io.File.separatorChar)).normalize();
        if (!container.startsWith(base) || !Files.isDirectory(container)) {
            throw new IOException("MVInv " + (scope.group() ? "group" : "world")
                    + " profile directory does not exist: " + scope.name());
        }
        Path realBase = base.toRealPath();
        Path realContainer = container.toRealPath();
        if (!realContainer.startsWith(realBase)) {
            throw new IOException("MVInv profile directory escapes the configured data directory");
        }
        return realContainer;
    }

    private String resolvePlayerName(String input, Path container) throws IOException {
        String value = Objects.requireNonNull(input, "player").strip();
        try {
            UUID uuid = UUID.fromString(value);
            JsonObject names = readDocument(root.resolve("playernames.json"));
            JsonElement mapped = names.get(uuid.toString());
            if (mapped == null || !mapped.isJsonPrimitive()) {
                throw new IOException("No MVInv player name mapping exists for UUID " + uuid);
            }
            value = mapped.getAsString();
        } catch (IllegalArgumentException notUuid) {
            // Player names are accepted directly.
        }
        if (!PLAYER_NAME.matcher(value).matches()) {
            throw new IOException("Invalid MVInv player name: " + value);
        }
        Path exact = container.resolve(value + ".json");
        if (Files.isRegularFile(exact)) {
            return value;
        }
        String requestedName = value;
        try (var files = Files.list(container)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - 5))
                    .filter(name -> name.equalsIgnoreCase(requestedName))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "MVInv profile does not exist for player " + requestedName));
        }
    }

    private Path resolveExistingProfile(Path container, String playerName) throws IOException {
        Path file = container.resolve(playerName + ".json").normalize();
        if (!file.startsWith(container) || !Files.isRegularFile(file)) {
            throw new IOException("MVInv player profile does not exist: " + playerName);
        }
        Path real = file.toRealPath();
        if (!real.startsWith(container)) {
            throw new IOException("MVInv player profile escapes its container directory");
        }
        return real;
    }

    private JsonObject readDocument(Path file) throws IOException {
        requireContainedFile(file);
        if (!Files.isRegularFile(file)) {
            throw new IOException("MVInv data file does not exist: " + file.getFileName());
        }
        long size = Files.size(file);
        if (size < 2 || size > MAX_JSON_BYTES) {
            throw new IOException("MVInv data file size is invalid: " + file.getFileName());
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("MVInv data root is not an object: " + file.getFileName());
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException("Unable to parse MVInv data file " + file.getFileName(), invalid);
        }
    }

    private void requireContainedFile(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            if (!normalized.toRealPath().startsWith(root.toRealPath())) {
                throw new IOException("MVInv data path escapes the configured directory");
            }
        } else if (!normalized.startsWith(root)) {
            throw new IOException("MVInv data path escapes the configured directory");
        }
    }

    private void requireDataDirectory() throws IOException {
        if (!Files.isDirectory(root.resolve("worlds")) || !Files.isDirectory(root.resolve("groups"))) {
            throw new IOException("MVInv data directory is missing worlds/groups: " + root);
        }
    }

    private static JsonObject profile(JsonObject document, String gameMode, Path file) throws IOException {
        JsonElement raw = document.get(gameMode);
        if (raw == null || !raw.isJsonObject()) {
            throw new IOException("MVInv profile " + file.getFileName() + " has no " + gameMode + " data");
        }
        return raw.getAsJsonObject();
    }

    private static int parseSlot(String value, String field) throws IOException {
        try {
            int slot = Integer.parseInt(value);
            if (slot < 0 || slot >= MAX_SLOTS) {
                throw new NumberFormatException("out of range");
            }
            return slot;
        } catch (NumberFormatException invalid) {
            throw new IOException("MVInv field '" + field + "' has invalid slot key '" + value + "'", invalid);
        }
    }

    private static String normalizeGameMode(String gameMode) {
        String normalized = Objects.requireNonNull(gameMode, "gameMode").strip().toUpperCase(Locale.ROOT);
        if (!GAME_MODES.contains(normalized)) {
            throw new IllegalArgumentException("Game mode must be survival, creative, adventure, or spectator");
        }
        return normalized;
    }

    private static String abbreviate(String value) {
        return value.length() <= 512 ? value : value.substring(0, 509) + "...";
    }

    private static ItemStack recoveryToken(CompoundTag originalItem, String label, String failure) {
        ItemStack token = new ItemStack(Items.BARRIER);
        token.set(DataComponents.CUSTOM_NAME,
                Component.literal("MVInv recovery: " + label).withStyle(ChatFormatting.GOLD));
        token.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Original mod item NBT is preserved").withStyle(ChatFormatting.GRAY),
                Component.literal("Use /mvinvtoken restore after installing its mod/version")
                        .withStyle(ChatFormatting.YELLOW))));
        token.set(DataComponents.CUSTOM_DATA, CustomData.of(encodeRecoveryData(originalItem, failure)));
        return token;
    }

    static CompoundTag encodeRecoveryData(CompoundTag originalItem, String failure) {
        CompoundTag payload = new CompoundTag();
        payload.putInt("schema", RECOVERY_TOKEN_SCHEMA);
        payload.put("original_item", Objects.requireNonNull(originalItem, "originalItem").copy());
        payload.putString("failure", abbreviate(Objects.requireNonNull(failure, "failure")));
        CompoundTag customData = new CompoundTag();
        customData.put(RECOVERY_TOKEN_KEY, payload);
        return customData;
    }

    static RecoveryTokenData decodeRecoveryData(CompoundTag customData) {
        Objects.requireNonNull(customData, "customData");
        if (!(customData.get(RECOVERY_TOKEN_KEY) instanceof CompoundTag payload)
                || payload.getInt("schema") != RECOVERY_TOKEN_SCHEMA
                || !(payload.get("original_item") instanceof CompoundTag original)) {
            throw new IllegalArgumentException("The held MVInv recovery token is invalid or unsupported");
        }
        return new RecoveryTokenData(original, payload.getString("failure"));
    }

    public static ItemStack restoreToken(ItemStack token, HolderLookup.Provider lookup) {
        Objects.requireNonNull(token, "token");
        CustomData customData = token.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            throw new IllegalArgumentException("The held item is not an MVInv recovery token");
        }
        RecoveryTokenData data = decodeRecoveryData(customData.copyTag());
        String[] failure = new String[1];
        ItemStack restored = ItemStack.CODEC
                .parse(lookup.createSerializationContext(NbtOps.INSTANCE), data.originalItem())
                .resultOrPartial(message -> failure[0] = message)
                .orElse(null);
        if (restored == null || restored.isEmpty()) {
            throw new IllegalArgumentException("Original mod item is still unavailable: "
                    + (failure[0] == null ? data.failure() : failure[0]));
        }
        return restored;
    }

    private static void backupOnce(Path file) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".pre-yu-mwi-recovery.bak");
        try {
            Files.copy(file, backup);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Preserve the first untouched recovery source.
        }
    }

    private static void writeAtomically(Path file, String json) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public enum Section {
        ALL("all", "", 0, false),
        INVENTORY("inventory", "inventoryContents", 36, false),
        ENDER_CHEST("ender", "enderChestContents", 27, false),
        ARMOR("armor", "armorContents", 4, false),
        OFF_HAND("offhand", "offHandItem", 1, true);

        private final String commandName;
        private final String jsonField;
        private final int minimumSize;
        private final boolean scalar;

        Section(String commandName, String jsonField, int minimumSize, boolean scalar) {
            this.commandName = commandName;
            this.jsonField = jsonField;
            this.minimumSize = minimumSize;
            this.scalar = scalar;
        }

        public String commandName() {
            return commandName;
        }

        String jsonField() {
            return jsonField;
        }

        int minimumSize() {
            return minimumSize;
        }

        boolean scalar() {
            return scalar;
        }
    }

    public record OpenProfile(
            Path profileFile,
            String playerName,
            String sourceName,
            String gameMode,
            Section section,
            List<Slot> slots
    ) {
        public OpenProfile {
            profileFile = profileFile.toAbsolutePath().normalize();
            slots = List.copyOf(slots);
        }

        public List<ItemStack> items() {
            return slots.stream().map(slot -> slot.stack().copy()).toList();
        }

        public long recoveryTokenSlots() {
            return slots.stream().filter(slot -> !slot.failure().isEmpty()).count();
        }
    }

    public record Slot(String field, int slotIndex, ItemStack stack, String failure) {
        public Slot {
            stack = stack.copy();
            failure = Objects.requireNonNull(failure, "failure");
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }

    private record DecodedItem(ItemStack stack, String failure) {
        private DecodedItem {
            stack = stack.copy();
        }
    }

    record RecoveryTokenData(CompoundTag originalItem, String failure) {
        RecoveryTokenData {
            originalItem = originalItem.copy();
            failure = Objects.requireNonNull(failure, "failure");
        }

        @Override
        public CompoundTag originalItem() {
            return originalItem.copy();
        }
    }

    private record Scope(boolean group, String name) {
        private static Scope parse(String expression) {
            String normalized = Objects.requireNonNull(expression, "scope").strip();
            boolean group = normalized.regionMatches(true, 0, "group:", 0, 6);
            String name = group ? normalized.substring(6) : normalized;
            List<String> segments = List.of(name.split("/", -1));
            if (name.isBlank() || name.length() > 1_024 || name.indexOf('\0') >= 0
                    || name.indexOf('\\') >= 0 || name.startsWith("/")
                    || segments.stream().anyMatch(segment -> segment.isEmpty()
                            || segment.equals(".") || segment.equals(".."))) {
                throw new IllegalArgumentException("Invalid MVInv " + (group ? "group" : "world") + " name");
            }
            if (group && name.contains("/")) {
                throw new IllegalArgumentException("MVInv group names cannot contain '/'");
            }
            return new Scope(group, name);
        }

        private String displayName() {
            return group ? "group:" + name : name;
        }
    }
}
