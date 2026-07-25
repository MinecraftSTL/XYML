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
package space.minecraftstl.xyml.ui.launch;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.concurrent.CompletionStage;

/// Presents launch-time choices without exposing a desktop UI toolkit to launch preparation.
///
/// Implementations must accept calls from worker threads. Closing a prompt must resolve to the
/// request's explicit close action instead of leaving launch preparation suspended.
@NotNullByDefault
public interface LaunchInteraction {
    /// Presents one launch decision and eventually returns the selected semantic action.
    ///
    /// @param prompt immutable localized prompt and action description
    /// @return non-null completion that resolves once the prompt has been answered or closed
    CompletionStage<LaunchInteractionPrompt.Action> present(LaunchInteractionPrompt prompt);
}
