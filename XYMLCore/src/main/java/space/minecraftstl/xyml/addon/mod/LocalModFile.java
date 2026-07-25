/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.addon.mod;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.addon.LocalAddonManager;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Describes one installed mod file and owns its enabled/disabled file-system state.
///
/// Enabling or disabling a current mod updates the requested in-memory state first and then
/// attempts to rename the file. A failed rename is logged without rolling back that requested
/// state, matching the historical launcher behavior. Archived files never change on disk when
/// their requested active state changes.
@NotNullByDefault
public final class LocalModFile extends LocalAddonFile implements Comparable<LocalModFile> {
    /// Current path, including disabled or archived suffixes when applicable.
    private Path file;

    /// Manager used for state-changing file operations.
    private final ModManager modManager;

    /// Logical mod shared by all current and archived versions.
    private final LocalMod mod;

    /// Human-readable mod name.
    private final String name;

    /// Parsed mod description.
    private final Description description;

    /// Parsed author display text.
    private final String authors;

    /// Parsed mod version.
    private final String version;

    /// Parsed target game version.
    private final String gameVersion;

    /// Optional upstream page URL represented as an empty string when absent.
    private final String url;

    /// Stable local add-on name without state suffixes or the archive extension.
    private final String fileName;

    /// Optional path of the embedded logo represented as an empty string when absent.
    private final String logoPath;

    /// Requested active state, which can intentionally differ from disk after an I/O failure.
    private boolean active;

    /// Creates a local mod file with empty optional metadata.
    ///
    /// @param modManager manager that owns the file
    /// @param mod logical mod represented by the file
    /// @param file current file path
    /// @param name human-readable mod name
    /// @param description parsed mod description
    public LocalModFile(ModManager modManager, LocalMod mod, Path file, String name, Description description) {
        this(modManager, mod, file, name, description, "", "", "", "", "");
    }

    /// Creates a local mod file with all parsed metadata.
    ///
    /// @param modManager manager that owns the file
    /// @param mod logical mod represented by the file
    /// @param file current file path
    /// @param name human-readable mod name
    /// @param description parsed mod description
    /// @param authors author display text
    /// @param version mod version
    /// @param gameVersion target game version
    /// @param url upstream page URL, or an empty string
    /// @param logoPath embedded logo path, or an empty string
    public LocalModFile(ModManager modManager, LocalMod mod, Path file, String name, Description description, String authors, String version, String gameVersion, String url, String logoPath) {
        super();
        this.modManager = modManager;
        this.mod = mod;
        this.file = file;
        this.name = name;
        this.description = description;
        this.authors = authors;
        this.version = version;
        this.gameVersion = gameVersion;
        this.url = url;
        this.logoPath = logoPath;
        this.active = !modManager.isDisabled(file);

        fileName = FileUtils.getNameWithoutExtension(LocalAddonManager.getLocalAddonName(file));

        if (isOld()) {
            mod.getOldFiles().add(this);
        } else {
            mod.getFiles().add(this);
        }
    }

    /// Returns the manager that owns this file.
    ///
    /// @return owning mod manager
    public ModManager getModManager() {
        return modManager;
    }

    /// Returns the logical mod shared by its installed versions.
    ///
    /// @return logical mod
    public LocalMod getMod() {
        return mod;
    }

    /// Returns the current path after any successful state transitions.
    ///
    /// @return current file path
    @Override
    public Path getFile() {
        return file;
    }

    /// Returns the loader declared for the logical mod.
    ///
    /// @return mod loader type
    public ModLoaderType getModLoaderType() {
        return mod.getModLoaderType();
    }

    /// Returns the logical mod identifier.
    ///
    /// @return mod identifier
    public String getId() {
        return mod.getId();
    }

    /// Returns the parsed human-readable name.
    ///
    /// @return mod name
    public String getName() {
        return name;
    }

    /// Returns the parsed description.
    ///
    /// @return mod description
    public Description getDescription() {
        return description;
    }

    /// Returns the parsed author display text.
    ///
    /// @return authors, or an empty string
    public String getAuthors() {
        return authors;
    }

    /// Returns the parsed mod version.
    ///
    /// @return mod version, or an empty string
    public String getVersion() {
        return version;
    }

    /// Returns the parsed target game version.
    ///
    /// @return game version, or an empty string
    public String getGameVersion() {
        return gameVersion;
    }

    /// Returns the upstream page URL.
    ///
    /// @return upstream URL, or an empty string
    public String getUrl() {
        return url;
    }

    /// Returns the embedded logo path.
    ///
    /// @return embedded logo path, or an empty string
    public String getLogoPath() {
        return logoPath;
    }

    /// Returns the requested active state.
    ///
    /// The result can differ from the path after a failed enable or disable operation.
    ///
    /// @return whether the mod is requested to be active
    public boolean isActive() {
        return active;
    }

    /// Changes the requested active state and renames a current mod when the value changes.
    ///
    /// Archived files only retain the requested value. File-system failures are logged and leave
    /// both the requested value and last known path unchanged.
    ///
    /// @param active whether the mod should be active
    public void setActive(boolean active) {
        if (this.active == active) {
            return;
        }

        this.active = active;
        if (isOld()) {
            return;
        }

        Path path = file.toAbsolutePath();
        try {
            file = active ? modManager.enableMod(path) : modManager.disableMod(path);
        } catch (IOException e) {
            LOG.error("Unable to invert state of mod file " + path, e);
        }
    }

    /// Returns the stable file name without add-on and state extensions.
    ///
    /// @return stable file name
    @Override
    public String getFileName() {
        return fileName;
    }

    /// Returns whether this file is an archived version.
    ///
    /// @return whether the current path has the archived suffix
    public boolean isOld() {
        return modManager.isOld(file);
    }

    /// Moves the file into or out of the archived set and updates logical-mod membership.
    ///
    /// @param old whether the file should be archived
    /// @throws IOException if the file cannot be moved
    @Override
    public void setOld(boolean old) throws IOException {
        file = modManager.setOld(this, old);

        if (old) {
            mod.getFiles().remove(this);
            mod.getOldFiles().add(this);
        } else {
            mod.getOldFiles().remove(this);
            mod.getFiles().add(this);
        }
    }

    /// Indicates that archived versions should be retained during add-on maintenance.
    ///
    /// @return always `true`
    @Override
    public boolean keepOldFiles() {
        return true;
    }

    /// Renames the current file with the disabled suffix without changing requested state.
    ///
    /// @throws IOException if the file cannot be renamed
    @Override
    public void markDisabled() throws IOException {
        file = modManager.disableMod(file);
    }

    /// Deletes the current file if it exists.
    ///
    /// @throws IOException if deletion fails
    @Override
    public void delete() throws IOException {
        Files.deleteIfExists(file);
    }

    /// Finds the newest compatible remote version newer than the installed file.
    ///
    /// @param downloadProvider provider used for remote metadata requests
    /// @param gameVersion target game version
    /// @param source configured remote add-on source
    /// @return update description, or `null` when no compatible update is available
    /// @throws IOException if remote metadata cannot be loaded
    @Override
    public @Nullable AddonUpdate checkUpdates(DownloadProvider downloadProvider, String gameVersion, RemoteAddon.Source source) throws IOException {
        @Nullable RemoteAddonRepository repository = source.getRepoForType(RemoteAddonRepository.Type.MOD);
        if (repository == null) return null;
        Optional<RemoteAddon.Version> currentVersion = repository.getRemoteVersionByLocalFile(file);
        if (currentVersion.isEmpty()) return null;
        @Unmodifiable List<RemoteAddon.Version> remoteVersions = repository.getRemoteVersionsById(downloadProvider, currentVersion.get().modid())
                .filter(version -> version.gameVersions().contains(gameVersion))
                .filter(version -> version.loaders().contains(getModLoaderType()))
                .filter(version -> version.datePublished().compareTo(currentVersion.get().datePublished()) > 0)
                .sorted(Comparator.comparing(RemoteAddon.Version::datePublished).reversed())
                .toList();
        if (remoteVersions.isEmpty()) return null;
        return new AddonUpdate(this, currentVersion.get(), remoteVersions.get(0), true);
    }

    /// Orders files by their stable names without regard to case.
    ///
    /// @param other file to compare with
    /// @return comparison result
    @Override
    public int compareTo(LocalModFile other) {
        return getFileName().compareToIgnoreCase(other.getFileName());
    }

    /// Compares local mod files by stable file name.
    ///
    /// @param obj candidate value
    /// @return whether the candidate has the same stable file name
    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof LocalModFile && Objects.equals(getFileName(), ((LocalModFile) obj).getFileName());
    }

    /// Returns the hash of the stable file name.
    ///
    /// @return stable-name hash
    @Override
    public int hashCode() {
        return Objects.hash(getFileName());
    }
}
