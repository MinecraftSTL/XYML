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
package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Task;

import java.util.List;

/// Describes an actionable repair without depending on a presentation toolkit.
@NotNullByDefault
public interface Solver {
    /// Returns the localization key used by launcher presentation layers.
    ///
    /// @return stable localization key
    String messageKey();

    /// Returns immutable non-null arguments for the localized repair text.
    ///
    /// @return immutable formatting arguments
    @Unmodifiable List<Object> messageArguments();

    /// Returns a presentation-independent English repair description.
    ///
    /// @return fallback repair text suitable for Core and MCP callers
    String fallbackMessage();

    /// Creates an optional executable repair task.
    ///
    /// A fresh task must be returned for every invocation. Text-only solvers return null.
    ///
    /// @return executable repair task, or null when the repair requires user action
    default @Nullable Task<?> createTask() {
        return null;
    }
}
