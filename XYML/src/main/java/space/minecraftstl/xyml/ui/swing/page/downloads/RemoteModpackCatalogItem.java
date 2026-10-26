// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.addon.RemoteAddon;

import java.util.Objects;

/// Immutable remote-modpack result paired with the exact source needed for later version loading.
///
/// The result intentionally retains the Core `RemoteAddon` rather than a browser URL, allowing the
/// selected item to resolve installable versions and enter the launcher task pipeline directly.
///
/// @param addon Core remote add-on result
/// @param source source repository that returned the add-on
@NotNullByDefault
public record RemoteModpackCatalogItem(RemoteAddon addon, RemoteModpackCatalogSource source) {
    /// Validates the retained Core result and source provenance.
    public RemoteModpackCatalogItem {
        addon = Objects.requireNonNull(addon, "addon");
        source = Objects.requireNonNull(source, "source");
    }

    /// Returns a compact stable text used by the sparse catalog renderer.
    ///
    /// @return title and source suitable for a single list row
    public String displayText() {
        String title = addon.title().isBlank() ? addon.slug() : addon.title();
        return title + " - " + source.displayName();
    }

    /// Returns the best initial instance-id proposal for this remote project.
    ///
    /// @return non-blank project slug when present, otherwise title
    public String suggestedInstanceName() {
        return addon.slug().isBlank() ? addon.title() : addon.slug();
    }
}
