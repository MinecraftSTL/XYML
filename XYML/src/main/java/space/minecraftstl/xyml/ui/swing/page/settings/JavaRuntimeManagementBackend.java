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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.java.JavaRuntimeSnapshot;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.collection.CollectionChangeListener;
import space.minecraftstl.xyml.observable.collection.SetChange;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.task.Task;

import java.nio.file.Path;
import java.util.Set;

/// Isolates process-wide Java and settings state from the testable runtime lifecycle policy.
///
/// Task-producing methods must return stopped tasks and imperative mutation methods are called only from a service
/// task body.
@NotNullByDefault
interface JavaRuntimeManagementBackend {
    /// Returns the observable process-wide runtime discovery state.
    ObservableValue<JavaRuntimeSnapshot> runtimeSnapshots();

    /// Returns a thread-safe immutable snapshot of disabled executable path strings.
    @Unmodifiable Set<String> disabledJavaPathsSnapshot();

    /// Subscribes to immutable incremental changes from the disabled path set.
    ///
    /// @param listener listener receiving exact additions and removals
    /// @return independently removable change subscription
    Subscription subscribeDisabledJavaPaths(CollectionChangeListener<SetChange<String>> listener);

    /// Returns whether user Java settings may currently be changed.
    boolean isWritable();

    /// Starts a local runtime rescan.
    void refreshLocalRuntimes();

    /// Creates a stopped task that validates and registers one local executable.
    ///
    /// @param binary canonical or normalized executable candidate
    /// @return stopped registration task
    Task<JavaRuntime> addLocalRuntime(Path binary);

    /// Probes one selected configured path and returns its explicit inspection result.
    ///
    /// @param configuredPath original disabled path text
    /// @return available or invalid inspection result preserving the original text
    /// @throws InterruptedException if Java discovery initialization or probing is interrupted
    DisabledJavaRuntimeEntry inspectDisabledRuntime(String configuredPath) throws InterruptedException;

    /// Disables one unmanaged runtime in settings and removes it from the live registry.
    ///
    /// @param runtime unmanaged runtime to disable
    /// @throws InterruptedException if registry initialization is interrupted
    void disableLocalRuntime(JavaRuntime runtime) throws InterruptedException;

    /// Creates a stopped task that removes one launcher-managed runtime installation.
    ///
    /// @param runtime managed runtime to uninstall
    /// @return stopped uninstall task
    Task<@Nullable Void> uninstallManagedRuntime(JavaRuntime runtime);

    /// Removes one exact disabled path from user settings.
    ///
    /// @param configuredPath original disabled path text
    /// @return whether the disabled set changed
    boolean removeDisabledRuntime(String configuredPath);
}
