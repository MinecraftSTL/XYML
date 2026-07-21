/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
