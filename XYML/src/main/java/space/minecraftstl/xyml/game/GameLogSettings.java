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
