// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;

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
        GameInstanceID instanceId,
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
    }
}
