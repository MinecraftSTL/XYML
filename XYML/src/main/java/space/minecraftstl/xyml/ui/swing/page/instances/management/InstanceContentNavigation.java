// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.ui.swing.page.downloads.DownloadPageTarget;

import java.util.Objects;

/// Supplies cross-page content navigation from an instance-management action.
@FunctionalInterface
@NotNullByDefault
public interface InstanceContentNavigation {
    /// Opens the requested category in the download center.
    ///
    /// @param target requested download category
    void openDownloads(DownloadPageTarget target);

    /// Opens one download category and prefills it from an explicit instance context.
    ///
    /// Implementations that model no instance context retain the plain category transition.
    ///
    /// @param target requested download category
    /// @param instanceId instance whose analyzed context should prefill the category
    default void openInstanceDownloads(DownloadPageTarget target, GameInstanceID instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        openDownloads(Objects.requireNonNull(target, "target"));
    }

    /// Opens the Mods category with a project query.
    ///
    /// Implementations without search support retain the plain Mods category transition.
    ///
    /// @param searchText non-blank project identifier
    /// @throws IllegalArgumentException if the query is blank
    default void openModSearch(String searchText) {
        if (Objects.requireNonNull(searchText, "searchText").trim().isEmpty()) {
            throw new IllegalArgumentException("searchText must not be blank");
        }
        openDownloads(DownloadPageTarget.MODS);
    }

    /// Returns a disabled navigation boundary for headless construction.
    ///
    /// @return navigation accepting requests without performing a shell transition
    static InstanceContentNavigation disabled() {
        return target -> { };
    }
}
