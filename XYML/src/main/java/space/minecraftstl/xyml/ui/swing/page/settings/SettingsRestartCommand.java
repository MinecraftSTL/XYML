// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.util.concurrent.CompletionStage;

/// Starts a launcher restart requested from one settings component.
///
/// Implementations must complete only after pending settings are durable, a replacement process has started,
/// and the current owning window has been asked to close. Failures must leave the current window open.
@FunctionalInterface
@NotNullByDefault
public interface SettingsRestartCommand {
    /// Starts one nonblocking restart operation.
    ///
    /// @param owner settings component whose owning window must close after the replacement process starts
    /// @return completion after the restart sequence reaches its terminal state
    CompletionStage<@Nullable Void> restart(Component owner);
}
