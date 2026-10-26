// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;
import space.minecraftstl.xyml.game.GameComponentType;

import java.util.List;
import java.util.Objects;

/// Builds deterministic labels for locally created game instances.
@NotNullByDefault
final class GameVersionCatalogNames {
    /// Builds the historical concise instance name from Minecraft and selected primary loaders.
    ///
    /// @param versionId exact selected Minecraft version
    /// @param loaders immutable selected loader and companion versions
    /// @return suggested instance name
    static String defaultInstanceName(
            String versionId,
            @Unmodifiable List<ComponentRemoteVersion> loaders) {
        StringBuilder name = new StringBuilder(Objects.requireNonNull(versionId, "versionId"));
        for (ComponentRemoteVersion loader : Objects.requireNonNull(loaders, "loaders")) {
            GameComponentType type = loader.getComponentType();
            @Nullable String suffix = switch (type) {
                case FORGE -> "Forge";
                case NEO_FORGE -> "NeoForge";
                case CLEANROOM -> "Cleanroom";
                case LEGACY_FABRIC -> "LegacyFabric";
                case FABRIC -> "Fabric";
                case LITELOADER -> "LiteLoader";
                case QUILT -> "Quilt";
                case OPTIFINE -> "OptiFine";
                default -> null;
            };
            if (suffix != null) {
                name.append('-').append(suffix);
            }
        }
        return name.toString();
    }

    /// Prevents instantiation.
    private GameVersionCatalogNames() {
    }
}
