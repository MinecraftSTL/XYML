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
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Provides local Java-runtime discovery and registration without coupling a settings page to legacy state APIs.
///
/// Implementations must not fetch Java distributions. The refresh operation scans local candidate paths only, and the
/// add operation validates one local executable or Java home chosen by the user.
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
    /// @return completion with the registered local runtime or its validation failure
    CompletionStage<JavaRuntime> addLocalRuntime(Path selectedPath);
}
