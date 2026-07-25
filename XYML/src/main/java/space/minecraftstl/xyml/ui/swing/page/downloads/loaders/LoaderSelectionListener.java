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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;

/// Receives an immutable loader selection after the wizard adds, removes, or clears a loader.
///
/// The callback runs on Swing's event dispatch thread. Consumers that mutate non-Swing state should
/// snapshot the value immediately and schedule their own work outside that thread.
@FunctionalInterface
@NotNullByDefault
public interface LoaderSelectionListener {
    /// Receives the newest dependency-safe selected loader snapshot.
    ///
    /// @param snapshot immutable current selection
    void selectionChanged(LoaderSelectionSnapshot snapshot);
}
