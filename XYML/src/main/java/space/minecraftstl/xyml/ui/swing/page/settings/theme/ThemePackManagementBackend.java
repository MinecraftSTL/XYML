// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Background boundary for the embedded and locally installed theme-pack inventory.
///
/// Every method must defer repository, file, and image work to the supplied non-EDT executor.
@NotNullByDefault
public interface ThemePackManagementBackend {
    /// Loads every selectable embedded and installed theme.
    ///
    /// @param executor caller-owned non-EDT worker executor
    /// @return immutable theme index
    CompletionStage<@Unmodifiable List<ThemePackItem>> loadAll(Executor executor);

    /// Imports one local archive without replacing an existing package.
    ///
    /// @param archive selected `.xyml-theme` archive
    /// @param executor caller-owned non-EDT worker executor
    /// @return immutable imported themes from the newly installed package
    CompletionStage<@Unmodifiable List<ThemePackItem>> importArchive(Path archive, Executor executor);

    /// Revalidates and deletes the exact installed package represented by an item.
    ///
    /// @param item installed theme item authorizing its containing package
    /// @param executor caller-owned non-EDT worker executor
    /// @return completion stage resolved after deletion
    CompletionStage<@Nullable Void> deleteInstalled(ThemePackItem item, Executor executor);

    /// Revalidates and deletes the exact installed package using the selected mode.
    ///
    /// @param item installed theme item authorizing its containing package
    /// @param mode selected deletion behavior
    /// @param executor caller-owned non-EDT worker executor
    /// @return completion stage resolved after deletion
    default CompletionStage<@Nullable Void> deleteInstalled(
            ThemePackItem item,
            DeletionMode mode,
            Executor executor) {
        return deleteInstalled(item, executor);
    }

    /// Revalidates the exact installation directory before desktop integration opens it.
    ///
    /// @param item installed theme item authorizing its containing package
    /// @param executor caller-owned non-EDT worker executor
    /// @return exact current installation directory
    CompletionStage<Path> locateInstalled(ThemePackItem item, Executor executor);
}
