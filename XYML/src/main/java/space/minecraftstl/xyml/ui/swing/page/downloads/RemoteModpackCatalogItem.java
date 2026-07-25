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
