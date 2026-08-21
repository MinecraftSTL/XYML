/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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

import com.google.gson.JsonParseException;
import kala.compress.archivers.zip.ZipArchiveReader;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.modpack.*;
import space.minecraftstl.xyml.modpack.curse.CurseModpackProvider;
import space.minecraftstl.xyml.modpack.mcbbs.McbbsModpackManifest;
import space.minecraftstl.xyml.modpack.mcbbs.McbbsModpackProvider;
import space.minecraftstl.xyml.modpack.modrinth.ModrinthModpackProvider;
import space.minecraftstl.xyml.modpack.multimc.MultiMCComponents;
import space.minecraftstl.xyml.modpack.multimc.MultiMCInstanceConfiguration;
import space.minecraftstl.xyml.modpack.multimc.MultiMCModpackProvider;
import space.minecraftstl.xyml.modpack.server.ServerModpackManifest;
import space.minecraftstl.xyml.modpack.server.ServerModpackProvider;
import space.minecraftstl.xyml.modpack.server.ServerModpackRemoteInstallTask;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameWindowType;
import space.minecraftstl.xyml.setting.JavaVersionType;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.Lang;
import space.minecraftstl.xyml.util.PortablePath;
import space.minecraftstl.xyml.util.function.ExceptionalConsumer;
import space.minecraftstl.xyml.util.function.ExceptionalRunnable;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.i18n.LocalizedText;
import space.minecraftstl.xyml.util.io.CompressingUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.tree.ArchiveFileTree;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static space.minecraftstl.xyml.util.Lang.mapOf;
import static space.minecraftstl.xyml.util.Pair.pair;

/// Utilities for reading, installing, and applying modpack-specific game settings.
@NotNullByDefault
public final class ModpackHelper {
    /// Prevents instantiation of this utility class.
    private ModpackHelper() {
    }

    /// Modpack providers indexed by their persisted type names.
    private static final Map<String, ModpackProvider> providers = mapOf(
            pair(CurseModpackProvider.INSTANCE.getName(), CurseModpackProvider.INSTANCE),
            pair(McbbsModpackProvider.INSTANCE.getName(), McbbsModpackProvider.INSTANCE),
            pair(ModrinthModpackProvider.INSTANCE.getName(), ModrinthModpackProvider.INSTANCE),
            pair(MultiMCModpackProvider.INSTANCE.getName(), MultiMCModpackProvider.INSTANCE),
            pair(ServerModpackProvider.INSTANCE.getName(), ServerModpackProvider.INSTANCE),
            pair(XYMLModpackProvider.INSTANCE.getName(), XYMLModpackProvider.INSTANCE)
    );

    static {
        MultiMCComponents.setImplementation(Metadata.FULL_TITLE);
    }

    /// Finds the provider registered for a persisted modpack type.
    ///
    /// @param type persisted provider type
    /// @return the matching provider, or `null` when the type is unknown
    @Nullable
    public static ModpackProvider getProviderByType(String type) {
        return providers.get(type);
    }

    /// Returns whether a file suffix identifies a supported archive modpack.
    ///
    /// @param file candidate archive path
    /// @return whether the suffix is supported
    public static boolean isFileModpackByExtension(Path file) {
        String ext = FileUtils.getExtension(file);
        return "zip".equals(ext) || "mrpack".equals(ext);
    }

    /// Reads a supported modpack manifest from an archive.
    ///
    /// @param file archive path
    /// @param charset archive entry-name charset
    /// @return parsed modpack
    /// @throws UnsupportedModpackException if no supported manifest or manual game directory is found
    /// @throws ManuallyCreatedModpackException if the archive contains a manually assembled game directory
    public static Modpack readModpackManifest(Path file, Charset charset) throws UnsupportedModpackException, ManuallyCreatedModpackException {
        try (ZipArchiveReader zipFile = CompressingUtils.openZipFile(file, charset)) {
            // Order for trying detecting manifest is necessary here.
            // Do not change to iterating providers.
            for (ModpackProvider provider : new ModpackProvider[]{
                    McbbsModpackProvider.INSTANCE,
                    CurseModpackProvider.INSTANCE,
                    ModrinthModpackProvider.INSTANCE,
                    XYMLModpackProvider.INSTANCE,
                    MultiMCModpackProvider.INSTANCE,
                    ServerModpackProvider.INSTANCE}) {
                try {
                    return provider.readManifest(zipFile, file, charset);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }

        try {
            findMinecraftDirectoryInManuallyCreatedModpack(file.toString(), file);
            throw new ManuallyCreatedModpackException(file);
        } catch (IOException e) {
            // ignore it
        }

        throw new UnsupportedModpackException(file.toString());
    }

    /// Locates the Minecraft directory in a manually assembled modpack archive.
    ///
    /// @param modpackName name used when reporting an unsupported archive
    /// @param zipPath archive path to inspect
    /// @return relative archive directory containing the `versions` directory, or an empty string for the root
    /// @throws IOException if the archive cannot be inspected
    /// @throws UnsupportedModpackException if no Minecraft directory is found within two levels
    public static String findMinecraftDirectoryInManuallyCreatedModpack(String modpackName, Path zipPath)
            throws IOException, UnsupportedModpackException {
        try (ArchiveFileTree<?, ?> tree = ArchiveFileTree.open(zipPath)) {
            ArchiveFileTree.Dir<?> root = tree.getRoot();
            if (isMinecraftDirectory(root)) {
                return "";
            }

            for (ArchiveFileTree.Dir<?> firstLayer : root.getSubDirs().values()) {
                if (isMinecraftDirectory(firstLayer)) {
                    return firstLayer.getName();
                }

                for (ArchiveFileTree.Dir<?> secondLayer : firstLayer.getSubDirs().values()) {
                    if (isMinecraftDirectory(secondLayer)) {
                        return firstLayer.getName() + "/" + secondLayer.getName();
                    }
                }
            }
        }
        throw new UnsupportedModpackException(modpackName);
    }

    /// Returns whether an archive directory has the structure of a Minecraft installation.
    ///
    /// @param directory candidate archive directory
    /// @return whether it contains a `versions` directory and has an accepted root name
    private static boolean isMinecraftDirectory(ArchiveFileTree.Dir<?> directory) {
        return directory.getSubDirs().containsKey("versions")
                && (directory.isRoot() || ".minecraft".equals(directory.getName()));
    }

    /// Reads persisted modpack configuration from JSON.
    ///
    /// @param file configuration file
    /// @return parsed modpack configuration
    /// @throws IOException if the file cannot be read or contains malformed JSON
    public static ModpackConfiguration<?> readModpackConfiguration(Path file) throws IOException {
        try {
            return JsonUtils.fromJsonFile(file, ModpackConfiguration.class);
        } catch (JsonParseException e) {
            throw new IOException("Malformed modpack configuration");
        }
    }

    /// Creates a task that installs a remote server modpack and updates repository state afterward.
    ///
    /// @param repository destination game repository
    /// @param manifest remote server manifest
    /// @param instanceId destination instance identifier
    /// @param modpack modpack metadata retained for API symmetry with archive installs
    /// @return configured installation task
    public static Task<?> getInstallTask(
            XYMLGameRepository repository,
            ServerModpackManifest manifest,
            GameInstanceID instanceId,
            Modpack modpack) {
        repository.markInstanceAsModpack(instanceId);

        ExceptionalRunnable<?> success = () -> {
            repository.refresh();
            @Nullable GameSettings.Instance setting = repository.getInstanceGameSettingsOrCreate(instanceId);
            repository.undoMark(instanceId);
            if (setting != null) {
                setting.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
            }
        };

        ExceptionalConsumer<Exception, ?> failure = ex -> {
            if (ex instanceof ModpackCompletionException && !(ex.getCause() instanceof FileNotFoundException)) {
                success.run();
                // This is tolerable and we will not delete the game
            }
        };

        return new ServerModpackRemoteInstallTask(repository.getDependency(), manifest, instanceId)
                .whenComplete(Schedulers.defaultScheduler(), success, failure)
                .withStagesHints(new Task.StagesHint("xyml.modpack"), new Task.StagesHint("xyml.modpack.download", List.of("xyml.install.assets", "xyml.install.libraries")));
    }

    /// Returns whether an external game directory already uses an instance name.
    ///
    /// @param name candidate instance name
    /// @return whether the name conflicts
    public static boolean isExternalGameNameConflicts(String name) {
        return Files.exists(Paths.get("externalgames").resolve(name));
    }

    /// Creates a task that installs a manually assembled archive as a local game directory.
    ///
    /// @param zipFile archive path
    /// @param name destination instance name
    /// @param charset archive entry-name charset
    /// @return configured installation task
    /// @throws IllegalArgumentException if the destination name already exists
    public static Task<?> getInstallManuallyCreatedModpackTask(Path zipFile, String name, Charset charset) {
        if (isExternalGameNameConflicts(name)) {
            throw new IllegalArgumentException("name existing");
        }

        return new ManuallyCreatedModpackInstallTask(zipFile, charset, name)
                .thenAcceptAsync(Schedulers.ui(), location -> {
                    GameDirectory newGameDirectory = new GameDirectory(
                            GameDirectoryManager.newGameDirectoryId(),
                            LocalizedText.plain(name),
                            PortablePath.fromPath(location));
                    GameDirectoryManager.addLocalGameDirectory(newGameDirectory);
                    GameDirectoryManager.setSelectedGameDirectory(newGameDirectory);
                });
    }

    /// Creates a task that installs an archive modpack and applies provider-specific settings.
    ///
    /// @param repository destination game repository
    /// @param zipFile archive path
    /// @param instanceId destination instance identifier
    /// @param modpack parsed modpack
    /// @param iconUrl instance icon URL
    /// @return configured installation task
    public static Task<?> getInstallTask(
            XYMLGameRepository repository,
            Path zipFile,
            GameInstanceID instanceId,
            Modpack modpack,
            String iconUrl) {
        repository.markInstanceAsModpack(instanceId);

        ExceptionalRunnable<?> success = () -> {
            repository.refresh();
            @Nullable GameSettings.Instance setting = repository.getInstanceGameSettingsOrCreate(instanceId);
            repository.undoMark(instanceId);
            if (setting != null) {
                setting.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
            }
        };

        ExceptionalConsumer<Exception, ?> failure = ex -> {
            if (ex instanceof ModpackCompletionException && !(ex.getCause() instanceof FileNotFoundException)) {
                success.run();
                // This is tolerable and we will not delete the game
            }
        };

        if (modpack.getManifest() instanceof MultiMCInstanceConfiguration)
            return modpack.getInstallTask(repository.getDependency(), zipFile, instanceId, iconUrl)
                    .whenComplete(Schedulers.defaultScheduler(), success, failure)
                    .thenComposeAsync(createMultiMCPostInstallTask(repository, (MultiMCInstanceConfiguration) modpack.getManifest(), instanceId))
                    .withStagesHints(new Task.StagesHint("xyml.modpack"), new Task.StagesHint("xyml.modpack.download", List.of("xyml.install.assets", "xyml.install.libraries")));
        else if (modpack.getManifest() instanceof McbbsModpackManifest)
            return modpack.getInstallTask(repository.getDependency(), zipFile, instanceId, iconUrl)
                    .whenComplete(Schedulers.defaultScheduler(), success, failure)
                    .thenComposeAsync(createMcbbsPostInstallTask(repository, (McbbsModpackManifest) modpack.getManifest(), instanceId))
                    .withStagesHints(new Task.StagesHint("xyml.modpack"), new Task.StagesHint("xyml.modpack.download", List.of("xyml.install.assets", "xyml.install.libraries")));
        else
            return modpack.getInstallTask(repository.getDependency(), zipFile, instanceId, iconUrl)
                    .whenComplete(Schedulers.defaultScheduler(), success, failure)
                    .withStagesHints(new Task.StagesHint("xyml.modpack"), new Task.StagesHint("xyml.modpack.download", List.of("xyml.install.assets", "xyml.install.libraries")));
    }

    /// Creates a task that updates an installed remote server modpack.
    ///
    /// @param repository destination game repository
    /// @param manifest current remote server manifest
    /// @param charset archive entry-name charset retained by the shared update workflow
    /// @param instanceId installed instance identifier
    /// @param configuration persisted modpack configuration
    /// @return configured update task
    /// @throws UnsupportedModpackException if the persisted provider type cannot be updated
    public static Task<Void> getUpdateTask(
            XYMLGameRepository repository,
            ServerModpackManifest manifest,
            Charset charset,
            GameInstanceID instanceId,
            ModpackConfiguration<?> configuration) throws UnsupportedModpackException {
        switch (configuration.getType()) {
            case ServerModpackRemoteInstallTask.MODPACK_TYPE:
                return new ModpackUpdateTask(
                        repository,
                        instanceId,
                        new ServerModpackRemoteInstallTask(repository.getDependency(), manifest, instanceId))
                        .thenComposeAsync(repository.refreshAsync())
                        .withStagesHints(new Task.StagesHint("xyml.modpack"), new Task.StagesHint("xyml.modpack.download", List.of("xyml.install.assets", "xyml.install.libraries")));
            default:
                throw new UnsupportedModpackException();
        }
    }

    /// Creates a task that updates an installed modpack from a local archive.
    ///
    /// @param repository destination game repository
    /// @param zipFile update archive path
    /// @param charset archive entry-name charset
    /// @param instanceId installed instance identifier
    /// @param configuration persisted modpack configuration
    /// @return configured update task
    /// @throws UnsupportedModpackException if the provider type is unsupported
    /// @throws ManuallyCreatedModpackException if the update is a manually assembled archive
    /// @throws MismatchedModpackTypeException if the archive provider differs from the installed modpack
    public static Task<?> getUpdateTask(
            XYMLGameRepository repository,
            Path zipFile,
            Charset charset,
            GameInstanceID instanceId,
            ModpackConfiguration<?> configuration)
            throws UnsupportedModpackException, ManuallyCreatedModpackException, MismatchedModpackTypeException {
        Modpack modpack = ModpackHelper.readModpackManifest(zipFile, charset);
        @Nullable ModpackProvider provider = getProviderByType(configuration.getType());
        if (provider == null) {
            throw new UnsupportedModpackException();
        }
        if (modpack.getManifest() instanceof MultiMCInstanceConfiguration)
            return provider.createUpdateTask(repository.getDependency(), instanceId, zipFile, modpack)
                    .thenComposeAsync(() -> createMultiMCPostUpdateTask(repository, (MultiMCInstanceConfiguration) modpack.getManifest(), instanceId))
                    .thenComposeAsync(repository.refreshAsync());
        else
            return provider.createUpdateTask(repository.getDependency(), instanceId, zipFile, modpack)
                    .thenComposeAsync(repository.refreshAsync());
    }

    /// Applies MultiMC launch overrides to instance-specific launcher settings.
    ///
    /// @param c MultiMC instance configuration
    /// @param setting mutable destination settings
    public static void toGameSettings(MultiMCInstanceConfiguration c, GameSettings.Instance setting) {
        setting.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);

        if (c.isOverrideJavaLocation()) {
            setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
            setting.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);
            setting.javaTypeProperty().setValue(JavaVersionType.CUSTOM);
            setting.customJavaPathProperty().setValue(Objects.requireNonNullElse(c.getJavaPath(), ""));
        }

        if (c.isOverrideMemory()) {
            setting.getOverrideProperties().addAll(List.of(
                    GameSettings.PROPERTY_AUTO_MEMORY,
                    GameSettings.PROPERTY_PERM_SIZE,
                    GameSettings.PROPERTY_MAX_MEMORY,
                    GameSettings.PROPERTY_MIN_MEMORY
            ));
            setting.permSizeProperty().setValue(Optional.ofNullable(c.getPermGen()).map(Object::toString).orElse(""));
            if (c.getMaxMemory() != null)
                setting.maxMemoryProperty().setValue(c.getMaxMemory());
            setting.minMemoryProperty().setValue(c.getMinMemory());
        }

        if (c.isOverrideCommands()) {
            setting.getOverrideProperties().addAll(List.of(
                    GameSettings.PROPERTY_COMMAND_WRAPPER,
                    GameSettings.PROPERTY_PRE_LAUNCH_COMMAND
            ));
            setting.commandWrapperProperty().setValue(Objects.requireNonNullElse(c.getWrapperCommand(), ""));
            setting.preLaunchCommandProperty().setValue(Objects.requireNonNullElse(c.getPreLaunchCommand(), ""));
        }

        if (c.isOverrideJavaArgs()) {
            setting.getOverrideProperties().add(GameSettings.PROPERTY_JVM_OPTIONS);
            setting.jvmOptionsProperty().setValue(Objects.requireNonNullElse(c.getJvmArgs(), ""));
        }

        if (c.isOverrideConsole()) {
            setting.getOverrideProperties().add(GameSettings.PROPERTY_SHOW_LOGS);
            setting.showLogsProperty().setValue(c.isShowConsole());
        }

        if (c.isOverrideWindow()) {
            setting.getOverrideProperties().addAll(List.of(
                    GameSettings.PROPERTY_WINDOW_TYPE,
                    GameSettings.PROPERTY_WIDTH,
                    GameSettings.PROPERTY_HEIGHT
            ));
            setting.windowTypeProperty().setValue(c.isFullscreen() ? GameWindowType.FULLSCREEN : GameWindowType.WINDOWED);
            if (c.getWidth() != null)
                setting.widthProperty().setValue(c.getWidth().doubleValue());
            if (c.getHeight() != null)
                setting.heightProperty().setValue(c.getHeight().doubleValue());
        }
    }

    /// Applies MultiMC command and JVM argument overrides after an update.
    ///
    /// @param c MultiMC instance configuration
    /// @param setting mutable destination settings
    private static void applyCommandAndJvmSettings(MultiMCInstanceConfiguration c, GameSettings.Instance setting) {
        if (c.isOverrideCommands()) {
            setting.getOverrideProperties().addAll(List.of(
                    GameSettings.PROPERTY_COMMAND_WRAPPER,
                    GameSettings.PROPERTY_PRE_LAUNCH_COMMAND
            ));
            setting.commandWrapperProperty().setValue(Lang.nonNull(c.getWrapperCommand(), ""));
            setting.preLaunchCommandProperty().setValue(Lang.nonNull(c.getPreLaunchCommand(), ""));
        }

        if (c.isOverrideJavaArgs()) {
            setting.getOverrideProperties().add(GameSettings.PROPERTY_JVM_OPTIONS);
            setting.jvmOptionsProperty().setValue(Lang.nonNull(c.getJvmArgs(), ""));
        }
    }

    /// Creates the post-update task that reapplies mutable MultiMC launch settings.
    ///
    /// @param repository destination game repository
    /// @param manifest MultiMC instance configuration
    /// @param instanceId installed instance ID
    /// @return post-update task
    private static Task<Void> createMultiMCPostUpdateTask(
            XYMLGameRepository repository,
            MultiMCInstanceConfiguration manifest,
            GameInstanceID instanceId) {
        return Task.runAsync(Schedulers.ui(), () -> {
            GameSettings.Instance setting = Objects.requireNonNull(repository.getInstanceGameSettingsOrCreate(instanceId));
            ModpackHelper.applyCommandAndJvmSettings(manifest, setting);
        });
    }

    /// Creates the post-install task that imports all supported MultiMC settings.
    ///
    /// @param repository destination game repository
    /// @param manifest MultiMC instance configuration
    /// @param instanceId installed instance ID
    /// @return post-install task
    private static Task<Void> createMultiMCPostInstallTask(
            XYMLGameRepository repository,
            MultiMCInstanceConfiguration manifest,
            GameInstanceID instanceId) {
        return Task.runAsync(Schedulers.ui(), () -> {
            GameSettings.Instance setting = Objects.requireNonNull(repository.getInstanceGameSettingsOrCreate(instanceId));
            ModpackHelper.toGameSettings(manifest, setting);
        });
    }

    /// Creates the post-install task that enforces an MCBBS manifest's minimum memory.
    ///
    /// @param repository destination game repository
    /// @param manifest MCBBS manifest
    /// @param instanceId installed instance ID
    /// @return post-install task
    private static Task<Void> createMcbbsPostInstallTask(
            XYMLGameRepository repository,
            McbbsModpackManifest manifest,
            GameInstanceID instanceId) {
        return Task.runAsync(Schedulers.ui(), () -> {
            GameSettings.Effective effective = repository.getEffectiveGameSettings(instanceId);
            if (manifest.getLaunchInfo().getMinMemory() > effective.getMaxMemory()) {
                GameSettings.Instance setting = Objects.requireNonNull(repository.getInstanceGameSettingsOrCreate(instanceId));
                setting.getOverrideProperties().addAll(List.of(
                        GameSettings.PROPERTY_AUTO_MEMORY,
                        GameSettings.PROPERTY_MIN_MEMORY,
                        GameSettings.PROPERTY_MAX_MEMORY,
                        GameSettings.PROPERTY_PERM_SIZE
                ));
                setting.autoMemoryProperty().setValue(effective.getInheritable(GameSettings::autoMemoryProperty));
                setting.minMemoryProperty().setValue(effective.getInheritable(GameSettings::minMemoryProperty));
                setting.maxMemoryProperty().setValue(manifest.getLaunchInfo().getMinMemory());
                setting.permSizeProperty().setValue(effective.getInheritable(GameSettings::permSizeProperty));
            }
        });
    }
}
