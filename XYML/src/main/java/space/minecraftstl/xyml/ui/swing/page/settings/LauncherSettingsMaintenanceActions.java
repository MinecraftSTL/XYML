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
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.update.SwingUpdateCheckService;
import space.minecraftstl.xyml.ui.swing.update.SwingUpdatePromptPresenter;
import space.minecraftstl.xyml.ui.swing.update.UpdateCheckRequest;
import space.minecraftstl.xyml.ui.swing.update.UpdateCheckResult;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.awt.Component;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/// Production settings-maintenance actions backed by the Swing update service and launcher cache layout.
@NotNullByDefault
final class LauncherSettingsMaintenanceActions implements SettingsMaintenanceActions {
    /// Update-check service owned for this settings-center lifetime.
    private final SwingUpdateCheckService updateCheckService;

    /// Native presenter used when a manual check finds an available release.
    private final SwingUpdatePromptPresenter updatePromptPresenter;

    /// Worker receiving cache filesystem operations.
    private final Executor cacheExecutor;

    /// Prevents maintenance work from starting after resource release.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates production actions owned by one settings center.
    ///
    /// @param owner stable component used as the native update-dialog owner
    /// @return production maintenance actions
    static LauncherSettingsMaintenanceActions create(Component owner) {
        Objects.requireNonNull(owner, "owner");
        SwingUpdateCheckService updateCheckService = SwingUpdateCheckService.production();
        try {
            return new LauncherSettingsMaintenanceActions(
                    updateCheckService,
                    SwingUpdatePromptPresenter.production(
                            () -> owner,
                            URI.create(Metadata.MANUAL_UPDATE_URL),
                            Schedulers.io()),
                    Schedulers.io());
        } catch (RuntimeException | Error failure) {
            updateCheckService.close();
            throw failure;
        }
    }

    /// Creates injectable maintenance actions.
    ///
    /// The supplied update service remains owned by this object and is closed by [#close()]. The cache executor is
    /// externally owned and remains available after closure.
    ///
    /// @param updateCheckService update service to own
    /// @param updatePromptPresenter presenter for available releases
    /// @param cacheExecutor worker for cache filesystem operations
    LauncherSettingsMaintenanceActions(
            SwingUpdateCheckService updateCheckService,
            SwingUpdatePromptPresenter updatePromptPresenter,
            Executor cacheExecutor) {
        this.updateCheckService = Objects.requireNonNull(updateCheckService, "updateCheckService");
        this.updatePromptPresenter = Objects.requireNonNull(updatePromptPresenter, "updatePromptPresenter");
        this.cacheExecutor = Objects.requireNonNull(cacheExecutor, "cacheExecutor");
    }

    /// Checks for an update and completes after any available-release prompt has finished.
    ///
    /// @param request exact release-channel and preview selection
    /// @return successful update result after presentation
    @Override
    public CompletionStage<UpdateCheckResult> checkForUpdates(UpdateCheckRequest request) {
        requireOpen();
        return updateCheckService.check(Objects.requireNonNull(request, "request"))
                .thenCompose(result -> updatePromptPresenter.present(result).thenApply(ignored -> result));
    }

    /// Cleans only the `cache` child of the supplied common directory on the configured worker.
    ///
    /// @param commonDirectory effective launcher common directory
    /// @return stage completed with whether cleanup succeeded
    @Override
    public CompletionStage<Boolean> clearCache(Path commonDirectory) {
        requireOpen();
        Path cacheDirectory = Objects.requireNonNull(commonDirectory, "commonDirectory").resolve("cache");
        return CompletableFuture.supplyAsync(
                () -> FileUtils.cleanDirectoryQuietly(cacheDirectory),
                cacheExecutor);
    }

    /// Closes the owned update service and rejects future maintenance requests.
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            updateCheckService.close();
        }
    }

    /// Rejects an action after this settings-center resource has closed.
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Settings maintenance actions are closed");
        }
    }
}
