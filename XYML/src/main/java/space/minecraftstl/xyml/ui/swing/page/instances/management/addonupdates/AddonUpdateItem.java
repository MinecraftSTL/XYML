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
package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.addon.RemoteAddon;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/// One locally installed add-on for which a newer compatible remote artifact was found.
///
/// @param fileName stable local display name
/// @param localFile exact locally installed file or directory
/// @param currentVersion identified currently installed remote version
/// @param targetVersion identified newer compatible remote version
/// @param source source that supplied the newer artifact
/// @param sourcePage exact remote project page when the source exposes one, otherwise `null`
/// @param update exact Core update object selected by the scan
@NotNullByDefault
public record AddonUpdateItem(
        String fileName,
        Path localFile,
        String currentVersion,
        String targetVersion,
        RemoteAddon.Source source,
        @Nullable URI sourcePage,
        LocalAddonFile.AddonUpdate update) {
    /// Validates immutable update presentation data against the retained exact Core update object.
    public AddonUpdateItem {
        fileName = Objects.requireNonNull(fileName, "fileName");
        localFile = Objects.requireNonNull(localFile, "localFile").toAbsolutePath().normalize();
        currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        targetVersion = Objects.requireNonNull(targetVersion, "targetVersion");
        source = Objects.requireNonNull(source, "source");
        update = Objects.requireNonNull(update, "update");

        LocalAddonFile localAddonFile = Objects.requireNonNull(
                update.localAddonFile(),
                "update.localAddonFile");
        Path updatePath = Objects.requireNonNull(
                localAddonFile.getFile(),
                "update.localAddonFile.file").toAbsolutePath().normalize();
        if (!fileName.equals(localAddonFile.getFileName())
                || !localFile.equals(updatePath)
                || !currentVersion.equals(update.currentVersion().version())
                || !targetVersion.equals(update.targetVersion().version())
                || source != update.targetVersion().self().getSource()) {
            throw new IllegalArgumentException("Update presentation data does not match the retained Core update");
        }
    }

    /// Creates one stable presentation snapshot while retaining the exact Core update object.
    ///
    /// @param update exact update returned by local add-on discovery
    /// @param sourcePage exact remote project page, or `null` when unavailable
    /// @return immutable update item suitable for selection and later application
    public static AddonUpdateItem from(
            LocalAddonFile.AddonUpdate update,
            @Nullable URI sourcePage) {
        LocalAddonFile.AddonUpdate exactUpdate = Objects.requireNonNull(update, "update");
        LocalAddonFile localAddonFile = Objects.requireNonNull(
                exactUpdate.localAddonFile(),
                "update.localAddonFile");
        return new AddonUpdateItem(
                localAddonFile.getFileName(),
                localAddonFile.getFile(),
                exactUpdate.currentVersion().version(),
                exactUpdate.targetVersion().version(),
                exactUpdate.targetVersion().self().getSource(),
                sourcePage,
                exactUpdate);
    }
}
