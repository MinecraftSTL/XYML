// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.function.Consumer;

/// Defines toolkit-neutral launcher-window actions applied at game-process lifecycle boundaries.
///
/// Each command must be safe to invoke from a process-completion thread. The production runtime
/// serializes native window work onto the Swing EDT and prevents show or hide callbacks from
/// reopening a runtime whose close transition has already started.
///
/// @param close closes the complete launcher runtime
/// @param hide hides the launcher window without disposing it
/// @param show shows the existing launcher window when the runtime remains open
/// @param openModSearch opens the Mods search page for one missing dependency
/// @param openMissingDependencySearch opens the Mods search page with the analyzed game-version filter
@NotNullByDefault
public record LaunchVisibilityActions(
        Runnable close,
        Runnable hide,
        Runnable show,
        Consumer<String> openModSearch,
        MissingDependencySearchAction openMissingDependencySearch) {
    /// Validates the complete action boundary.
    public LaunchVisibilityActions {
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(hide, "hide");
        Objects.requireNonNull(show, "show");
        Objects.requireNonNull(openModSearch, "openModSearch");
        Objects.requireNonNull(openMissingDependencySearch, "openMissingDependencySearch");
    }

    /// Creates a version-aware action set while retaining the legacy ID-only action.
    ///
    /// @param close closes the complete launcher runtime
    /// @param hide hides the launcher window without disposing it
    /// @param show shows the existing launcher window when the runtime remains open
    /// @param openModSearch opens a search without an analyzed-version constraint
    public LaunchVisibilityActions(
            Runnable close,
            Runnable hide,
            Runnable show,
            Consumer<String> openModSearch) {
        this(
                close,
                hide,
                show,
                openModSearch,
                (dependencyId, ignoredGameVersion) -> openModSearch.accept(dependencyId));
    }

    /// Creates a compatibility action set without an application search destination.
    ///
    /// @param close closes the complete launcher runtime
    /// @param hide hides the launcher window without disposing it
    /// @param show shows the existing launcher window when the runtime remains open
    public LaunchVisibilityActions(Runnable close, Runnable hide, Runnable show) {
        this(close, hide, show, ignored -> {
            throw new UnsupportedOperationException("Missing-mod search is unavailable");
        });
    }
}
