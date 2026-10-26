// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.addon;

import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/// Sub-classes should implement `Comparable`
@NotNullByDefault
public abstract class LocalAddonFile {

    protected LocalAddonFile() {
    }

    public abstract Path getFile();

    /// Without extension
    public abstract String getFileName();

    public boolean isDisabled() {
        return FileUtils.getName(getFile()).endsWith(LocalAddonManager.DISABLED_EXTENSION);
    }

    public abstract void markDisabled() throws IOException;

    public abstract void setOld(boolean old) throws IOException;

    public abstract boolean keepOldFiles();

    public abstract void delete() throws IOException;

    /// Finds one compatible update from a remote source when this local add-on supports remote identification.
    ///
    /// @param downloadProvider provider used for remote metadata requests
    /// @param gameVersion owning Minecraft version
    /// @param source remote service to query
    /// @return update metadata, or `null` when this add-on cannot be updated from the source
    /// @throws IOException when source metadata cannot be loaded
    public @Nullable AddonUpdate checkUpdates(
            DownloadProvider downloadProvider,
            String gameVersion,
            RemoteAddon.Source source)
            throws IOException {
        return null;
    }

    /// Immutable update metadata retaining the source and repository type that produced the candidate.
    ///
    /// @param source remote service that supplied the target version
    /// @param repoType content repository type used for provider follow-up requests
    /// @param localAddonFile exact installed add-on
    /// @param currentVersion identified installed version
    /// @param targetVersion selected newer compatible version
    /// @param useRemoteFileName whether installation should adopt the provider artifact name
    @NotNullByDefault
    public record AddonUpdate(
            RemoteAddon.Source source,
            RemoteAddon.Type repoType,
            LocalAddonFile localAddonFile,
            RemoteAddon.Version currentVersion,
            RemoteAddon.Version targetVersion,
            boolean useRemoteFileName
    ) {
    }

    @NotNullByDefault
    public record Description(@Unmodifiable List<Part> parts) {

        public Description {
            parts = List.copyOf(parts);
        }

        public Description(String text) {
            this(List.of(new Part(text, "black")));
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            for (Part part : parts) {
                builder.append(part.text);
            }
            return builder.toString();
        }

        public String toStringSingleLine() {
            return toString().lines().map(String::trim).filter(StringUtils::isNotBlank).collect(Collectors.joining(" | "));
        }

        public record Part(String text, String color) {

            public Part {
                Objects.requireNonNull(text);
                Objects.requireNonNull(color);
            }

            public Part(String text) {
                this(text, "");
            }
        }
    }
}
