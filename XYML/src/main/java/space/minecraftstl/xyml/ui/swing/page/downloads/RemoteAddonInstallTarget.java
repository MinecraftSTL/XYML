// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/// Immutable destination for one remote add-on acquisition.
///
/// A selected-instance target snapshots its instance identifier and managed directory. A save-as
/// target additionally snapshots an exact local file chosen by the user. Provider metadata can
/// never move either form of target outside its normalized parent directory.
///
/// @param kind acquisition category
/// @param instanceId stable selected instance identifier, or null for an explicit save-as target
/// @param directory normalized directory receiving the downloaded artifact
/// @param exactDestination exact user-selected destination, or null to use the provider filename
@NotNullByDefault
public record RemoteAddonInstallTarget(
        RemoteAddonCatalogKind kind,
        @Nullable GameInstanceID instanceId,
        Path directory,
        @Nullable Path exactDestination) {
    /// Creates a managed-directory target whose final filename comes from provider metadata.
    ///
    /// @param kind managed-directory category
    /// @param instanceId stable selected instance identifier
    /// @param directory normalized directory where the add-on file will be installed
    public RemoteAddonInstallTarget(
            RemoteAddonCatalogKind kind,
            GameInstanceID instanceId,
            Path directory) {
        this(kind, instanceId, directory, null);
    }

    /// Validates the target snapshot and normalizes its paths once.
    public RemoteAddonInstallTarget {
        kind = Objects.requireNonNull(kind, "kind");
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (exactDestination == null) {
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
        } else {
            if (kind != RemoteAddonCatalogKind.WORLD) {
                throw new IllegalArgumentException("Only world downloads support an exact save-as destination");
            }
            if (instanceId != null) {
                throw new IllegalArgumentException("A save-as target must not claim an installed instance");
            }
            exactDestination = exactDestination.toAbsolutePath().normalize();
            @Nullable Path parent = exactDestination.getParent();
            if (parent == null || !directory.equals(parent)) {
                throw new IllegalArgumentException("Exact destination must be an immediate child of its directory");
            }
            @Nullable Path fileName = exactDestination.getFileName();
            if (fileName == null || fileName.toString().isBlank()) {
                throw new IllegalArgumentException("Exact destination must name a file");
            }
        }
    }

    /// Creates an exact world-archive save-as destination selected by the user.
    ///
    /// @param destination exact local world archive path
    /// @return normalized immutable world target
    public static RemoteAddonInstallTarget worldSaveAs(Path destination) {
        Path normalized = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        @Nullable Path parent = normalized.getParent();
        @Nullable Path fileName = normalized.getFileName();
        if (parent == null || fileName == null || fileName.toString().isBlank()) {
            throw new IllegalArgumentException("World save destination must name a file");
        }
        return new RemoteAddonInstallTarget(
                RemoteAddonCatalogKind.WORLD,
                null,
                parent,
                normalized);
    }

    /// Resolves one provider artifact to an immediate child of this target directory.
    ///
    /// @param version remote version containing the provider-returned artifact file name
    /// @return normalized direct child path reserved for the artifact
    /// @throws IllegalArgumentException when provider metadata is blank, absolute, or path-bearing
    public Path resolveDestination(RemoteAddon.Version version) {
        RemoteAddon.Version selected = Objects.requireNonNull(version, "version");
        @Nullable Path selectedDestination = exactDestination;
        if (selectedDestination != null) {
            return selectedDestination;
        }
        String fileName = requireNonBlank(selected.file().filename(), "version.file.filename");
        final Path relativeName;
        try {
            relativeName = Path.of(fileName);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Remote artifact file name is invalid", exception);
        }
        if (relativeName.isAbsolute() || relativeName.getNameCount() != 1) {
            throw new IllegalArgumentException("Remote artifact file name must not contain a path");
        }
        Path destination = directory.resolve(relativeName).normalize();
        if (!directory.equals(destination.getParent())) {
            throw new IllegalArgumentException("Remote artifact destination escaped the target directory");
        }
        return destination;
    }

    /// Validates one required non-blank value without rewriting a user-visible identifier.
    ///
    /// @param value required value
    /// @param name diagnostic component name
    /// @return exact validated value
    private static String requireNonBlank(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
