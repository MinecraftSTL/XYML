// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;

/// Exposes lifecycle control for one Swing animation.
@NotNullByDefault
public interface AnimationHandle {
    /// Cancels the animation without invoking its completion callback.
    ///
    /// Calling this method after cancellation or completion has no effect.
    void cancel();

    /// Returns whether the animation is currently waiting for timer frames.
    ///
    /// @return `true` while the animation is running
    boolean isRunning();

    /// Returns whether the animation was explicitly cancelled.
    ///
    /// @return `true` after cancellation
    boolean isCancelled();

    /// Returns whether the animation reached its final state.
    ///
    /// @return `true` after normal or policy-driven completion
    boolean isFinished();
}
