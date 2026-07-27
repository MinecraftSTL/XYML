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
