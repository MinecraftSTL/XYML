// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Defines a nullable string property with concise get and set aliases.
@NotNullByDefault
public interface StringProperty extends Property<String> {
    /// Returns the current text, or null when the property is empty.
    default @Nullable String get() {
        return getValue();
    }

    /// Replaces the current text.
    default void set(@Nullable String value) {
        setValue(value);
    }
}
