// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;

/// Performs process-level startup effects outside the prompt policy and Swing presenter.
///
/// The coordinator invokes every method on its worker executor. Implementations may bridge to launcher
/// state or process services, but must not synchronously wait for the Swing event dispatch thread.
@NotNullByDefault
public interface StartupPromptEffects {
    /// Flushes launcher state into the asynchronous persistence pipeline before a requested restart.
    ///
    /// @throws Exception when the save request cannot be issued
    void saveBeforeRestart() throws Exception;

    /// Waits for already-issued persistence operations before a requested restart.
    ///
    /// @throws Exception when waiting fails or is interrupted
    void waitForPendingSaves() throws Exception;

    /// Starts a replacement launcher process.
    ///
    /// @throws Exception when the replacement process cannot be started
    void restartApplication() throws Exception;

    /// Requests application shutdown.
    ///
    /// @throws Exception when shutdown dispatch fails
    void closeApplication() throws Exception;

    /// Reports a prompt failure without deciding whether the remaining queue continues.
    ///
    /// @param promptKind prompt whose operation failed
    /// @param failure exact failure raised by the presenter, state gateway, or effect
    void reportFailure(StartupPromptKind promptKind, Throwable failure);
}
