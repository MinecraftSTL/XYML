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
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.util.FileSaver;
import space.minecraftstl.xyml.util.Restarter;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Window;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/// Production settings restart command with an explicit persistence and process-lifecycle order.
@NotNullByDefault
public final class LauncherSettingsRestartCommand implements SettingsRestartCommand {
    /// Worker executor used for the blocking save barrier and process launch.
    private final Executor workerExecutor;

    /// UI executor used only for disposing the owning Swing window.
    private final Executor uiExecutor;

    /// Blocking barrier for every settings save issued before the restart click.
    private final RestartAction waitForPendingSaves;

    /// Starts the replacement packaged application or Java process.
    private final RestartAction startReplacementProcess;

    /// Disposes the current window after the replacement process starts successfully.
    private final Consumer<Component> closeOwningWindow;

    /// Creates the production command using the shared I/O executor and launcher utilities.
    ///
    /// @return restart command for a live launcher window
    public static LauncherSettingsRestartCommand create() {
        return new LauncherSettingsRestartCommand(
                Schedulers.io(),
                SwingUiDispatcher.INSTANCE::dispatch,
                FileSaver::waitForAllSaves,
                Restarter::restartSelf,
                LauncherSettingsRestartCommand::disposeOwningWindow);
    }

    /// Creates an injectable command for deterministic lifecycle tests.
    ///
    /// @param workerExecutor executor for blocking restart preparation
    /// @param uiExecutor executor for window disposal
    /// @param waitForPendingSaves persistence completion barrier
    /// @param startReplacementProcess replacement-process launcher
    /// @param closeOwningWindow current-window disposal action
    LauncherSettingsRestartCommand(
            Executor workerExecutor,
            Executor uiExecutor,
            RestartAction waitForPendingSaves,
            RestartAction startReplacementProcess,
            Consumer<Component> closeOwningWindow) {
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
        this.waitForPendingSaves = Objects.requireNonNull(waitForPendingSaves, "waitForPendingSaves");
        this.startReplacementProcess = Objects.requireNonNull(
                startReplacementProcess,
                "startReplacementProcess");
        this.closeOwningWindow = Objects.requireNonNull(closeOwningWindow, "closeOwningWindow");
    }

    /// Schedules the persistence barrier and process launch away from the Swing event dispatch thread.
    ///
    /// @param owner settings component inside the current application window
    /// @return completion after successful disposal or the first failed restart step
    @Override
    public CompletionStage<@Nullable Void> restart(Component owner) {
        Component validatedOwner = Objects.requireNonNull(owner, "owner");
        CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        try {
            workerExecutor.execute(() -> executeRestart(validatedOwner, completion));
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    /// Runs blocking restart preparation in strict order and schedules terminal window disposal.
    ///
    /// @param owner component whose owning window must close
    /// @param completion restart completion to resolve
    private void executeRestart(Component owner, CompletableFuture<@Nullable Void> completion) {
        try {
            waitForPendingSaves.run();
            startReplacementProcess.run();
            uiExecutor.execute(() -> closeWindow(owner, completion));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            completion.completeExceptionally(failure);
        }
    }

    /// Disposes the current window and resolves the command only after disposal returns.
    ///
    /// @param owner component whose ancestor is the active launcher frame
    /// @param completion restart completion to resolve
    private void closeWindow(Component owner, CompletableFuture<@Nullable Void> completion) {
        try {
            closeOwningWindow.accept(owner);
            completion.complete(null);
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
    }

    /// Disposes the nearest owning Swing window, invoking `AppShellFrame#dispose()` when hosted by the shell.
    ///
    /// @param owner settings component attached to the active launcher window
    private static void disposeOwningWindow(Component owner) {
        @Nullable Window window = SwingUtilities.getWindowAncestor(Objects.requireNonNull(owner, "owner"));
        if (window == null) {
            throw new IllegalStateException("Settings restart control has no owning window");
        }
        window.dispose();
    }

    /// Checked restart step used for both persistence waiting and replacement-process startup.
    @FunctionalInterface
    @NotNullByDefault
    interface RestartAction {
        /// Runs one ordered restart step.
        ///
        /// @throws Exception when the step cannot complete
        void run() throws Exception;
    }
}
