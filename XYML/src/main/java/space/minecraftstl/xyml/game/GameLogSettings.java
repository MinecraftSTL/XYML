// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static space.minecraftstl.xyml.setting.SettingsManager.settings;

/// Resolves the persisted game-log retention limit in the application module.
@NotNullByDefault
public final class GameLogSettings {
    /// Default retained line count used when the persisted value is absent or invalid.
    public static final int DEFAULT_LOG_LINES = 2000;

    /// Prevents construction of this static settings adapter.
    private GameLogSettings() {
    }

    /// Returns the positive persisted game-log line limit or the default value.
    ///
    /// @return positive retained line count
    public static int getLogLines() {
        @Nullable Integer lines = settings().logLinesProperty().get();
        return lines != null && lines > 0 ? lines : DEFAULT_LOG_LINES;
    }
}
