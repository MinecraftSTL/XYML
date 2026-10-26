// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable;

import org.jetbrains.annotations.NotNullByDefault;

/// Receives value changes synchronously on the thread that publishes them.
@FunctionalInterface
@NotNullByDefault
public interface ValueChangeListener<T> {
    /// Handles one value change.
    void onChange(ValueChange<T> change);
}
