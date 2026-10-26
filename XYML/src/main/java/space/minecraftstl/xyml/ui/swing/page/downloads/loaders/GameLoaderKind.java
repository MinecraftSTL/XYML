// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Identifies one historical loader or loader-adjacent catalog backed by a Core [DownloadProvider] list ID.
///
/// The declaration order follows the historical installer's all-version card order. It is data only:
/// version availability and mutual exclusions belong to [GameLoaderCompatibilityMatrix].
@NotNullByDefault
public enum GameLoaderKind {
    /// Minecraft Forge mod loader.
    FORGE("forge", "Forge", GameLoaderCategory.MOD_LOADER),

    /// NeoForge mod loader.
    NEOFORGE("neoforge", "NeoForge", GameLoaderCategory.MOD_LOADER),

    /// LiteLoader legacy mod loader.
    LITELOADER("liteloader", "LiteLoader", GameLoaderCategory.LEGACY_LOADER),

    /// OptiFine client optimization component.
    OPTIFINE("optifine", "OptiFine", GameLoaderCategory.OPTIMIZATION),

    /// Fabric mod loader.
    FABRIC("fabric", "Fabric", GameLoaderCategory.MOD_LOADER),

    /// Fabric API companion component.
    FABRIC_API("fabric-api", "Fabric API", GameLoaderCategory.API),

    /// Quilt mod loader.
    QUILT("quilt", "Quilt", GameLoaderCategory.MOD_LOADER),

    /// Quilt API companion component.
    QUILT_API("quilt-api", "Quilt API", GameLoaderCategory.API),

    /// Legacy Fabric mod loader.
    LEGACY_FABRIC("legacyfabric", "LegacyFabric", GameLoaderCategory.LEGACY_LOADER),

    /// Legacy Fabric API companion component.
    LEGACY_FABRIC_API("legacyfabric-api", "LegacyFabric API", GameLoaderCategory.LEGACY_API),

    /// Cleanroom's Minecraft 1.12.2 loader.
    CLEANROOM("cleanroom", "Cleanroom", GameLoaderCategory.CLEANROOM);

    /// Core download-provider version-list identifier.
    private final String versionListId;

    /// Stable fallback presentation name for non-localized callers.
    private final String displayName;

    /// Semantic loader category preserved from the historical installer.
    private final GameLoaderCategory category;

    /// Creates one catalog kind.
    ///
    /// @param versionListId Core download-provider version-list identifier
    /// @param displayName stable fallback presentation name
    /// @param category semantic kind category
    GameLoaderKind(String versionListId, String displayName, GameLoaderCategory category) {
        this.versionListId = Objects.requireNonNull(versionListId, "versionListId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.category = Objects.requireNonNull(category, "category");
    }

    /// Returns the exact Core [DownloadProvider#getVersionListById(String)] identifier.
    ///
    /// @return non-blank Core list identifier
    public String versionListId() {
        return versionListId;
    }

    /// Returns a stable non-localized fallback name.
    ///
    /// @return user-recognizable loader name
    public String displayName() {
        return displayName;
    }

    /// Returns the preserved historical category.
    ///
    /// @return loader category
    public GameLoaderCategory category() {
        return category;
    }
}
