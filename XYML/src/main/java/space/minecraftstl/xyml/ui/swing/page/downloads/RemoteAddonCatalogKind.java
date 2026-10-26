// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
