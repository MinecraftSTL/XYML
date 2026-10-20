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

import java.util.List;
import java.util.Objects;

/// One unique diagnostic text safely read from an exported crash bundle.
///
/// @param kind semantic kind used to preserve crash-report-first analysis order
/// @param content decoded diagnostic text
/// @param sources immutable archive entry names containing this exact text
@NotNullByDefault
public record ExportedCrashBundleText(
        Kind kind,
        String content,
        @Unmodifiable List<String> sources) {
    /// Copies and validates the immutable text description.
    public ExportedCrashBundleText {
        kind = Objects.requireNonNull(kind, "kind");
        content = Objects.requireNonNull(content, "content");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must contain diagnostic text");
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
    }

    /// Classifies a supported exported bundle text entry.
    @NotNullByDefault
    public enum Kind {
        /// A dedicated Minecraft crash report from `crash-reports`.
        CRASH_REPORT,

        /// A launcher, game, or mod-loader log.
        LOG
    }
}
