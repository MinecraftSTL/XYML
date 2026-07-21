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
