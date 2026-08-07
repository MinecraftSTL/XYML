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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.addon.meta.PackMcMeta;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.image.EncodedImage;
import space.minecraftstl.xyml.util.io.CompressingUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// ZIP resource pack with eagerly parsed metadata and a lazily loaded encoded icon.
@NotNullByDefault
final class ResourcePackZipFile extends ResourcePackFile {
    /// Parsed `pack.mcmeta`, or null after a parse failure.
    private final @Nullable PackMcMeta meta;

    /// Loads one ZIP resource pack without constructing a UI-toolkit image.
    ///
    /// @param manager owning resource-pack manager
    /// @param path resource-pack ZIP path
    ResourcePackZipFile(ResourcePackManager manager, Path path) throws IOException {
        super(manager, path);

        @Nullable PackMcMeta parsedMeta = null;
        try (var zipFileTree = CompressingUtils.openZipTree(path)) {
            try {
                parsedMeta = PackMcMeta.fromNonNullJson(zipFileTree.readTextEntry("/pack.mcmeta"));
            } catch (Exception failure) {
                LOG.warning("Failed to parse resource pack meta", failure);
            }
        }
        meta = parsedMeta;
    }

    /// Returns parsed ZIP metadata.
    ///
    /// @return parsed metadata, or null after failure
    @Override
    public @Nullable PackMcMeta getMeta() {
        return meta;
    }

    /// Loads bounded decompressed ZIP icon data on demand.
    ///
    /// @return encoded icon, or null when `pack.png` is absent
    @Override
    public @Nullable EncodedImage loadIconData() throws IOException {
        try (var zipFileTree = CompressingUtils.openZipTree(file)) {
            @Nullable var iconEntry = zipFileTree.getEntry("/pack.png");
            if (iconEntry == null) {
                return null;
            }
            long declaredSize = iconEntry.getSize();
            if (declaredSize > MAX_ICON_BYTES) {
                throw new IOException("Resource pack icon exceeds " + MAX_ICON_BYTES + " bytes");
            }
            try (var input = zipFileTree.getInputStream(iconEntry)) {
                return EncodedImage.read(input, MAX_ICON_BYTES);
            }
        }
    }

    /// Deletes this resource-pack ZIP when it still exists.
    @Override
    public void delete() throws IOException {
        Files.deleteIfExists(file);
    }

    /// Finds the newest matching remote artifact newer than this local ZIP.
    ///
    /// @param downloadProvider selected download provider
    /// @param gameVersion owning game version
    /// @param source remote source descriptor
    /// @return update descriptor, or null when no newer matching artifact exists
    @Override
    public @Nullable AddonUpdate checkUpdates(
            DownloadProvider downloadProvider,
            String gameVersion,
            RemoteAddon.Source source) throws IOException {
        @Nullable RemoteAddonRepository repository = source.getRepoForType(
                RemoteAddon.Type.RESOURCE_PACK);
        if (repository == null) {
            return null;
        }
        Optional<RemoteAddon.Version> currentVersion = repository.getRemoteVersionByLocalFile(file);
        if (currentVersion.isEmpty()) {
            return null;
        }
        RemoteAddon.Version current = currentVersion.orElseThrow();
        @Unmodifiable List<RemoteAddon.Version> remoteVersions = repository
                .getRemoteVersionsById(downloadProvider, current.projectId())
                .filter(version -> version.gameVersions().contains(gameVersion))
                .filter(version -> version.datePublished().compareTo(current.datePublished()) > 0)
                .sorted(Comparator.comparing(RemoteAddon.Version::datePublished).reversed())
                .toList();
        return remoteVersions.isEmpty()
                ? null
                : new AddonUpdate(this, current, remoteVersions.get(0), false);
    }
}
