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
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.util.concurrent.CompletionStage;

/// Starts a launcher restart requested from one settings component.
///
/// Implementations must complete only after pending settings are durable, a replacement process has started,
/// and the current owning window has been asked to close. Failures must leave the current window open.
@FunctionalInterface
@NotNullByDefault
public interface SettingsRestartCommand {
    /// Starts one nonblocking restart operation.
    ///
    /// @param owner settings component whose owning window must close after the replacement process starts
    /// @return completion after the restart sequence reaches its terminal state
    CompletionStage<@Nullable Void> restart(Component owner);
}
