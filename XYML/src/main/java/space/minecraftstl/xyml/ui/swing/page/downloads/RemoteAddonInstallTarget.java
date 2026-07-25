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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.addon.RemoteAddon;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/// Immutable selected-instance destination for one direct remote add-on installation.
///
/// The target snapshots the selected instance identifier and destination directory before task creation.
/// It rejects path-bearing provider file names, keeping remote metadata unable to escape the selected
/// instance's managed add-on directory.
///
/// @param kind direct-install category
/// @param instanceId stable selected instance identifier
/// @param directory normalized directory where the add-on file will be installed
@NotNullByDefault
public record RemoteAddonInstallTarget(
        RemoteAddonCatalogKind kind,
        String instanceId,
        Path directory) {
    /// Validates the selected-instance snapshot and normalizes its directory once.
    public RemoteAddonInstallTarget {
        kind = Objects.requireNonNull(kind, "kind");
        instanceId = requireNonBlank(instanceId, "instanceId");
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    /// Resolves one provider artifact to an immediate child of this target directory.
    ///
    /// @param version remote version containing the provider-returned artifact file name
    /// @return normalized direct child path reserved for the artifact
    /// @throws IllegalArgumentException when provider metadata is blank, absolute, or path-bearing
    public Path resolveDestination(RemoteAddon.Version version) {
        RemoteAddon.Version selected = Objects.requireNonNull(version, "version");
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
