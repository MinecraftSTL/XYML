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
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.meta.PackMcMeta;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.image.EncodedImage;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Resource-pack directory with eagerly parsed metadata and a lazily loaded encoded icon.
@NotNullByDefault
final class ResourcePackFolder extends ResourcePackFile {
    /// Parsed `pack.mcmeta`, or null after a parse failure.
    private final @Nullable PackMcMeta meta;

    /// Loads one resource-pack directory without constructing a UI-toolkit image.
    ///
    /// @param manager owning resource-pack manager
    /// @param path directory containing `pack.mcmeta`
    ResourcePackFolder(ResourcePackManager manager, Path path) {
        super(manager, path);

        @Nullable PackMcMeta parsedMeta = null;
        try {
            parsedMeta = PackMcMeta.fromNonNullJsonFile(path.resolve("pack.mcmeta"));
        } catch (Exception failure) {
            LOG.warning("Failed to parse resource pack meta", failure);
        }
        meta = parsedMeta;

    }

    /// Returns parsed directory metadata.
    ///
    /// @return parsed metadata, or null after failure
    @Override
    public @Nullable PackMcMeta getMeta() {
        return meta;
    }

    /// Loads bounded encoded directory icon data on demand.
    ///
    /// @return encoded icon, or null when `pack.png` is absent
    @Override
    public @Nullable EncodedImage loadIconData() throws IOException {
        Path icon = file.resolve("pack.png");
        if (!Files.isRegularFile(icon)) {
            return null;
        }
        try (var input = Files.newInputStream(icon)) {
            return EncodedImage.read(input, MAX_ICON_BYTES);
        }
    }

    /// Recursively deletes this resource-pack directory.
    @Override
    public void delete() throws IOException {
        FileUtils.deleteDirectory(file);
    }

    /// Directory resource packs have no remotely identifiable update artifact.
    ///
    /// @param downloadProvider selected download provider
    /// @param gameVersion owning game version
    /// @param source remote source descriptor
    /// @return always null
    @Override
    public @Nullable AddonUpdate checkUpdates(
            DownloadProvider downloadProvider,
            String gameVersion,
            RemoteAddon.Source source) {
        return null;
    }
}
