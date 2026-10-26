// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;

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
        GameInstanceID instanceId,
        boolean modpack,
        boolean assetsPresent,
        boolean librariesPresent,
        boolean generatedFilesPresent) {
    /// Validates the stable instance identity retained by this snapshot.
    public InstanceMaintenanceSnapshot {
        Objects.requireNonNull(instanceId, "instanceId");
    }
}
