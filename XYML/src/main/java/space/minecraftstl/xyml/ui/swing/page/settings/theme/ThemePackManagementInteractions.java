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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/// Native chooser, confirmation, and desktop boundary injected into the theme management panel.
@NotNullByDefault
public interface ThemePackManagementInteractions {
    /// Selects one local `.xyml-theme` archive on the EDT.
    ///
    /// @param owner dialog owner
    /// @return selected archive, or `null` after cancellation
    @Nullable Path chooseImportArchive(Component owner);

    /// Confirms deletion of the complete installed package containing an item on the EDT.
    ///
    /// @param owner dialog owner
    /// @param item selected installed item
    /// @return whether deletion was confirmed
    boolean confirmDelete(Component owner, ThemePackItem item);

    /// Opens a previously revalidated installed package directory without blocking the EDT.
    ///
    /// @param directory exact validated directory
    /// @return completion stage resolved after desktop integration returns
    CompletionStage<@Nullable Void> revealInstalledDirectory(Path directory);

    /// Collects current-theme metadata and selects an output archive through Swing dialogs on the EDT.
    ///
    /// Implementations that are used without export support may retain this cancellation default.
    ///
    /// @param owner dialog owner
    /// @param defaults generated export defaults
    /// @return confirmed export request, or `null` after cancellation
    default @Nullable ThemePackExportRequest chooseThemePackExport(
            Component owner,
            ThemePackExportDefaults defaults) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(defaults, "defaults");
        return null;
    }

    /// Reports a successfully published theme-pack archive on the EDT.
    ///
    /// @param owner dialog owner
    /// @param outputFile published archive
    default void showThemePackExportSuccess(Component owner, Path outputFile) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(outputFile, "outputFile");
    }

    /// Reports a failed current-theme export on the EDT.
    ///
    /// @param owner dialog owner
    /// @param failure root export failure
    default void showThemePackExportFailure(Component owner, Throwable failure) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(failure, "failure");
    }
}
