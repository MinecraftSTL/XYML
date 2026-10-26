// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.task.Task;

import java.nio.file.Path;

/// Provides Java-runtime discovery and lifecycle operations without coupling settings pages to process-wide state.
@NotNullByDefault
public interface JavaRuntimeManagementService {
    /// Returns the latest discovered local Java-runtime snapshot.
    ///
    /// @return latest immutable runtime-management state
    JavaRuntimeManagementSnapshot snapshot();

    /// Registers for local Java-runtime snapshot changes.
    ///
    /// @param listener listener receiving local discovery transitions
    /// @return independently removable listener registration
    Subscription subscribe(ValueChangeListener<JavaRuntimeManagementSnapshot> listener);

    /// Starts a local rescan of Java runtime paths without downloading anything.
    void refreshLocalRuntimes();

    /// Validates and registers a local Java executable or Java home directory.
    ///
    /// @param selectedPath Java executable or Java home selected by the user
    /// @return stopped task yielding the registered local runtime or its validation failure
    Task<JavaRuntime> addLocalRuntime(Path selectedPath);

    /// Creates a stopped task that hides one unmanaged runtime from discovery.
    ///
    /// @param runtime unmanaged runtime to disable
    /// @return stopped disable task
    Task<@Nullable Void> disableLocalRuntime(JavaRuntime runtime);

    /// Creates a stopped task that unregisters and deletes one launcher-managed runtime.
    ///
    /// @param runtime managed runtime to uninstall
    /// @return stopped uninstall task
    Task<@Nullable Void> uninstallManagedRuntime(JavaRuntime runtime);

    /// Creates a stopped background task that inspects one selected disabled path.
    ///
    /// @param disabledRuntime disabled entry selected by the user
    /// @return stopped task yielding an available or invalid inspected entry
    Task<DisabledJavaRuntimeEntry> inspectDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime);

    /// Creates a stopped task that validates and restores one disabled executable.
    ///
    /// @param disabledRuntime disabled executable entry to restore
    /// @return stopped task yielding the restored runtime
    Task<JavaRuntime> restoreDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime);

    /// Creates a stopped task that forcibly forgets one exact disabled path.
    ///
    /// @param disabledRuntime disabled executable entry to remove after a missing path or failed restore
    /// @return stopped removal task
    Task<@Nullable Void> removeDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime);
}
