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
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.ModpackHelper;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.modpack.ModpackConfiguration;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Downloads one selected repository archive and updates a fixed existing modpack instance.
///
/// The launcher rejects target substitution, creates no provisional instance, delegates provider compatibility to
/// [ModpackHelper], and removes its temporary archive on every terminal path.
@NotNullByDefault
public final class ExistingInstanceRemoteModpackUpdateLauncher implements RemoteModpackInstallLauncher {
    /// Repository containing the fixed update target.
    private final XYMLGameRepository repository;

    /// Existing instance that receives every selected repository version.
    private final GameInstanceID instanceId;

    /// Launcher-wide download provider used for candidate URL injection.
    private final DownloadProvider downloadProvider;

    /// Creates an update launcher using the current launcher-wide download provider.
    ///
    /// @param repository repository containing the existing modpack
    /// @param instanceId fixed existing modpack identifier
    public ExistingInstanceRemoteModpackUpdateLauncher(
            XYMLGameRepository repository,
            GameInstanceID instanceId) {
        this(repository, instanceId, DownloadProviders.getDownloadProvider());
    }

    /// Creates an update launcher with an explicit download provider.
    ///
    /// @param repository repository containing the existing modpack
    /// @param instanceId fixed existing modpack identifier
    /// @param downloadProvider provider used to generate candidate archive URLs
    ExistingInstanceRemoteModpackUpdateLauncher(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            DownloadProvider downloadProvider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.downloadProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
    }

    /// Builds a download and provider-validated update chain for the fixed existing target.
    ///
    /// @param request selected repository project and exact archive version
    /// @return unstarted update task that owns temporary-archive cleanup
    /// @throws IOException when temporary archive preparation cannot start
    @Override
    public Task<?> createInstallTask(RemoteModpackInstallRequest request) throws IOException {
        RemoteModpackInstallRequest updateRequest = Objects.requireNonNull(request, "request");
        if (!instanceId.equals(updateRequest.instanceId())) {
            throw new IllegalArgumentException("Remote modpack update target does not match the fixed instance");
        }
        if (!repository.hasInstance(instanceId) || !repository.isModpack(instanceId)) {
            throw new IllegalStateException("The fixed instance is not an updateable modpack");
        }

        Path archive = Files.createTempFile("xyml-remote-modpack-update-", ".zip");
        FileDownloadTask download = new FileDownloadTask(
                downloadProvider.injectURLWithCandidates(updateRequest.version().file().url()),
                archive,
                updateRequest.version().file().getIntegrityCheck());
        download.setName(updateRequest.version().name().isBlank()
                ? updateRequest.version().file().filename()
                : updateRequest.version().name());
        download.addIntegrityCheckHandler(FileDownloadTask.ZIP_INTEGRITY_CHECK_HANDLER);
        return download
                .thenComposeAsync(Schedulers.io(), ignored -> createUpdateTask(archive))
                .whenComplete(Schedulers.io(), failure -> Files.deleteIfExists(archive));
    }

    /// Reads the installed provider configuration and delegates compatibility checks to the shared helper.
    ///
    /// @param archive downloaded remote archive
    /// @return provider-specific update task
    /// @throws Exception when the archive or persisted provider configuration cannot be used
    private Task<?> createUpdateTask(Path archive) throws Exception {
        ModpackConfiguration<?> configuration = ModpackHelper.readModpackConfiguration(
                repository.getModpackConfiguration(instanceId));
        return ModpackHelper.getUpdateTask(
                repository,
                Objects.requireNonNull(archive, "archive"),
                StandardCharsets.UTF_8,
                instanceId,
                configuration);
    }
}
