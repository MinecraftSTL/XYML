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
package space.minecraftstl.xyml.addon.resourcepack;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.addon.meta.PackMcMeta;
import space.minecraftstl.xyml.image.EncodedImage;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// One local resource-pack archive or directory owned by a [ResourcePackManager].
///
/// Metadata is parsed without initializing a UI toolkit. Icon bytes are loaded only when requested,
/// allowing a viewport-driven UI to avoid reading every off-screen icon.
@NotNullByDefault
public sealed abstract class ResourcePackFile extends LocalAddonFile
        implements Comparable<ResourcePackFile>
        permits ResourcePackFolder, ResourcePackZipFile {
    /// Maximum encoded icon size accepted from a directory or decompressed ZIP entry.
    static final int MAX_ICON_BYTES = 4 * 1024 * 1024;

    /// Owning manager used for compatibility and enabled-state operations.
    protected final ResourcePackManager manager;

    /// Current archive or directory path, updated when old-file state changes.
    protected Path file;

    /// Display name without the final extension and with Minecraft color escapes parsed.
    protected final String fileName;

    /// Stable original file name including its extension.
    protected final String fileNameWithExtension;

    /// Lazily resolved compatibility for this immutable metadata and manager version.
    private @Nullable Compatibility compatibility;

    /// Creates a concrete pack representation for a supported direct child path.
    ///
    /// @param manager owning resource-pack manager
    /// @param path candidate archive or directory
    /// @return matching pack, or null when the path is not a supported resource pack
    public static @Nullable ResourcePackFile fromFile(
            ResourcePackManager manager,
            Path path) throws IOException {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(path, "path");
        if (!isFileResourcePack(path)) {
            return null;
        }
        return Files.isRegularFile(path)
                ? new ResourcePackZipFile(manager, path)
                : new ResourcePackFolder(manager, path);
    }

    /// Tests whether a path is a ZIP candidate or a directory containing `pack.mcmeta`.
    ///
    /// @param path candidate path
    /// @return whether the path has a supported resource-pack shape
    public static boolean isFileResourcePack(Path path) {
        Objects.requireNonNull(path, "path");
        if (Files.isDirectory(path)) {
            return Files.isRegularFile(path.resolve("pack.mcmeta"));
        }
        return Files.isRegularFile(path)
                && path.toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /// Initializes stable names and ownership for one concrete pack.
    ///
    /// @param manager owning manager
    /// @param file archive or directory path
    protected ResourcePackFile(ResourcePackManager manager, Path file) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.file = Objects.requireNonNull(file, "file");
        fileName = StringUtils.parseColorEscapes(FileUtils.getNameWithoutExtension(file));
        fileNameWithExtension = Objects.requireNonNull(file.getFileName(), "resource pack file name")
                .toString();
    }

    /// Returns the current archive or directory path.
    ///
    /// @return current pack path
    @Override
    public Path getFile() {
        return file;
    }

    /// Returns the display name without the final extension.
    ///
    /// @return parsed display name
    @Override
    public String getFileName() {
        return fileName;
    }

    /// Returns the original file name including its extension.
    ///
    /// @return file name with extension
    public String getFileNameWithExtension() {
        return fileNameWithExtension;
    }

    /// Resolves and caches compatibility against the owning instance version.
    ///
    /// @return current compatibility classification
    public Compatibility getCompatibility() {
        if (compatibility == null) {
            compatibility = manager.getCompatibility(this);
        }
        return compatibility;
    }

    /// Returns whether metadata is compatible with the owning instance.
    ///
    /// @return whether the pack is compatible
    public boolean isCompatible() {
        return getCompatibility() == Compatibility.COMPATIBLE;
    }

    /// Reads whether this pack is currently enabled in instance options.
    ///
    /// @return current enabled state
    public boolean isEnabled() {
        return manager.isEnabled(this);
    }

    /// Updates this pack's enabled state through the owning manager.
    ///
    /// @param enabled desired enabled state
    public void setEnabled(boolean enabled) {
        if (enabled) {
            manager.enableResourcePacks(List.of(this));
        } else {
            manager.disableResourcePacks(List.of(this));
        }
    }

    /// Moves this pack into or out of the manager's old-file convention.
    ///
    /// @param old whether the old-file suffix should be applied
    @Override
    public void setOld(boolean old) throws IOException {
        file = manager.setOld(this, old);
    }

    /// Resource packs do not retain duplicate old-file copies.
    ///
    /// @return always false
    @Override
    public final boolean keepOldFiles() {
        return false;
    }

    /// Resource packs do not use the generic disabled-file suffix.
    @Override
    public final void markDisabled() {
    }

    /// Returns parsed pack metadata when available.
    ///
    /// @return parsed metadata, or null after missing or invalid metadata
    public abstract @Nullable PackMcMeta getMeta();

    /// Returns the pack description from parsed metadata.
    ///
    /// @return parsed description, or null when unavailable
    public @Nullable LocalAddonFile.Description getDescription() {
        @Nullable PackMcMeta meta = getMeta();
        return meta == null || meta.pack() == null ? null : meta.pack().description();
    }

    /// Loads immutable encoded icon data without constructing a UI-toolkit image.
    ///
    /// Callers must perform this potentially blocking operation away from a UI thread. The method
    /// reads no more than [#MAX_ICON_BYTES] and returns null only when `pack.png` is absent.
    ///
    /// @return encoded icon, or null when `pack.png` is absent
    /// @throws IOException when the icon cannot be read or exceeds the safety bound
    public abstract @Nullable EncodedImage loadIconData() throws IOException;

    /// Orders packs by their stable file names including extensions.
    ///
    /// @param other other pack
    /// @return lexical file-name comparison
    @Override
    public int compareTo(ResourcePackFile other) {
        return fileNameWithExtension.compareTo(
                Objects.requireNonNull(other, "other").fileNameWithExtension);
    }

    /// Compatibility between one pack's declared format and the owning game version.
    public enum Compatibility {
        /// Pack metadata accepts the owning game version.
        COMPATIBLE,

        /// Pack metadata requires a newer game version.
        TOO_NEW,

        /// Pack metadata targets an older game version.
        TOO_OLD,

        /// Pack format declarations are internally invalid.
        INVALID,

        /// Pack metadata or its pack section is missing.
        MISSING_PACK_META,

        /// The owning instance does not expose a usable required pack format.
        MISSING_GAME_META
    }
}
