// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Adds stable bean and name metadata to an [ObservableValue].
@NotNullByDefault
public interface ReadOnlyProperty<T> extends ObservableValue<T> {
    /// Returns the owning model object, or null when the property has no owner.
    @Nullable Object getBean();

    /// Returns the property name, which is empty when no name was supplied.
    String getName();
}
