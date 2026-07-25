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

/// Immutable user-confirmed remote-modpack installation request.
///
/// Retaining the selected Core item and version means the installation path has everything needed
/// to download a real archive and launch the existing ModpackHelper workflow without browser state.
///
/// @param item loaded source project selected by the user
/// @param version installable project version selected by the user
/// @param instanceName exact destination instance identifier
@NotNullByDefault
public record RemoteModpackInstallRequest(
        RemoteModpackCatalogItem item,
        RemoteAddon.Version version,
        String instanceName) {
    /// Validates the selected Core values and normalizes the user-entered destination name.
    public RemoteModpackInstallRequest {
        item = Objects.requireNonNull(item, "item");
        version = Objects.requireNonNull(version, "version");
        instanceName = Objects.requireNonNull(instanceName, "instanceName").trim();
        if (instanceName.isEmpty()) {
            throw new IllegalArgumentException("instanceName must not be blank");
        }
    }
}
