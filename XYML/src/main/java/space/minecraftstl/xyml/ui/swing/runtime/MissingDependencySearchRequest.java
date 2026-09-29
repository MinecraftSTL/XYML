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
package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Objects;

/// Captures one programmatic missing-dependency catalog request and its instance context.
///
/// @param dependencyId validated dependency identifier used as the search query
/// @param gameVersion analyzed Minecraft version, or null when unavailable
/// @param modLoader current instance mod loader, or null when unavailable or unsupported
/// @param targetInstanceId crashed instance that must receive a selected dependency, or null when unavailable
@NotNullByDefault
public record MissingDependencySearchRequest(
        String dependencyId,
        @Nullable String gameVersion,
        @Nullable ModLoaderType modLoader,
        @Nullable GameInstanceID targetInstanceId) {
    /// Normalizes the query and retains the optional instance context unchanged.
    public MissingDependencySearchRequest {
        dependencyId = Objects.requireNonNull(dependencyId, "dependencyId").trim();
        if (dependencyId.isEmpty()) {
            throw new IllegalArgumentException("dependencyId must not be blank");
        }
        gameVersion = gameVersion == null ? null : gameVersion.trim();
    }
}
