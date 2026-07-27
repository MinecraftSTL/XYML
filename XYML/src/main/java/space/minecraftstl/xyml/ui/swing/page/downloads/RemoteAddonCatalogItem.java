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
}
