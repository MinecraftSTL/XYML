// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
