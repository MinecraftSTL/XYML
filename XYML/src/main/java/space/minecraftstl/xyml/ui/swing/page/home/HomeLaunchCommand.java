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
package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.game.launch.LaunchSession;

/// Starts one launch session from the immutable identities captured by the home model.
@FunctionalInterface
@NotNullByDefault
public interface HomeLaunchCommand {
    /// Starts preparing the supplied captured request.
    ///
    /// @param request stable account, game-directory, and instance identifiers captured at command time
    /// @return the session representing this exact launch request
    LaunchSession launch(LaunchRequest request);
}
