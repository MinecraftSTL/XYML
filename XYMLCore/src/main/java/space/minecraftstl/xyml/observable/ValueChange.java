// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable description of a value transition emitted by an observable model.
///
/// Both values are explicitly nullable so this type can represent initialization and clearing without sentinel
/// objects. The source identifies the model or property that emitted the transition.
@NotNullByDefault
public final class ValueChange<T> {
    /// The model or property that emitted this change.
    private final Object source;

    /// The value before the change, or null when no previous value existed.
    private final @Nullable T previousValue;

    /// The value after the change, or null when the value was cleared.
    private final @Nullable T currentValue;

    /// Creates a value change.
    public ValueChange(Object source, @Nullable T previousValue, @Nullable T currentValue) {
        this.source = Objects.requireNonNull(source, "source");
        this.previousValue = previousValue;
        this.currentValue = currentValue;
    }

    /// Returns the model or property that emitted this change.
    public Object source() {
        return source;
    }

    /// Returns the value before the change, or null when no previous value existed.
    public @Nullable T previousValue() {
        return previousValue;
    }

    /// Returns the value after the change, or null when the value was cleared.
    public @Nullable T currentValue() {
        return currentValue;
    }
}
