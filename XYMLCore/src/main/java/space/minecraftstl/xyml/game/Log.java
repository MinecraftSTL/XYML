// SPDX-FileCopyrightText: Copyright (C) 2024 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.util.Log4jLevel;

import java.util.Objects;

/// One decoded game-process log line with a lazily inferred severity.
@NotNullByDefault
public final class Log {
    /// Decoded line text.
    private final String log;

    /// Explicit or lazily inferred severity, or null before inference.
    private @Nullable Log4jLevel level;

    /// Creates a log line whose severity will be inferred when requested.
    ///
    /// @param log decoded line text
    public Log(String log) {
        this(log, null);
    }

    /// Creates a log line with an optional known severity.
    ///
    /// @param log decoded line text
    /// @param level known severity, or null to infer it lazily
    public Log(String log, @Nullable Log4jLevel level) {
        this.log = Objects.requireNonNull(log, "log");
        this.level = level;
    }

    /// Returns the decoded line text.
    ///
    /// @return decoded line text
    public String getLog() {
        return log;
    }

    /// Returns the explicit or inferred severity.
    ///
    /// @return non-null log severity
    public Log4jLevel getLevel() {
        @Nullable Log4jLevel resolvedLevel = level;
        if (resolvedLevel == null) {
            resolvedLevel = Log4jLevel.guessLevel(log);
            if (resolvedLevel == null) {
                resolvedLevel = Log4jLevel.INFO;
            }
            level = resolvedLevel;
        }
        return resolvedLevel;
    }

    /// Returns the decoded line text for list and diagnostic rendering.
    ///
    /// @return decoded line text
    @Override
    public String toString() {
        return log;
    }
}
