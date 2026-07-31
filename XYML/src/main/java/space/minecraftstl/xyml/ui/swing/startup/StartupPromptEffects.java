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
