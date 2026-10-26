// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.addon.RemoteAddon;

import java.util.Objects;

/// Captures one selected remote project version and its exact acquisition destination.
///
/// @param item selected remote project
/// @param version exact selected project version
/// @param target managed-directory or save-as target captured immediately before task creation
@NotNullByDefault
public record RemoteAddonInstallRequest(
        RemoteAddonCatalogItem item,
        RemoteAddon.Version version,
        RemoteAddonInstallTarget target) {
    /// Validates that the item and target belong to the same acquisition category.
    public RemoteAddonInstallRequest {
        item = Objects.requireNonNull(item, "item");
        version = Objects.requireNonNull(version, "version");
        target = Objects.requireNonNull(target, "target");
        if (item.kind() != target.kind()) {
            throw new IllegalArgumentException("Remote add-on item and target kinds must match");
        }
    }
}
