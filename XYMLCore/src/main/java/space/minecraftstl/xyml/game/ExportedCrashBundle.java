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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Immutable diagnostic contents of one validated launcher crash export.
///
/// @param archive normalized source archive path
/// @param texts immutable unique texts in crash-report-first analysis order
@NotNullByDefault
public record ExportedCrashBundle(
        Path archive,
        @Unmodifiable List<ExportedCrashBundleText> texts) {
    /// Normalizes the source path and retains an immutable text snapshot.
    public ExportedCrashBundle {
        archive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        texts = List.copyOf(Objects.requireNonNull(texts, "texts"));
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("texts must not be empty");
        }
    }
}
