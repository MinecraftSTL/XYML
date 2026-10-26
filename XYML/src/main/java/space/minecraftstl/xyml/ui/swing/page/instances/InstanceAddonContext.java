// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Objects;

/// Captures the analyzed catalog filters of one installed instance.
///
/// Add-on catalogs derived from an instance forward this context so their version and loader filters match the
/// instance. Unresolved values remain `null` and callers keep their own defaults instead of reporting a failure.
///
/// @param instanceId stable repository instance identifier
/// @param gameVersion analyzed Minecraft version, or `null` when unavailable
/// @param modLoader primary supported mod loader, or `null` when unavailable or unsupported
@NotNullByDefault
public record InstanceAddonContext(
        GameInstanceID instanceId,
        @Nullable String gameVersion,
        @Nullable ModLoaderType modLoader) {
    /// Normalizes one analyzed instance context.
    public InstanceAddonContext {
        Objects.requireNonNull(instanceId, "instanceId");
    }
}
