// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Stores a boolean value and normalizes nullable boxed writes to false.
@NotNullByDefault
public final class SimpleBooleanProperty extends AbstractProperty<Boolean> implements BooleanProperty {
    /// Creates an unnamed, ownerless false property.
    public SimpleBooleanProperty() {
        this(null, "", false);
    }

    /// Creates an unnamed, ownerless property with the supplied value.
    public SimpleBooleanProperty(boolean initialValue) {
        this(null, "", initialValue);
    }

    /// Creates a false property with metadata.
    public SimpleBooleanProperty(@Nullable Object bean, String name) {
        this(bean, name, false);
    }

    /// Creates a property with metadata and its initial value.
    public SimpleBooleanProperty(@Nullable Object bean, String name, boolean initialValue) {
        super(bean, name, initialValue);
    }

    /// Returns the non-null boxed value required by [BooleanProperty].
    @Override
    public Boolean getValue() {
        return Boolean.TRUE.equals(super.getValue());
    }

    /// Converts null boxed writes to false.
    @Override
    protected Boolean normalize(@Nullable Boolean candidate) {
        return Boolean.TRUE.equals(candidate);
    }
}
