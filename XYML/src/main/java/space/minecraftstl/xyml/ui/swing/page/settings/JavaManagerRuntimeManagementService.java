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
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.java.JavaRuntimeSnapshot;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Adapts process-wide [JavaManager] local discovery to the embeddable Java management page.
///
/// The adapter deliberately exposes only local scanning and registration. It never invokes Java download or install
/// operations, while registration continues to use the existing manager so user-selected paths are persisted through
/// the established launcher settings workflow.
@NotNullByDefault
public final class JavaManagerRuntimeManagementService implements JavaRuntimeManagementService {
    /// Process-wide legacy observable that publishes discovered Java runtime snapshots.
    private final ObservableValue<JavaRuntimeSnapshot> legacySnapshots;

    /// Creates an adapter backed by the process-wide Java manager.
    public JavaManagerRuntimeManagementService() {
        this(JavaManager.getAllJavaSnapshotObservable());
    }

    /// Creates an adapter around one Java-runtime snapshot observable.
    ///
    /// @param legacySnapshots legacy local-runtime snapshot source
    JavaManagerRuntimeManagementService(ObservableValue<JavaRuntimeSnapshot> legacySnapshots) {
        this.legacySnapshots = Objects.requireNonNull(legacySnapshots, "legacySnapshots");
    }

    /// Returns the latest normalized Java runtime-management snapshot.
    ///
    /// @return current local Java runtime state
    @Override
    public JavaRuntimeManagementSnapshot snapshot() {
        return mapSnapshot(legacySnapshots.getValue());
    }

    /// Maps legacy property changes to settings-page snapshots without changing delivery threads.
    ///
    /// @param listener settings-page listener
    /// @return independently removable mapped subscription
    @Override
    public Subscription subscribe(ValueChangeListener<JavaRuntimeManagementSnapshot> listener) {
        ValueChangeListener<JavaRuntimeManagementSnapshot> target = Objects.requireNonNull(listener, "listener");
        return legacySnapshots.subscribe(change -> target.onChange(new ValueChange<>(
                this,
                mapNullableSnapshot(change.previousValue()),
                mapNullableSnapshot(change.currentValue()))));
    }

    /// Starts the legacy manager's local Java path rescan.
    @Override
    public void refreshLocalRuntimes() {
        JavaManager.refresh();
    }

    /// Runs the existing local Java validation task and adapts its terminal result to a completion stage.
    ///
    /// @param selectedPath selected executable or Java home directory
    /// @return completion with the registered runtime or validation failure
    @Override
    public CompletionStage<JavaRuntime> addLocalRuntime(Path selectedPath) {
        Path executable = resolveExecutable(Objects.requireNonNull(selectedPath, "selectedPath"));
        CompletableFuture<JavaRuntime> completion = new CompletableFuture<>();
        Task<JavaRuntime> operation = JavaManager.getAddJavaTask(executable);
        Task<@Nullable Void> terminal = operation.whenComplete(
                Schedulers.ui(),
                () -> completeRuntimeResult(operation, completion),
                completion::completeExceptionally);
        try {
            terminal.start();
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    /// Returns a Java executable from either an executable path or a selected Java home directory.
    ///
    /// @param selectedPath user-selected candidate path
    /// @return executable candidate passed to JavaManager validation
    private static Path resolveExecutable(Path selectedPath) {
        Path normalized = selectedPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            return normalized;
        }
        Path executable = JavaManager.getExecutable(normalized);
        if (Files.isRegularFile(executable)) {
            return executable;
        }
        Path macExecutable = JavaManager.getMacExecutable(normalized);
        return Files.isRegularFile(macExecutable) ? macExecutable : executable;
    }

    /// Completes a stage from a successful existing Java registration task.
    ///
    /// @param operation completed registration task
    /// @param completion target completion stage
    private static void completeRuntimeResult(Task<JavaRuntime> operation, CompletableFuture<JavaRuntime> completion) {
        @Nullable JavaRuntime runtime = operation.getResult();
        if (runtime == null) {
            completion.completeExceptionally(new IllegalStateException("Java registration completed without a runtime"));
        } else {
            completion.complete(runtime);
        }
    }

    /// Maps a possibly absent legacy snapshot to a non-null empty-or-populated settings snapshot.
    ///
    /// @param legacySnapshot legacy snapshot, or null before its first publication
    /// @return non-null settings-page snapshot
    private static JavaRuntimeManagementSnapshot mapSnapshot(@Nullable JavaRuntimeSnapshot legacySnapshot) {
        if (legacySnapshot == null) {
            return new JavaRuntimeManagementSnapshot(false, 0L, List.of());
        }
        return new JavaRuntimeManagementSnapshot(
                legacySnapshot.isInitialized(),
                legacySnapshot.getRevision(),
                legacySnapshot.getRuntimes());
    }

    /// Maps a nullable legacy change value while preserving a null absence marker.
    ///
    /// @param legacySnapshot legacy transition value, or null
    /// @return mapped transition value, or null
    private static @Nullable JavaRuntimeManagementSnapshot mapNullableSnapshot(
            @Nullable JavaRuntimeSnapshot legacySnapshot) {
        return legacySnapshot == null ? null : mapSnapshot(legacySnapshot);
    }
}
