// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// A lazily resolved row exposed to the Swing list renderer.
///
/// @param index the stable data-source index represented by the row
/// @param status the current loading status
/// @param value the loaded value, present only in the loaded state
/// @param failure the request failure, present only in the error state
/// @param <T> the non-null choice value type
@NotNullByDefault
public record ChoiceListEntry<T extends Object>(
        int index,
        ChoiceLoadStatus status,
        @Nullable T value,
        @Nullable Throwable failure) {
    /// Validates that the payload matches the declared loading status.
    public ChoiceListEntry {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        if (status == ChoiceLoadStatus.LOADED && (value == null || failure != null)) {
            throw new IllegalArgumentException("A loaded entry must contain only a value");
        }
        if (status == ChoiceLoadStatus.ERROR && (value != null || failure == null)) {
            throw new IllegalArgumentException("An error entry must contain only a failure");
        }
        if (status == ChoiceLoadStatus.LOADING && (value != null || failure != null)) {
            throw new IllegalArgumentException("A loading entry must not contain a payload");
        }
    }

    /// Creates a loading row.
    ///
    /// @param index the stable data-source index
    /// @param <T> the choice value type
    /// @return a loading entry
    public static <T extends Object> ChoiceListEntry<T> loading(int index) {
        return new ChoiceListEntry<>(index, ChoiceLoadStatus.LOADING, null, null);
    }

    /// Creates a loaded row.
    ///
    /// @param index the stable data-source index
    /// @param value the loaded non-null value
    /// @param <T> the choice value type
    /// @return a loaded entry
    public static <T extends Object> ChoiceListEntry<T> loaded(int index, T value) {
        return new ChoiceListEntry<>(index, ChoiceLoadStatus.LOADED, value, null);
    }

    /// Creates a failed row.
    ///
    /// @param index the stable data-source index
    /// @param failure the load failure
    /// @param <T> the choice value type
    /// @return an error entry
    public static <T extends Object> ChoiceListEntry<T> failed(int index, Throwable failure) {
        return new ChoiceListEntry<>(index, ChoiceLoadStatus.ERROR, null, failure);
    }
}
