// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;

import java.util.Objects;

/// Opens a read-only missing-dependency catalog search with the analyzer's captured query context.
@FunctionalInterface
@NotNullByDefault
public interface MissingDependencySearchAction {
    /// Opens one dependency query using the analyzed Minecraft version when it is known.
    ///
    /// @param dependencyId validated dependency identifier
    /// @param gameVersion analyzed Minecraft version, or null when unavailable
    void open(String dependencyId, @Nullable String gameVersion);

    /// Opens one dependency query while retaining the current instance loader when supplied.
    ///
    /// Implementations that do not model loader filtering retain the version-only behavior.
    ///
    /// @param dependencyId validated dependency identifier
    /// @param gameVersion analyzed Minecraft version, or null when unavailable
    /// @param modLoader current instance mod loader, or null when unavailable or unsupported
    default void open(String dependencyId, @Nullable String gameVersion, @Nullable ModLoaderType modLoader) {
        open(dependencyId, gameVersion);
    }

    /// Opens one dependency query with the captured target-instance context when supplied.
    ///
    /// Implementations that do not model target binding retain the loader-aware behavior.
    ///
    /// @param request validated missing-dependency search request
    default void open(MissingDependencySearchRequest request) {
        MissingDependencySearchRequest checked = Objects.requireNonNull(request, "request");
        open(checked.dependencyId(), checked.gameVersion(), checked.modLoader());
    }
}
