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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Describes the authoritative local scope of one instance-maintenance page.
///
/// @param instanceId stable managed instance identifier
/// @param modpack whether the instance has persisted modpack metadata and accepts archive updates
/// @param assetsPresent whether the shared game assets directory currently exists
/// @param librariesPresent whether the shared game libraries directory currently exists
/// @param generatedFilesPresent whether logs or crash reports exist in the shared or instance run directory
@NotNullByDefault
public record InstanceMaintenanceSnapshot(
        String instanceId,
        boolean modpack,
        boolean assetsPresent,
        boolean librariesPresent,
        boolean generatedFilesPresent) {
    /// Validates the stable instance identity retained by this snapshot.
    public InstanceMaintenanceSnapshot {
        Objects.requireNonNull(instanceId, "instanceId");
        if (instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
    }
}
