// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
