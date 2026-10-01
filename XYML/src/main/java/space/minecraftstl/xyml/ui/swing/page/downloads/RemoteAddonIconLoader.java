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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.Icon;
import java.util.concurrent.CompletionStage;

/// Asynchronously loads one remote add-on icon without exposing network details to Swing renderers.
@NotNullByDefault
@FunctionalInterface
interface RemoteAddonIconLoader {
    /// Starts loading one validated or unvalidated provider icon URL away from the EDT.
    ///
    /// Implementations must complete the stage with a stable failure icon rather than propagate
    /// ordinary network or image-decoding failures to the result list.
    ///
    /// @param rawUrl provider icon URL
    /// @return asynchronous fixed-size icon result
    CompletionStage<Icon> load(String rawUrl);
}
