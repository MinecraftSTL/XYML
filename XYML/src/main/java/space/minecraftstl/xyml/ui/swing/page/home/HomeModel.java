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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;

/// Supplies launcher-home state and commands without exposing JavaFX or Swing types.
@NotNullByDefault
public interface HomeModel {
    /// Returns the latest immutable launcher-home state.
    ///
    /// @return current home snapshot
    HomeSnapshot snapshot();

    /// Registers for future home-state transitions on the publishing thread.
    ///
    /// @param listener snapshot transition listener
    /// @return independently cancellable listener registration
    Subscription subscribe(ValueChangeListener<HomeSnapshot> listener);

    /// Opens the account-selection workflow.
    void selectAccount();

    /// Opens the instance-selection workflow.
    void selectInstance();

    /// Opens the new-instance workflow.
    void addInstance();

    /// Starts the selected instance with the selected account.
    void launch();
}
