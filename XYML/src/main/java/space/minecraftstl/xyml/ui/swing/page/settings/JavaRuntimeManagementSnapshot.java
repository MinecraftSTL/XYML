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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.java.JavaRuntime;

import java.util.List;
import java.util.Objects;

/// Immutable local Java-runtime state rendered by [JavaManagementPanel].
///
/// @param initialized whether the first local discovery has completed
/// @param revision monotonically increasing runtime-discovery revision
/// @param runtimes sorted discovered local Java runtimes
@NotNullByDefault
public record JavaRuntimeManagementSnapshot(
        boolean initialized,
        long revision,
        @Unmodifiable List<JavaRuntime> runtimes) {
    /// Validates the revision and defensively copies the runtime list.
    public JavaRuntimeManagementSnapshot {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        runtimes = List.copyOf(Objects.requireNonNull(runtimes, "runtimes"));
    }
}
