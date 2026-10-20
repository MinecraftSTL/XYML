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
package space.minecraftstl.xyml.ui.swing.dialog;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.util.Objects;

/// Common failure-presentation boundary for operations that can be safely replayed.
@NotNullByDefault
public interface RetryableFailureInteraction {
    /// Shows one non-retryable failure.
    ///
    /// @param owner dialog owner
    /// @param title concise title
    /// @param detail actionable detail
    void showFailure(Component owner, String title, String detail);

    /// Shows a plain failure when no retry action exists, otherwise shows the retry dialog.
    ///
    /// @param owner dialog owner
    /// @param title concise title
    /// @param detail actionable detail
    /// @param retryAction captured retry action, or null for a terminal failure
    default void showFailure(
            Component owner,
            String title,
            String detail,
            @Nullable Runnable retryAction) {
        if (retryAction == null) {
            showFailure(owner, title, detail);
        } else {
            showRetryableFailure(owner, title, detail, retryAction);
        }
    }

    /// Shows one retryable failure and delegates to the plain failure boundary by default.
    ///
    /// @param owner dialog owner
    /// @param title concise title
    /// @param detail actionable detail
    /// @param retryAction captured operation to run only after an explicit retry choice
    default void showRetryableFailure(
            Component owner,
            String title,
            String detail,
            Runnable retryAction) {
        Objects.requireNonNull(retryAction, "retryAction");
        showFailure(owner, title, detail);
    }
}
