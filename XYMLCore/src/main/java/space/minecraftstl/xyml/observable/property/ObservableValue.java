// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;

/// Exposes a strongly typed value and synchronous change subscriptions without depending on a UI toolkit.
@NotNullByDefault
public interface ObservableValue<T> {
    /// Returns the current value, or null when this observable represents an empty object value.
    @Nullable T getValue();

    /// Registers a listener that runs synchronously on the thread that changes the value.
    Subscription subscribe(ValueChangeListener<T> listener);
}
