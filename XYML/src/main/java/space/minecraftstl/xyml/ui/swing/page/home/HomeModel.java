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
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Supplies launcher-home state and commands without exposing JavaFX or Swing types.
@NotNullByDefault
public interface HomeModel {
    /// Returns the latest immutable launcher-home state.
    ///
    /// @return current home snapshot
    HomeSnapshot snapshot();

    /// Registers for future home-state invalidations on the publishing thread.
    ///
    /// Concurrent changes may coalesce before delivery. Consumers must read [#snapshot()] rather than treating an
    /// event payload as newer than a snapshot already observed on another thread. One listener's runtime failure is
    /// isolated from later registrations, while an [Error] is propagated unchanged.
    ///
    /// @param listener snapshot transition listener
    /// @return independently cancellable listener registration
    Subscription subscribe(ValueChangeListener<HomeSnapshot> listener);

    /// Returns the latest launch session for task presentation, or an empty value before the first launch.
    ///
    /// A terminal session remains available until the next launch so failure details do not disappear immediately.
    ///
    /// @return read-only optional launch-session property
    ReadOnlyProperty<Optional<LaunchSession>> launchSessionProperty();

    /// Opens the account-selection workflow.
    void selectAccount();

    /// Opens the instance-selection workflow.
    void selectInstance();

    /// Opens the new-instance workflow.
    void addInstance();

    /// Starts the selected instance with the selected account.
    void launch();

    /// Exports a standalone script for the selected account and instance.
    ///
    /// The caller must provide a local destination chosen through a native UI boundary. The returned stage completes
    /// on an implementation-owned worker after launch preparation and script writing finish.
    ///
    /// @param scriptFile local destination script path
    /// @return completion stage yielding the exact generated script path
    default CompletionStage<Path> exportLaunchScript(Path scriptFile) {
        Objects.requireNonNull(scriptFile, "scriptFile");
        return CompletableFuture.failedFuture(new IllegalStateException("Launch-script export is unavailable"));
    }
}
