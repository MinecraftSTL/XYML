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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.ModpackHelper;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Production remote-modpack task factory backed by FileDownloadTask and ModpackHelper.
///
/// The generated graph downloads only after the user presses Install. It uses a temporary archive,
/// parses the provider manifest away from the EDT, delegates actual installation to the established
/// Core helper, clears a failed provisional mark, and deletes the temporary archive on every terminal path.
@NotNullByDefault
public final class DefaultRemoteModpackInstallLauncher implements RemoteModpackInstallLauncher {
    /// Launcher-wide download provider used for candidate URL injection and artifact download.
    private final DownloadProvider downloadProvider;

    /// Creates an installation launcher using the current launcher-wide download provider.
    public DefaultRemoteModpackInstallLauncher() {
        this(DownloadProviders.getDownloadProvider());
    }

    /// Creates an installation launcher using an explicit download provider.
    ///
    /// @param downloadProvider provider used to generate candidate archive URLs
    public DefaultRemoteModpackInstallLauncher(DownloadProvider downloadProvider) {
        this.downloadProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
    }

    /// Builds the true download, manifest-read, and ModpackHelper install chain for one selected version.
    ///
    /// @param request selected source project, exact archive version, and destination identifier
    /// @return unstarted task chain that owns temporary-archive cleanup
    /// @throws IOException when the operating system cannot create a temporary archive
    @Override
    public Task<?> createInstallTask(RemoteModpackInstallRequest request) throws IOException {
        RemoteModpackInstallRequest installRequest = Objects.requireNonNull(request, "request");
        if (!XYMLGameRepository.isValidInstanceId(installRequest.instanceName())) {
            throw new IllegalArgumentException("Invalid remote modpack instance name");
        }

        XYMLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (repository.instanceIdConflicts(installRequest.instanceName())) {
            throw new IllegalArgumentException("Remote modpack instance name already exists");
        }

        Path archive = Files.createTempFile("xyml-remote-modpack-", ".zip");
        FileDownloadTask download = new FileDownloadTask(
                downloadProvider.injectURLWithCandidates(installRequest.version().file().url()),
                archive,
                installRequest.version().file().getIntegrityCheck());
        download.addIntegrityCheckHandler(FileDownloadTask.ZIP_INTEGRITY_CHECK_HANDLER);
        return download
                .thenComposeAsync(Schedulers.io(), ignored -> createInstallTask(repository, installRequest, archive))
                .whenComplete(Schedulers.io(), failure -> cleanupTerminalState(
                        repository,
                        installRequest.instanceName(),
                        archive,
                        failure));
    }

    /// Parses the completed archive and delegates provider-specific installation to the shared helper.
    ///
    /// @param repository destination repository captured before task construction
    /// @param request immutable selected project and version request
    /// @param archive completed remote archive
    /// @return provider-specific installation task
    /// @throws Exception when the archive is unsupported or cannot be parsed
    private static Task<?> createInstallTask(
            XYMLGameRepository repository,
            RemoteModpackInstallRequest request,
            Path archive) throws Exception {
        Modpack modpack = ModpackHelper.readModpackManifest(archive, StandardCharsets.UTF_8);
        return ModpackHelper.getInstallTask(
                repository,
                archive,
                request.instanceName(),
                modpack,
                request.item().addon().iconUrl());
    }

    /// Clears unfinished repository state and removes the temporary archive after task termination.
    ///
    /// @param repository destination repository captured for this task
    /// @param instanceName destination instance identifier
    /// @param archive temporary remote archive
    /// @param failure terminal task failure, or null after success
    /// @throws IOException when the temporary archive cannot be deleted
    private static void cleanupTerminalState(
            XYMLGameRepository repository,
            String instanceName,
            Path archive,
            @Nullable Throwable failure) throws IOException {
        if (failure != null) {
            repository.undoMark(instanceName);
        }
        Files.deleteIfExists(archive);
    }
}
