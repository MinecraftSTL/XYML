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

/// Describes one remote add-on category that can be acquired through the native catalog.
///
/// Mods, resource packs, and shader packs install into a selected instance. World archives use the
/// same verified download pipeline but resolve an explicit user-selected save-as destination.
@NotNullByDefault
public enum RemoteAddonCatalogKind {
    /// Remote JAR modifications installed into the selected instance's `mods` directory.
    MOD(RemoteAddon.Type.MOD, "mods"),

    /// Remote resource-pack archives installed into the selected instance's `resourcepacks` directory.
    RESOURCE_PACK(RemoteAddon.Type.RESOURCE_PACK, "resourcepack"),

    /// Remote shader-pack archives installed into the selected instance's `shaderpacks` directory.
    SHADER_PACK(RemoteAddon.Type.SHADER_PACK, "download.shader"),

    /// Remote world archives saved to an explicit local file without automatic extraction.
    WORLD(RemoteAddon.Type.WORLD, "world");

    /// Core repository type used to select a provider-specific catalog.
    private final RemoteAddon.Type repositoryType;

    /// Existing launcher localization key used for the visible category title.
    private final String titleKey;

    /// Creates one remote category descriptor.
    ///
    /// @param repositoryType Core catalog category
    /// @param titleKey existing launcher localization key
    RemoteAddonCatalogKind(RemoteAddon.Type repositoryType, String titleKey) {
        this.repositoryType = repositoryType;
        this.titleKey = titleKey;
    }

    /// Returns the Core repository category used for source lookup.
    ///
    /// @return non-null Core repository category
    public RemoteAddon.Type repositoryType() {
        return repositoryType;
    }

    /// Returns the existing launcher localization key for this category.
    ///
    /// @return non-blank localization key
    public String titleKey() {
        return titleKey;
    }
}
