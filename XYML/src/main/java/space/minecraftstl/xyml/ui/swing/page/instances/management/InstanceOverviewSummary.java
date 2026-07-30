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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.ImageIcon;
import java.nio.file.Path;
import java.util.Objects;

/// Immutable overview projection shared by the persistent instance summary and the overview page.
///
/// @param instanceId stable repository instance identifier
/// @param versionDetail resolved Minecraft version text, or an empty string when unavailable
/// @param instanceRoot resolved version-root directory
/// @param gameDirectory resolved effective game running directory
/// @param iconPreview decoded fixed-size instance icon
@NotNullByDefault
record InstanceOverviewSummary(
        String instanceId,
        String versionDetail,
        Path instanceRoot,
        Path gameDirectory,
        ImageIcon iconPreview) {
    /// Validates one complete shared overview projection.
    InstanceOverviewSummary {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(versionDetail, "versionDetail");
        Objects.requireNonNull(instanceRoot, "instanceRoot");
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(iconPreview, "iconPreview");
        if (instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
    }
}
