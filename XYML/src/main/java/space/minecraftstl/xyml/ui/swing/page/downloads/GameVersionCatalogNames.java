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
