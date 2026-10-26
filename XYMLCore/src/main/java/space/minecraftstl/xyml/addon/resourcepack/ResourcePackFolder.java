// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
