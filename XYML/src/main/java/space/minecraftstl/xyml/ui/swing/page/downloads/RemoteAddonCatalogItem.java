// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.addon.RemoteAddon;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// One Core remote add-on result paired with the catalog kind and source needed for version loading.
///
/// The value retains `RemoteAddon` rather than only a browser URL, so a selected project can enter
/// the launcher task pipeline without handing discovery back to a system browser.
///
/// @param addon Core remote add-on result
/// @param kind acquisition category represented by the result
/// @param source provider that returned the result
@NotNullByDefault
public record RemoteAddonCatalogItem(
        RemoteAddon addon,
        RemoteAddonCatalogKind kind,
        RemoteAddonCatalogSource source) {
    /// Validates immutable result provenance.
    public RemoteAddonCatalogItem {
        addon = Objects.requireNonNull(addon, "addon");
        kind = Objects.requireNonNull(kind, "kind");
        source = Objects.requireNonNull(source, "source");
    }

    /// Returns compact text suitable for one sparse result row.
    ///
    /// @return title and provider label
    public String displayText() {
        String title = addon.title().isBlank() ? addon.slug() : addon.title();
        return title + " - " + source.displayName();
    }

    /// Formats the description, author, and provider tags already present in this result.
    ///
    /// @return compact metadata line suitable for a narrow result row
    public String rowDetail() {
        List<String> values = new ArrayList<>();
        String description = addon.description().lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("");
        if (!description.isBlank()) {
            values.add(description);
        }
        if (!addon.author().isBlank()) {
            values.add(addon.author());
        }
        if (!addon.categories().isEmpty()) {
            values.add(String.join(", ", addon.categories()));
        }
        if (values.isEmpty()) {
            values.add(addon.slug());
        }
        return String.join(" | ", values);
    }
}
