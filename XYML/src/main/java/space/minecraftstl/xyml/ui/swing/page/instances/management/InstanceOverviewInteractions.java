// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.setting.GameInstanceIconType;
import space.minecraftstl.xyml.ui.swing.dialog.RetryableFailureInteraction;

import java.awt.Component;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Owns native Swing dialogs and desktop integration used by the instance overview.
///
/// Keeping these platform boundaries outside the panel makes repository state transitions independently
/// testable while the production implementation still uses `JFileChooser` and `Desktop` directly.
@NotNullByDefault
public interface InstanceOverviewInteractions extends RetryableFailureInteraction {
    /// Displays the complete built-in and custom instance icon selector.
    ///
    /// @param owner parent component for the native dialogs
    /// @param currentIconType persisted built-in fallback type
    /// @param hasCustomIcon whether a custom image currently overrides the fallback
    /// @param initialDirectory directory shown when custom image selection opens
    /// @return completed icon choice, or `null` when selection is cancelled
    @Nullable InstanceIconChoice chooseInstanceIcon(
            Component owner,
            GameInstanceIconType currentIconType,
            boolean hasCustomIcon,
            Path initialDirectory);

    /// Requests confirmation before a custom icon is deleted.
    ///
    /// This method must be invoked on the Swing event-dispatch thread.
    ///
    /// @param owner parent component for the native dialog
    /// @param instanceId stable instance identifier shown to the user
    /// @return whether the icon should be removed
    boolean confirmDeleteIcon(Component owner, GameInstanceID instanceId);

    /// Opens one local directory with the platform desktop handler.
    ///
    /// The returned stage completes after directory creation and the desktop request finish off the EDT.
    ///
    /// @param directory target directory
    /// @return non-null completion stage for the desktop request
    CompletionStage<@Nullable Void> openDirectory(Path directory);

    /// Shows an operational failure to the user.
    ///
    /// This method must be invoked on the Swing event-dispatch thread.
    ///
    /// @param owner parent component for the dialog
    /// @param title failure title
    /// @param detail human-readable failure detail
    void showFailure(Component owner, String title, String detail);
}
