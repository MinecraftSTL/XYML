/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.event.Event;
import space.minecraftstl.xyml.event.EventManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.modpack.ModAdviser;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.modpack.ModpackConfiguration;
import space.minecraftstl.xyml.modpack.ModpackProvider;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.observable.property.SimpleStringProperty;
import space.minecraftstl.xyml.observable.property.StringProperty;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.setting.DefaultIsolationType;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.ProxyType;
import space.minecraftstl.xyml.setting.SettingFileUtils;
import space.minecraftstl.xyml.setting.GameSettingsPresetID;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.Lang;
import space.minecraftstl.xyml.util.gson.JsonSchema;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.SystemInfo;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import space.minecraftstl.xyml.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static space.minecraftstl.xyml.setting.SettingsManager.settings;
import static space.minecraftstl.xyml.util.Pair.pair;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// XYML game repository implementation backed by a GameDirectory and per-instance game settings.
@NotNullByDefault
public final class XYMLGameRepository extends DefaultGameRepository {
    /// References an optional game instance in a repository.
    ///
    /// @param repository the owning game repository
    /// @param instanceId the game instance ID, or `null` when only repository context is available
    @NotNullByDefault
    public record InstanceReference(XYMLGameRepository repository, @Nullable String instanceId) {
    }

    /// Directory under the version root that stores XYML-managed instance metadata.
    private static final String INSTANCE_METADATA_DIRECTORY = ".xyml";

    /// Directory under the instance metadata directory that stores instance configuration files.
    private static final String INSTANCE_CONFIG_DIRECTORY = "config";

    /// Directory under the instance metadata directory that stores instance state files.
    private static final String INSTANCE_STATE_DIRECTORY = "state";

    /// Current file name for instance-specific game settings.
    private static final String INSTANCE_GAME_SETTINGS_FILENAME = "instance-game-settings.json";

    /// Image suffixes accepted for per-instance icon files.
    private static final @Unmodifiable List<String> ICON_EXTENSIONS = List.of("png", "jpg", "jpeg", "gif", "webp");

    /// The persistent game directory for this repository.
    private final GameDirectory gameDirectory;

    /// The selected instance ID persisted for this repository's game directory.
    private final StringProperty selectedInstance;

    /// Subscription that keeps the selected instance in sync with launcher settings.
    private final Subscription selectedInstanceSubscription;

    /// Toolkit-neutral selected-instance transitions for Swing and later core migration.
    private final ValueChangeSupport<String> selectedInstanceChanges = new ValueChangeSupport<>(this);

    /// Loaded instance settings indexed by instance ID.
    private final Map<String, GameSettings.Instance> instanceGameSettings = new HashMap<>();

    /// Instance IDs whose local game settings file has already been checked.
    private final Set<String> loadedInstanceGameSettings = new HashSet<>();

    /// Instance IDs whose newer settings schema must be preserved without writing.
    private final Set<String> readOnlyInstanceGameSettings = new HashSet<>();

    /// Instance IDs provisionally treated as modpacks while installation is in progress.
    private final Set<String> beingModpackInstances = new HashSet<>();

    /// Publishes changes to per-instance icon files.
    public final EventManager<Event> onInstanceIconChanged = new EventManager<>();

    /// Creates a repository backed by the given game directory.
    public XYMLGameRepository(GameDirectory gameDirectory) {
        super(gameDirectory.getPath().toPath());
        this.gameDirectory = gameDirectory;
        this.selectedInstance = new SimpleStringProperty(
                settings().getSelectedInstance(gameDirectory.getId()));
        this.selectedInstanceSubscription = settings().getSelectedInstance().subscribe(change -> {
            if (change.affectedKeys().contains(gameDirectory.getId())) {
                selectedInstance.set(settings().getSelectedInstance(gameDirectory.getId()));
            }
        });
        gameDirectory.pathProperty().subscribe(change -> changeDirectory(gameDirectory.getPath().toPath()));
    }

    /// Returns the persistent game directory for this repository.
    public GameDirectory getGameDirectory() {
        return gameDirectory;
    }

    /// Returns the selected instance ID property for this repository's game directory.
    public StringProperty selectedInstanceProperty() {
        return selectedInstance;
    }

    /// Returns the selected instance ID for this repository's game directory.
    public @Nullable String getSelectedInstance() {
        return selectedInstance.get();
    }

    /// Sets the selected instance ID for this repository's game directory.
    public void setSelectedInstance(@Nullable String instance) {
        @Nullable String previous = getSelectedInstance();
        settings().setSelectedInstance(gameDirectory.getId(), instance);
        selectedInstanceChanges.fireChange(previous, getSelectedInstance());
    }

    /// Registers for selected-instance transitions on the thread that changes the setting.
    ///
    /// @param listener selected-instance transition listener
    /// @return independently cancellable listener registration
    public Subscription subscribeSelectedInstance(ValueChangeListener<String> listener) {
        return selectedInstanceChanges.subscribe(listener);
    }

    /// Refreshes the selected instance ID after versions are loaded.
    public void refreshSelectedInstance() {
        @Nullable String selectedInstance = settings().getSelectedInstance(gameDirectory.getId());
        @Nullable String refreshedInstance = selectedInstance;
        if (!hasVersion(refreshedInstance)) {
            refreshedInstance = getInstances().isEmpty() ? null : getInstances().iterator().next().getId();
        }
        if (!Objects.equals(selectedInstance, refreshedInstance)) {
            setSelectedInstance(refreshedInstance);
        }
    }

    /// Returns a dependency manager using the currently selected download provider.
    public DefaultDependencyManager getDependency() {
        return getDependency(DownloadProviders.getDownloadProvider());
    }

    /// Returns a dependency manager using the given download provider.
    public DefaultDependencyManager getDependency(DownloadProvider downloadProvider) {
        return new DefaultDependencyManager(this, downloadProvider, XYMLCacheRepository.REPOSITORY);
    }

    /// Resolves the effective running directory for an instance.
    ///
    /// @param id instance ID
    /// @return isolated, custom, or repository-default running directory
    @Override
    public Path getRunDirectory(String id) {
        if (beingModpackInstances.contains(id) || isModpack(id)) {
            return getVersionRoot(id);
        }

        @Nullable GameSettings.Instance localSetting = getInstanceGameSettings(id);
        boolean useInstanceRunningDirectory =
                localSetting != null && localSetting.getOverrideProperties().contains(GameSettings.PROPERTY_RUNNING_DIRECTORY);

        String runningDirectory = getSelectedRunningDirectory(localSetting, useInstanceRunningDirectory);
        if (StringUtils.isBlank(runningDirectory)) {
            return useInstanceRunningDirectory ? getVersionRoot(id) : super.getRunDirectory(id);
        }

        try {
            return Path.of(runningDirectory);
        } catch (InvalidPathException ignored) {
            return getVersionRoot(id);
        }
    }

    /// Returns the running directory string selected by the current source.
    private String getSelectedRunningDirectory(
            @Nullable GameSettings.Instance localSetting,
            boolean useInstanceRunningDirectory) {
        if (useInstanceRunningDirectory) {
            if (localSetting == null) {
                return "";
            }

            //noinspection DataFlowIssue
            return Objects.requireNonNullElse(localSetting.runningDirectoryProperty().getValue(), "");
        }

        GameSettings.Preset parent = getParentGameSettings(localSetting);
        //noinspection DataFlowIssue
        return Objects.requireNonNullElse(parent.runningDirectoryProperty().getValue(), "");
    }

    /// Streams visible installed instances in release-time and version-number order.
    ///
    /// @return lazily filtered and sorted visible instance manifests
    public Stream<Version> getDisplayInstances() {
        return getInstances().stream()
                .filter(v -> !v.isHidden())
                .sorted(Comparator.comparing((Version v) -> Lang.requireNonNullElse(v.getReleaseTime(), Instant.EPOCH))
                        .thenComparing(v -> VersionNumber.asVersion(v.getId())));
    }

    /// Detects the Minecraft version from one already captured primary JAR path.
    ///
    /// This avoids resolving an instance ID against a newer repository revision during lazy row loading.
    ///
    /// @param primaryJar captured primary game JAR
    /// @return detected Minecraft version, or empty when the JAR cannot identify one
    public Optional<String> detectGameVersion(Path primaryJar) {
        return GameVersion.minecraftVersion(primaryJar);
    }

    /// Serializes scans for this repository because refresh rebuilding mutates non-concurrent caches.
    ///
    /// Separate game repositories may still refresh concurrently.
    @Override
    public synchronized void refreshInstances() {
        super.refreshInstances();
    }

    /// Rebuilds version and instance-setting caches, then creates the Forge-compatible profile file when needed.
    @Override
    protected void refreshInstancesImpl() {
        instanceGameSettings.clear();
        loadedInstanceGameSettings.clear();
        readOnlyInstanceGameSettings.clear();
        super.refreshInstancesImpl();
        instances.keySet().forEach(this::loadInstanceGameSettings);

        try {
            Path file = getBaseDirectory().resolve("launcher_profiles.json");
            if (!Files.exists(file) && !instances.isEmpty()) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, PROFILE);
            }
        } catch (IOException ex) {
            LOG.warning("Unable to create launcher_profiles.json, Forge/LiteLoader installer will not work.", ex);
        }
    }

    /// Switches the repository root and schedules an asynchronous version refresh.
    ///
    /// @param newDirectory new repository root
    public void changeDirectory(Path newDirectory) {
        setBaseDirectory(newDirectory);
        refreshInstancesAsync().start();
    }

    /// Removes crash reports and logs from one game directory.
    ///
    /// @param directory directory to clean
    /// @throws IOException if either generated directory cannot be removed
    private void clean(Path directory) throws IOException {
        FileUtils.deleteDirectory(directory.resolve("crash-reports"));
        FileUtils.deleteDirectory(directory.resolve("logs"));
    }

    /// Removes generated crash reports and logs from shared and instance running directories.
    ///
    /// @param id instance ID
    /// @throws IOException if generated files cannot be removed
    public void clean(String id) throws IOException {
        clean(getBaseDirectory());
        clean(getRunDirectory(id));
    }

    /// Removes an instance from disk and drops any cached instance settings for that instance.
    ///
    /// @param id instance ID
    /// @return whether the instance was removed from disk
    @Override
    public boolean removeInstanceFromDisk(String id) {
        boolean removed = super.removeInstanceFromDisk(id);
        if (removed) {
            instanceGameSettings.remove(id);
            loadedInstanceGameSettings.remove(id);
            readOnlyInstanceGameSettings.remove(id);
            beingModpackInstances.remove(id);
        }
        return removed;
    }

    /// Duplicates an instance and its selected data under a new ID.
    ///
    /// @param srcId source instance ID
    /// @param dstId destination instance ID
    /// @param copySaves whether save data should be copied
    /// @throws IOException if the destination already exists or copying fails
    public void duplicateInstance(String srcId, String dstId, boolean copySaves) throws IOException {
        Path srcDir = getVersionRoot(srcId);
        Path dstDir = getVersionRoot(dstId);

        Version fromVersion = getVersion(srcId);

        List<String> blackList = new ArrayList<>(ModAdviser.MODPACK_BLACK_LIST);
        blackList.add(srcId + ".jar");
        blackList.add(srcId + ".json");
        if (!copySaves)
            blackList.add("saves");

        if (Files.exists(dstDir)) throw new IOException("Instance exists");

        Files.createDirectories(dstDir);
        FileUtils.copyDirectory(srcDir, dstDir, path -> Modpack.acceptFile(path, blackList, null));

        Path fromJson = srcDir.resolve(srcId + ".json");
        Path fromJar = srcDir.resolve(srcId + ".jar");
        Path toJson = dstDir.resolve(dstId + ".json");
        Path toJar = dstDir.resolve(dstId + ".jar");

        if (Files.exists(fromJar)) {
            Files.copy(fromJar, toJar);
        }
        Files.copy(fromJson, toJson);

        JsonUtils.writeToJsonFile(toJson, fromVersion.setId(dstId).setJar(dstId));

        boolean copyOriginalGameDir;
        try {
            copyOriginalGameDir = !Files.isSameFile(getRunDirectory(srcId), getVersionRoot(srcId));
        } catch (IOException e) {
            copyOriginalGameDir = true;
        }

        Path srcGameDir = getRunDirectory(srcId);

        GameSettings.Instance newGameSettings = copyInstanceGameSettings(srcId);
        newGameSettings.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
        newGameSettings.runningDirectoryProperty().setValue("");
        initInstanceGameSettings(dstId, newGameSettings);
        saveGameSettingsSync(dstId);

        Path dstGameDir = getRunDirectory(dstId);

        if (copyOriginalGameDir)
            FileUtils.copyDirectory(srcGameDir, dstGameDir, path -> Modpack.acceptFile(path, blackList, null));
    }

    /// Copies explicit instance settings or derives a new instance from the effective parent preset.
    ///
    /// @param id source instance ID
    /// @return independent mutable settings for a duplicate
    private GameSettings.Instance copyInstanceGameSettings(String id) {
        @Nullable GameSettings.Instance setting = getInstanceGameSettings(id);
        if (setting != null) {
            return JsonUtils.clone(LauncherSettings.SETTINGS_GSON, setting, TypeToken.get(GameSettings.Instance.class));
        }

        GameSettings.Instance copied = new GameSettings.Instance();
        copied.parentProperty().setValue(getEffectiveGameSettings(id).getPreset().idProperty().getValue());
        return copied;
    }

    /// Returns the XYML-managed metadata directory under the version root.
    ///
    /// This directory stores instance-scoped files owned by XYML.
    public Path getInstanceMetadataDirectory(String id) {
        return getVersionRoot(id).resolve(INSTANCE_METADATA_DIRECTORY);
    }

    /// Returns the XYML-managed configuration directory under the instance metadata directory.
    public Path getInstanceConfigDirectory(String id) {
        return getInstanceMetadataDirectory(id).resolve(INSTANCE_CONFIG_DIRECTORY);
    }

    /// Returns the XYML-managed state directory under the instance metadata directory.
    public Path getInstanceStateDirectory(String id) {
        return getInstanceMetadataDirectory(id).resolve(INSTANCE_STATE_DIRECTORY);
    }

    /// Returns the current local game settings path under the instance configuration directory.
    private Path getInstanceGameSettingsFile(String id) {
        return getInstanceConfigDirectory(id).resolve(INSTANCE_GAME_SETTINGS_FILENAME);
    }

    /// Loads current instance settings into the in-memory cache once.
    ///
    /// @param id instance ID
    private void loadInstanceGameSettings(String id) {
        loadedInstanceGameSettings.add(id);
        InstanceGameSettingsLoadResult result = loadGameSettingsFile(getInstanceGameSettingsFile(id));
        if (result.setting() != null) {
            initInstanceGameSettings(id, result.setting(), result.allowSave());
            return;
        }
        if (!result.allowSave()) {
            readOnlyInstanceGameSettings.add(id);
        }
    }

    /// Loads a current-format instance game settings file.
    private InstanceGameSettingsLoadResult loadGameSettingsFile(Path file) {
        if (!Files.exists(file)) {
            return new InstanceGameSettingsLoadResult(null, true);
        }

        try {
            @Nullable JsonObject jsonObject = JsonUtils.fromJsonFile(LauncherSettings.SETTINGS_GSON, file, JsonObject.class);
            if (jsonObject == null) {
                LOG.warning("Instance game settings are empty: " + file);
                GameSettings.Instance fallback = new GameSettings.Instance();
                return new InstanceGameSettingsLoadResult(fallback, true);
            }

            JsonSchema.CompatibilityResult schemaResult =
                    JsonSchema.check(jsonObject, GameSettings.Instance.CURRENT_SCHEMA);
            switch (schemaResult.status()) {
                case MISSING -> LOG.warning("Missing schema in instance game settings: " + file);
                case INVALID -> LOG.warning("Invalid schema in instance game settings: "
                        + file + ", Actual: " + schemaResult.invalidValue());
                case UNPARSEABLE -> LOG.warning("Unparseable schema in instance game settings: "
                        + file + ", Actual: " + schemaResult.actual());
                case UNEXPECTED_ID -> LOG.warning("Unexpected instance game settings schema. Expected: "
                        + GameSettings.Instance.CURRENT_SCHEMA + ", Actual: " + schemaResult.actual());
                case UNSUPPORTED_MAJOR, READ_ONLY_PRESERVE_SCHEMA ->
                    LOG.warning("Unsupported instance game settings schema. Expected: "
                                + GameSettings.Instance.CURRENT_SCHEMA + ", Actual: " + schemaResult.actual());
                case READ_WRITE, READ_WRITE_PRESERVE_SCHEMA -> {
                }
            }
            if (!schemaResult.readable()) {
                GameSettings.Instance fallback = new GameSettings.Instance();
                fallback.setSavable(false);
                return new InstanceGameSettingsLoadResult(fallback, false);
            }

            @Nullable GameSettings.Instance setting =
                    LauncherSettings.SETTINGS_GSON.fromJson(jsonObject, GameSettings.Instance.class);
            if (setting == null) {
                LOG.warning("Instance game settings deserialized to null: " + file);
                GameSettings.Instance fallback = new GameSettings.Instance();
                fallback.setBackupOnNextSave(true);
                return new InstanceGameSettingsLoadResult(fallback, true);
            }
            if (!schemaResult.preserveSchema() && !GameSettings.Instance.CURRENT_SCHEMA.equals(setting.getSchema())) {
                setting.setSchema(GameSettings.Instance.CURRENT_SCHEMA);
            }
            return new InstanceGameSettingsLoadResult(setting, schemaResult.allowSave());
        } catch (JsonParseException ex) {
            LOG.warning("Failed to parse game setting " + file, ex);
            GameSettings.Instance fallback = new GameSettings.Instance();
            fallback.setBackupOnNextSave(true);
            return new InstanceGameSettingsLoadResult(fallback, true);
        } catch (Exception ex) {
            LOG.warning("Failed to load game setting " + file, ex);
            return new InstanceGameSettingsLoadResult(null, false);
        }
    }

    /// Creates writable settings for an existing instance when none are loaded.
    ///
    /// @param id instance ID
    /// @return the created settings, existing settings, or `null` for an unknown or read-only instance
    public @Nullable GameSettings.Instance createInstanceGameSettings(String id) {
        if (!hasVersion(id)) {
            return null;
        }
        if (readOnlyInstanceGameSettings.contains(id)) {
            return null;
        }
        if (instanceGameSettings.containsKey(id)) {
            return getInstanceGameSettings(id);
        }

        GameSettings.Instance setting = new GameSettings.Instance();
        return initInstanceGameSettings(id, setting);
    }

    /// Registers writable instance settings and their auto-save listener.
    ///
    /// @param id instance ID
    /// @param setting settings to register
    /// @return the registered settings
    private GameSettings.Instance initInstanceGameSettings(String id, GameSettings.Instance setting) {
        return initInstanceGameSettings(id, setting, true);
    }

    /// Registers instance settings with the requested persistence policy.
    ///
    /// @param id instance ID
    /// @param setting settings to register
    /// @param allowSave whether changes may overwrite the settings file
    /// @return the registered settings
    private GameSettings.Instance initInstanceGameSettings(String id, GameSettings.Instance setting, boolean allowSave) {
        setting.setSavable(allowSave);
        loadedInstanceGameSettings.add(id);
        instanceGameSettings.put(id, setting);
        if (allowSave) {
            readOnlyInstanceGameSettings.remove(id);
            setting.changes().subscribe(change -> saveGameSettings(id));
        } else {
            readOnlyInstanceGameSettings.add(id);
        }
        return setting;
    }

    /// Returns loaded settings for an instance, loading them on first access.
    ///
    /// @param id instance ID
    /// @return loaded settings, or `null` when no settings exist
    @Nullable
    public GameSettings.Instance getInstanceGameSettings(String id) {
        if (!loadedInstanceGameSettings.contains(id)) {
            loadInstanceGameSettings(id);
        }
        return instanceGameSettings.get(id);
    }

    /// Returns existing instance settings or creates writable defaults when possible.
    ///
    /// @param id instance ID
    /// @return instance settings, or `null` for an unknown or read-only instance
    @Nullable
    public GameSettings.Instance getInstanceGameSettingsOrCreate(String id) {
        @Nullable GameSettings.Instance setting = getInstanceGameSettings(id);
        if (setting == null) {
            setting = createInstanceGameSettings(id);
        }
        return setting;
    }

    /// Returns whether the instance-specific game settings file cannot be overwritten safely.
    ///
    /// @param id the instance ID
    /// @return whether the instance settings are loaded in read-only mode
    public boolean isInstanceGameSettingsReadOnly(String id) {
        if (!loadedInstanceGameSettings.contains(id)) {
            loadInstanceGameSettings(id);
        }

        return readOnlyInstanceGameSettings.contains(id);
    }

    /// Backs up and overwrites the instance-specific game settings file with the currently loaded settings.
    ///
    /// @param id the instance ID
    public void forceOverwriteInstanceGameSettings(String id) {
        if (!loadedInstanceGameSettings.contains(id)) {
            loadInstanceGameSettings(id);
        }

        @Nullable GameSettings.Instance setting = instanceGameSettings.get(id);
        if (setting == null) {
            setting = new GameSettings.Instance();
            instanceGameSettings.put(id, setting);
            loadedInstanceGameSettings.add(id);
        }

        boolean installAutoSave = !setting.isSavable();
        Path file = getInstanceGameSettingsFile(id).toAbsolutePath().normalize();
        SettingFileUtils.backupInvalidConfig(file);
        setting.setSchema(GameSettings.Instance.CURRENT_SCHEMA);
        setting.setSavable(true);
        setting.setBackupOnNextSave(false);
        readOnlyInstanceGameSettings.remove(id);
        saveGameSettings(id);
        if (installAutoSave) {
            setting.changes().subscribe(change -> saveGameSettings(id));
        }
    }

    /// Returns the explicit parent preset of the instance, falling back to the default preset.
    public GameSettings.Preset getParentGameSettings(@Nullable GameSettings.Instance instance) {
        @Nullable GameSettingsPresetID parent = instance != null ? instance.parentProperty().getValue() : null;
        @Nullable GameSettings.Preset parentSetting = SettingsManager.getGameSettings(parent);
        return parentSetting != null ? parentSetting : SettingsManager.getDefaultGameSettingsPresetOrCreate();
    }

    /// Returns whether a new instance should use an isolated running directory under the default isolation settings.
    public boolean shouldIsolateNewInstance(boolean modded) {
        GameSettings.Preset preset = getParentGameSettings(null);
        DefaultIsolationType type = Lang.requireNonNullElse(preset.defaultIsolationTypeProperty().getValue(), DefaultIsolationType.MODDED);
        return switch (type) {
            case NEVER -> false;
            case ALWAYS -> true;
            case MODDED -> modded;
        };
    }

    /// Applies default isolation to a new instance before the version metadata is saved.
    public void applyDefaultIsolationSettingForNewInstance(String id, boolean modded) {
        if (!shouldIsolateNewInstance(modded) || readOnlyInstanceGameSettings.contains(id)) {
            return;
        }

        @Nullable GameSettings.Instance setting = getInstanceGameSettings(id);
        if (setting == null) {
            setting = initInstanceGameSettings(id, new GameSettings.Instance());
        }
        if (setting.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY)) {
            saveGameSettings(id);
        }
    }

    /// Resolves all effective game settings for an instance.
    ///
    /// @param id instance ID
    /// @return effective settings combining the parent preset and local overrides
    public GameSettings.Effective getEffectiveGameSettings(String id) {
        @Nullable GameSettings.Instance instance = getInstanceGameSettings(id);
        return GameSettings.resolve(getParentGameSettings(instance), instance);
    }

    /// Finds the first supported icon file for an instance.
    ///
    /// @param id instance ID
    /// @return existing icon path, or empty when no icon is stored
    public Optional<Path> getInstanceIconFile(String id) {
        Path root = getVersionRoot(id);

        for (String extension : ICON_EXTENSIONS) {
            Path file = root.resolve("icon." + extension);
            if (Files.exists(file)) {
                return Optional.of(file);
            }
        }

        return Optional.empty();
    }

    /// Replaces an instance icon with a supported image file.
    ///
    /// @param id instance ID
    /// @param iconFile source image file
    /// @throws IOException if copying the icon fails
    /// @throws IllegalArgumentException if the image suffix is unsupported
    public void setInstanceIconFile(String id, Path iconFile) throws IOException {
        String ext = FileUtils.getExtension(iconFile).toLowerCase(Locale.ROOT);
        if (!ICON_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported icon file: " + ext);
        }

        deleteIconFile(id);

        FileUtils.copyFile(iconFile, getVersionRoot(id).resolve("icon." + ext));
    }

    /// Deletes every supported icon variant for an instance, logging individual failures.
    ///
    /// @param id instance ID
    public void deleteIconFile(String id) {
        Path root = getVersionRoot(id);
        for (String extension : ICON_EXTENSIONS) {
            Path file = root.resolve("icon." + extension);
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.warning("Failed to delete icon file: " + file, e);
            }
        }
    }

    /// Queues writable instance settings for safe asynchronous persistence.
    ///
    /// @param id instance ID
    public void saveGameSettings(String id) {
        if (!instanceGameSettings.containsKey(id) || readOnlyInstanceGameSettings.contains(id))
            return;
        @Nullable GameSettings.Instance setting = instanceGameSettings.get(id);
        if (setting == null) {
            return;
        }
        Path file = getInstanceGameSettingsFile(id).toAbsolutePath().normalize();
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            LOG.warning("Failed to create directory: " + file.getParent(), e);
        }

        if (setting.isBackupOnNextSave()) {
            setting.setBackupOnNextSave(false);
            SettingFileUtils.backupInvalidConfig(file);
        }
        FileSaver.save(file, LauncherSettings.SETTINGS_GSON.toJson(setting));
    }

    /// Saves instance-specific game settings synchronously.
    ///
    /// @param id the instance ID
    /// @throws IOException if saving the file fails
    private void saveGameSettingsSync(String id) throws IOException {
        if (!instanceGameSettings.containsKey(id) || readOnlyInstanceGameSettings.contains(id)) {
            return;
        }

        @Nullable GameSettings.Instance setting = instanceGameSettings.get(id);
        if (setting == null) {
            return;
        }

        Path file = getInstanceGameSettingsFile(id).toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        if (setting.isBackupOnNextSave()) {
            setting.setBackupOnNextSave(false);
            SettingFileUtils.backupInvalidConfig(file);
        }
        FileUtils.saveSafely(file, LauncherSettings.SETTINGS_GSON.toJson(setting));
    }

    /// Result of loading an instance-specific game settings file.
    ///
    /// @param setting   the loaded instance settings, or `null` when unavailable
    /// @param allowSave whether the file may be overwritten
    @NotNullByDefault
    private record InstanceGameSettingsLoadResult(
            @Nullable GameSettings.Instance setting,
            boolean allowSave) {
    }

    /// Builds game launch options from effective settings and modpack metadata.
    ///
    /// @param instanceId target installed instance identifier
    /// @param javaVersion selected Java runtime
    /// @param gameDir effective running directory
    /// @param javaAgents additional Java agent arguments
    /// @param javaArguments additional JVM arguments
    /// @param makeLaunchScript whether the result is intended for a standalone launch script
    /// @return populated launch-options builder
    public LaunchOptions.Builder getLaunchOptions(
            String instanceId,
            JavaRuntime javaVersion,
            Path gameDir,
            List<String> javaAgents,
            List<String> javaArguments,
            boolean makeLaunchScript) {
        GameSettings.Effective vs = getEffectiveGameSettings(instanceId);
        boolean noJVMOptions = vs.getInheritable(GameSettings::noJVMOptionsProperty);
        boolean autoMemory = vs.getInheritable(GameSettings::autoMemoryProperty);
        GameVersionNumber gameVersionNumber = GameVersionNumber.asGameVersion(getGameVersion(instanceId));

        @Nullable Integer maxMemory;
        if (autoMemory) {
            maxMemory = noJVMOptions
                    ? null
                    : Math.toIntExact(getAutoAllocatedMemory(SystemInfo.getPhysicalMemoryStatus().available()) / 1024L / 1024L);
        } else {
            maxMemory = vs.getMaxMemory();
        }

        LaunchOptions.Builder builder = new LaunchOptions.Builder()
                .setGameDir(gameDir)
                .setJava(javaVersion)
                .setVersionType(Metadata.TITLE)
                .setVersionName(instanceId)
                .setProfileName(Metadata.TITLE)
                .setGameArguments(StringUtils.tokenize(vs.getInheritable(GameSettings::gameArgumentsProperty)))
                .setOverrideJavaArguments(StringUtils.tokenize(vs.getInheritable(GameSettings::jvmOptionsProperty)))
                .setMaxMemory(maxMemory)
                .setMinMemory(vs.getInheritable(GameSettings::minMemoryProperty))
                .setMetaspace(Lang.toIntOrNull(vs.getInheritable(GameSettings::permSizeProperty)))
                .setEnvironmentVariables(
                        Lang.mapOf(StringUtils.tokenize(vs.getInheritable(GameSettings::environmentVariablesProperty))
                                .stream()
                                .map(it -> {
                                    int idx = it.indexOf('=');
                                    return idx >= 0 ? pair(it.substring(0, idx), it.substring(idx + 1)) : pair(it, "");
                                })
                                .collect(Collectors.toList())
                        )
                )
                .setWidth(vs.getWidth())
                .setHeight(vs.getHeight())
                .setFullscreen(vs.getInheritable(GameSettings::windowTypeProperty) == GameWindowType.FULLSCREEN)
                .setWrapper(vs.getInheritable(GameSettings::commandWrapperProperty))
                .setProxyOption(getProxyOption())
                .setPreLaunchCommand(vs.getInheritable(GameSettings::preLaunchCommandProperty))
                .setPostExitCommand(vs.getInheritable(GameSettings::postExitCommandProperty))
                .setNoGeneratedJVMArgs(noJVMOptions)
                .setNoGeneratedOptimizingJVMArgs(vs.getInheritable(GameSettings::noOptimizingJVMOptionsProperty))
                .setUseCustomNatives(vs.getInheritable(GameSettings::useCustomNativesProperty))
                .setNativesDir(vs.getInheritable(GameSettings::nativesDirectoryProperty))
                .setProcessPriority(vs.getInheritable(GameSettings::processPriorityProperty))
                .setGraphicsBackend(vs.getInheritable(GameSettings::graphicsBackendProperty))
                .setRenderer(vs.getRenderer(gameVersionNumber))
                .setEnableDebugLogOutput(vs.getInheritable(GameSettings::enableDebugLogOutputProperty))
                .setAllowAutoAgent(vs.getInheritable(GameSettings::allowAutoAgentProperty))
                .setDisableAutoGameOptions(vs.getInheritable(GameSettings::disableAutoGameOptionsProperty))
                .setUseNativeGLFW(vs.getInheritable(GameSettings::useNativeGLFWProperty))
                .setUseNativeOpenAL(vs.getInheritable(GameSettings::useNativeOpenALProperty))
                .setDaemon(!makeLaunchScript && vs.getInheritable(GameSettings::launcherVisibilityProperty).isDaemon())
                .setJavaAgents(javaAgents)
                .setJavaArguments(javaArguments);

        @Nullable QuickPlayOption quickPlayOption = vs.getQuickPlayOption();
        if (quickPlayOption != null) {
            builder.setQuickPlayOption(quickPlayOption);
        }

        Path json = getModpackConfiguration(instanceId);
        if (Files.exists(json)) {
            try {
                String jsonText = Files.readString(json);
                @Nullable ModpackConfiguration<?> modpackConfiguration = JsonUtils.GSON.fromJson(jsonText, ModpackConfiguration.class);
                @Nullable ModpackProvider provider = ModpackHelper.getProviderByType(modpackConfiguration.getType());
                if (provider != null) provider.injectLaunchOptions(jsonText, builder);
            } catch (IOException | JsonParseException e) {
                LOG.warning("Failed to parse modpack configuration file " + json, e);
            }
        }

        if (autoMemory && builder.getJavaArguments().stream().anyMatch(it -> it.startsWith("-Xmx")))
            builder.setMaxMemory(null);

        return builder;
    }

    /// Returns the persisted modpack configuration path for an instance.
    ///
    /// @param instanceId target installed instance identifier
    /// @return modpack configuration path
    @Override
    public Path getModpackConfiguration(String instanceId) {
        return getVersionRoot(instanceId).resolve("modpack.cfg");
    }

    /// Marks an instance as a modpack while installation is in progress.
    ///
    /// @param id instance ID
    public void markInstanceAsModpack(String id) {
        beingModpackInstances.add(id);
    }

    /// Clears an instance's provisional modpack installation mark.
    ///
    /// @param id instance ID
    public void undoMark(String id) {
        beingModpackInstances.remove(id);
    }

    /// Persists an abnormal-exit marker for an instance.
    ///
    /// @param id instance ID
    public void markInstanceLaunchedAbnormally(String id) {
        try {
            Files.createFile(getVersionRoot(id).resolve(".abnormal"));
        } catch (IOException ignored) {
        }
    }

    /// Removes an instance's abnormal-exit marker.
    ///
    /// @param id instance ID
    /// @return whether an abnormal marker existed
    public boolean unmarkInstanceLaunchedAbnormally(String id) {
        Path file = getVersionRoot(id).resolve(".abnormal");
        if (Files.isRegularFile(file)) {
            try {
                Files.delete(file);
            } catch (IOException e) {
                LOG.warning("Failed to delete abnormal mark file: " + file, e);
            }

            return true;
        } else {
            return false;
        }
    }

    /// Minimal launcher profile created for installers that require the vanilla profile file.
    private static final String PROFILE = "{\"selectedProfile\": \"(Default)\",\"profiles\": {\"(Default)\": {\"name\": \"(Default)\"}},\"clientToken\": \"88888888-8888-8888-8888-888888888888\"}";

    /// Instance IDs forbidden because they conflict with modpack configuration file names.
    private static final @Unmodifiable Set<String> FORBIDDEN_INSTANCE_IDS = Set.of(
            "modpack", "minecraftinstance", "manifest");

    /// Returns whether an instance ID is filesystem-safe and avoids reserved modpack names.
    ///
    /// @param id candidate instance ID
    /// @return whether the ID is valid
    public static boolean isValidInstanceId(String id) {
        if (FORBIDDEN_INSTANCE_IDS.contains(id))
            return false;

        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS &&
                FORBIDDEN_INSTANCE_IDS.contains(id.toLowerCase(Locale.ROOT)))
            return false;

        return FileUtils.isNameValidForJar(id);
    }

    /// Returns whether an instance ID conflicts with an existing installed instance.
    ///
    /// @param id candidate instance ID
    /// @return whether an existing instance uses the ID, case-insensitively on Windows
    public boolean instanceIdConflicts(String id) {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            // on Windows, filenames are case-insensitive
            for (String existingId : instances.keySet()) {
                if (existingId.equalsIgnoreCase(id)) {
                    return true;
                }
            }
            return false;
        } else {
            return instances.containsKey(id);
        }
    }

    /// Calculates the recommended game heap from currently available physical memory.
    ///
    /// @param available available physical memory in bytes
    /// @return recommended heap size in bytes
    public static long getAutoAllocatedMemory(long available) {
        long usable = available - 512 * 1024 * 1024; // Reserve 512 MiB memory for off-heap memory and XYML itself
        if (usable <= 0) {
            return available;
        }

        final long threshold = 8L * 1024 * 1024 * 1024; // 8 GiB
        final long suggested;
        if (usable <= threshold)
            suggested = (long) (usable * 0.8);
        else
            suggested = Math.min(
                    (long) (threshold * 0.8 + (usable - threshold) * 0.2),
                    16L * 1024 * 1024 * 1024);
        return suggested;
    }

    /// Builds the launch proxy option from validated launcher settings.
    ///
    /// @return direct, system, HTTP, or SOCKS proxy configuration
    public static ProxyOption getProxyOption() {
        return switch (settings().proxyTypeProperty().get()) {
            case SYSTEM -> ProxyOption.Default.INSTANCE;
            case DIRECT -> ProxyOption.Direct.INSTANCE;
            case HTTP, SOCKS -> {
                @Nullable String proxyHost = settings().proxyHostProperty().get();
                int proxyPort = settings().proxyPortProperty().get();

                if (StringUtils.isBlank(proxyHost) || proxyPort < 0 || proxyPort > 0xFFFF) {
                    yield ProxyOption.Default.INSTANCE;
                }

                @Nullable String proxyUser = settings().proxyUserProperty().get();
                @Nullable String proxyPass = settings().proxyPasswordProperty().get();

                if (StringUtils.isBlank(proxyUser)) {
                    proxyUser = null;
                    proxyPass = null;
                } else if (proxyPass == null) {
                    proxyPass = "";
                }

                if (settings().proxyTypeProperty().get() == ProxyType.HTTP) {
                    yield new ProxyOption.Http(proxyHost, proxyPort, proxyUser, proxyPass);
                } else {
                    yield new ProxyOption.Socks(proxyHost, proxyPort, proxyUser, proxyPass);
                }
            }
        };
    }
}
