// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
