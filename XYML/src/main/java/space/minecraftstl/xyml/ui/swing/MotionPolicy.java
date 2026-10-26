// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Controls which categories of Swing animation are allowed to run.
@NotNullByDefault
public enum MotionPolicy {
    /// Runs both essential and decorative animation.
    FULL,

    /// Runs essential animation while applying decorative changes immediately.
    REDUCED,

    /// Applies all animated changes immediately.
    OFF;

    /// Returns whether an animation with the given purpose should advance over time.
    ///
    /// @param purpose the semantic purpose of the animation
    /// @return `true` when the animation should run
    public boolean allows(MotionPurpose purpose) {
        Objects.requireNonNull(purpose);

        return switch (this) {
            case FULL -> true;
            case REDUCED -> purpose == MotionPurpose.ESSENTIAL;
            case OFF -> false;
        };
    }
}
