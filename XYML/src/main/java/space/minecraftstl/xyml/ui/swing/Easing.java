// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;

/// Provides bounded easing curves for Swing animation progress.
@NotNullByDefault
public enum Easing {
    /// Preserves linear progress.
    LINEAR,

    /// Uses a smooth cubic curve with zero velocity at both ends.
    STANDARD,

    /// Uses a cubic deceleration curve for elements entering or settling into place.
    DECELERATE;

    /// Transforms normalized time into normalized visual progress.
    ///
    /// Values outside the normalized range are clamped before applying the selected curve.
    ///
    /// @param progress normalized elapsed time
    /// @return eased progress between zero and one
    public double apply(double progress) {
        double boundedProgress = Math.max(0.0, Math.min(1.0, progress));
        return switch (this) {
            case LINEAR -> boundedProgress;
            case STANDARD -> boundedProgress * boundedProgress * (3.0 - 2.0 * boundedProgress);
            case DECELERATE -> 1.0 - Math.pow(1.0 - boundedProgress, 3.0);
        };
    }
}
